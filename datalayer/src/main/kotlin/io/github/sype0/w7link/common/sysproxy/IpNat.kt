// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common.sysproxy

import io.github.sype0.w7link.common.TunNat
import java.io.Closeable
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Gives IP packets from the watch somewhere to go without a tun device of this side's own: every
 * TCP connection the watch opens is answered here, as a TCP peer written by hand, and carried to
 * its destination over an ordinary socket; UDP datagrams go out through a socket per flow; a ping
 * is answered on the spot. IPv4 only, as that is all the watch routes this way. The link to the
 * watch loses nothing and keeps order, so this peer's TCP can stay small: it resends only when an
 * ack is long overdue.
 *
 * [write] takes a packet for the watch and must not block; [allowed] says whether a destination
 * may be connected to.
 */
class IpNat(
    private val write: (ByteArray) -> Unit,
    private val allowed: (InetAddress) -> Boolean = { true },
) : Closeable {
    private data class Key(val srcPort: Int, val dst: Int, val dstPort: Int)

    private val tcp = ConcurrentHashMap<Key, TcpFlow>()
    private val udp = ConcurrentHashMap<Key, UdpFlow>()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "w7link-nat-timer").apply { isDaemon = true } }
    private val random = SecureRandom()
    private val ipId = AtomicInteger(random.nextInt())

    @Volatile
    private var closed = false

    init {
        timer.scheduleWithFixedDelay({ sweep() }, SWEEP_MS, SWEEP_MS, TimeUnit.MILLISECONDS)
    }

    val flows: Int
        get() = tcp.size + udp.size

    /** One IP packet from the watch. Quick: the work happens on the flows' own threads. */
    fun onPacket(packet: ByteArray) {
        if (closed || packet.size < IP_HEADER || packet[0].toInt() ushr 4 and 0xf != 4) return
        val header = (packet[0].toInt() and 0xf) * 4
        val total = TunNat.short(packet, 2)
        if (header < IP_HEADER || total > packet.size || TunNat.short(packet, 6) and 0x3fff != 0) return
        when (packet[9].toInt()) {
            TCP -> if (total >= header + TCP_HEADER) onTcp(packet, header, total)
            UDP -> if (total >= header + UDP_HEADER) onUdp(packet, header, total)
            ICMP -> if (total >= header + 8 && packet[header].toInt() == ICMP_ECHO) onPing(packet, header, total)
        }
    }

    override fun close() {
        closed = true
        timer.shutdownNow()
        tcp.values.toList().forEach { it.abort(tell = false) }
        udp.values.toList().forEach { it.close() }
    }

    private fun sweep() {
        val now = System.nanoTime()
        udp.values.toList().forEach { if (now - it.lastUsed > UDP_IDLE_NS) it.close() }
        tcp.values.toList().forEach { if (now - it.lastUsed > TCP_IDLE_NS) it.abort(tell = true) }
    }

    // --- TCP ---

    private fun onTcp(packet: ByteArray, header: Int, total: Int) {
        val key = Key(TunNat.short(packet, header), TunNat.int(packet, 16), TunNat.short(packet, header + 2))
        val flags = packet[header + 13].toInt()
        val flow = tcp[key]
        if (flow != null) {
            flow.onSegment(packet, header, total, flags)
            return
        }
        if (flags and SYN == 0 || flags and ACK != 0) {
            // A connection we know nothing about: tell the watch so, unless it is only a reset.
            if (flags and RST == 0) {
                val seq = TunNat.int(packet, header + 4)
                val length = total - header - (packet[header + 12].toInt() ushr 4 and 0xf) * 4 + (if (flags and SYN != 0) 1 else 0) + (if (flags and FIN != 0) 1 else 0)
                writeTcp(TunNat.int(packet, 12), key, 0, seq + length, RST or ACK, 0, null, 0, 0)
            }
            return
        }
        val address = InetAddress.getByAddress(packet.copyOfRange(16, 20))
        val seq = TunNat.int(packet, header + 4)
        if (!allowed(address)) {
            writeTcp(TunNat.int(packet, 12), key, 0, seq + 1, RST or ACK, 0, null, 0, 0)
            return
        }
        val mss = tcpOption(packet, header, total, OPTION_MSS)?.let { TunNat.short(packet, it) } ?: 536
        val created = TcpFlow(key, TunNat.int(packet, 12), address, seq, TunNat.short(packet, header + 14), minOf(mss, MSS))
        if (tcp.putIfAbsent(key, created) == null) created.open()
    }

    /** The offset of option [kind]'s value in the SYN at [header], or null. */
    private fun tcpOption(packet: ByteArray, header: Int, total: Int, kind: Int): Int? {
        var at = header + TCP_HEADER
        val end = minOf(total, header + (packet[header + 12].toInt() ushr 4 and 0xf) * 4)
        while (at < end) {
            when (val option = packet[at].toInt() and 0xff) {
                0 -> return null
                1 -> at++
                else -> {
                    if (at + 1 >= end) return null
                    val length = packet[at + 1].toInt() and 0xff
                    if (length < 2 || at + length > end) return null
                    if (option == kind) return at + 2
                    at += length
                }
            }
        }
        return null
    }

    /**
     * Writes a TCP segment to the watch as if from [key]'s destination. [ack] is ignored when the
     * ACK flag is off.
     */
    private fun writeTcp(watch: Int, key: Key, seq: Int, ack: Int, flags: Int, window: Int, data: ByteArray?, from: Int, length: Int, mss: Int = 0) {
        val options = if (mss > 0) 4 else 0
        val headerLength = TCP_HEADER + options
        val packet = ByteArray(IP_HEADER + headerLength + length)
        ipHeader(packet, key.dst, watch, TCP, packet.size)
        val h = IP_HEADER
        TunNat.putShort(packet, h, key.dstPort)
        TunNat.putShort(packet, h + 2, key.srcPort)
        TunNat.putInt(packet, h + 4, seq)
        TunNat.putInt(packet, h + 8, if (flags and ACK != 0) ack else 0)
        packet[h + 12] = (headerLength / 4 shl 4).toByte()
        packet[h + 13] = flags.toByte()
        TunNat.putShort(packet, h + 14, window.coerceIn(0, 0xffff))
        if (mss > 0) {
            packet[h + 20] = OPTION_MSS.toByte()
            packet[h + 21] = 4
            TunNat.putShort(packet, h + 22, mss)
        }
        if (data != null) data.copyInto(packet, h + headerLength, from, from + length)
        TunNat.putShort(packet, h + 16, TunNat.checksum(packet, h, packet.size, TunNat.pseudoHeader(packet, TCP, packet.size - h)))
        write(packet)
    }

    /**
     * The watch's side of one connection, as seen from here. Its socket is connected while the
     * watch already thinks it is; what the watch sends meanwhile waits in [toSocket].
     */
    private inner class TcpFlow(
        private val key: Key,
        private val watch: Int,
        private val address: InetAddress,
        theirIsn: Int,
        private var theirWindow: Int,
        private val mss: Int,
    ) {
        private val socket = Socket()
        private val lock = Any()
        private val isn = random.nextInt()

        // Sequence space is circular: compare differences, never the numbers.
        private var ourSeq = isn + 1
        private var ourUnacked = isn + 1
        private var theirSeq = theirIsn + 1
        private val unackedData = ArrayDeque<Pair<Int, ByteArray>>()
        private val toSocket = LinkedBlockingQueue<ByteArray>()
        private var queued = 0
        private var ourFin = false
        private var theirFin = false
        private var done = false
        private var retries = 0
        private var retransmit: ScheduledFuture<*>? = null

        @Volatile
        var lastUsed = System.nanoTime()
            private set

        fun open() {
            writeTcp(watch, key, isn, theirSeq, SYN or ACK, window(), null, 0, 0, mss)
            thread(name = "w7link-nat-connect", isDaemon = true) {
                try {
                    socket.connect(InetSocketAddress(address, key.dstPort), CONNECT_TIMEOUT_MS)
                    socket.tcpNoDelay = true
                } catch (e: Exception) {
                    abort(tell = true)
                    return@thread
                }
                thread(name = "w7link-nat-out", isDaemon = true) { pump() }
                thread(name = "w7link-nat-in", isDaemon = true) { drain() }
            }
        }

        /** Carries what the watch sent into the socket. */
        private fun pump() {
            try {
                val out = socket.getOutputStream()
                while (true) {
                    val chunk = toSocket.take()
                    if (chunk === END) {
                        socket.shutdownOutput()
                        break
                    }
                    out.write(chunk)
                    synchronized(lock) { queued -= chunk.size }
                }
            } catch (e: Exception) {
                abort(tell = true)
            }
        }

        /** Carries what the socket receives to the watch, as far as its window allows. */
        private fun drain() {
            try {
                val input = socket.getInputStream()
                val buffer = ByteArray(mss)
                while (true) {
                    val n = input.read(buffer)
                    synchronized(lock) {
                        if (done) return
                        if (n < 0) {
                            ourFin = true
                            writeTcp(watch, key, ourSeq, theirSeq, FIN or ACK, window(), null, 0, 0)
                            unackedData.addLast(ourSeq to FIN_MARK)
                            ourSeq += 1
                            armRetransmit()
                            return
                        }
                        // In flight no more than the watch said it can take.
                        while (!done && ourSeq - ourUnacked + n > theirWindow) (lock as Object).wait(WINDOW_WAIT_MS)
                        if (done) return
                        val data = buffer.copyOf(n)
                        writeTcp(watch, key, ourSeq, theirSeq, PSH or ACK, window(), data, 0, n)
                        unackedData.addLast(ourSeq to data)
                        ourSeq += n
                        lastUsed = System.nanoTime()
                        armRetransmit()
                    }
                }
            } catch (e: Exception) {
                abort(tell = true)
            }
        }

        fun onSegment(packet: ByteArray, header: Int, total: Int, flags: Int) {
            val dataAt = header + (packet[header + 12].toInt() ushr 4 and 0xf) * 4
            val length = total - dataAt
            val seq = TunNat.int(packet, header + 4)
            synchronized(lock) {
                if (done) return
                lastUsed = System.nanoTime()
                if (flags and RST != 0) {
                    abort(tell = false)
                    return
                }
                if (flags and ACK != 0) {
                    val ack = TunNat.int(packet, header + 8)
                    theirWindow = TunNat.short(packet, header + 14)
                    if (ack - ourUnacked > 0 && ack - ourSeq <= 0) {
                        ourUnacked = ack
                        while (unackedData.isNotEmpty()) {
                            val (start, data) = unackedData.first()
                            val size = if (data === FIN_MARK) 1 else data.size
                            if (ack - (start + size) < 0) break
                            unackedData.removeFirst()
                        }
                        retries = 0
                        retransmit?.cancel(false)
                        retransmit = if (unackedData.isEmpty()) null else schedule()
                    }
                    (lock as Object).notifyAll()
                }
                if (flags and SYN != 0) {
                    // The watch did not get our answer yet: say it again.
                    writeTcp(watch, key, isn, theirSeq, SYN or ACK, window(), null, 0, 0, mss)
                    return
                }
                var acknowledge = false
                if (length > 0) {
                    if (seq == theirSeq) {
                        if (!theirFin) {
                            toSocket.add(packet.copyOfRange(dataAt, total))
                            queued += length
                            theirSeq += length
                        }
                    }
                    acknowledge = true
                }
                if (flags and FIN != 0 && seq + length == theirSeq && !theirFin) {
                    theirFin = true
                    theirSeq += 1
                    toSocket.add(END)
                    acknowledge = true
                }
                if (acknowledge) writeTcp(watch, key, ourSeq, theirSeq, ACK, window(), null, 0, 0)
                if (ourFin && theirFin && unackedData.isEmpty()) finish()
            }
        }

        private fun window() = (RECEIVE_WINDOW - queued).coerceIn(0, 0xffff)

        private fun armRetransmit() {
            if (retransmit == null) retransmit = schedule()
        }

        private fun schedule(): ScheduledFuture<*> = timer.schedule(Runnable {
            synchronized(lock) {
                retransmit = null
                if (done || unackedData.isEmpty()) return@synchronized
                if (++retries > MAX_RETRIES) {
                    abort(tell = true)
                    return@synchronized
                }
                unackedData.forEach { (start, data) ->
                    if (data === FIN_MARK) {
                        writeTcp(watch, key, start, theirSeq, FIN or ACK, window(), null, 0, 0)
                    } else {
                        writeTcp(watch, key, start, theirSeq, PSH or ACK, window(), data, 0, data.size)
                    }
                }
                retransmit = schedule()
            }
        }, RETRANSMIT_MS, TimeUnit.MILLISECONDS)

        /** Both sides have said their last and heard it: gone without a trace. */
        private fun finish() {
            done = true
            retransmit?.cancel(false)
            tcp.remove(key, this)
            toSocket.add(END)
            runCatching { socket.close() }
            (lock as Object).notifyAll()
        }

        /** Drops the connection; [tell] sends the watch a reset. */
        fun abort(tell: Boolean) {
            synchronized(lock) {
                if (done) return
                done = true
                retransmit?.cancel(false)
                if (tell) writeTcp(watch, key, ourSeq, theirSeq, RST or ACK, 0, null, 0, 0)
                tcp.remove(key, this)
                toSocket.add(END)
                runCatching { socket.close() }
                (lock as Object).notifyAll()
            }
        }
    }

    // --- UDP ---

    private fun onUdp(packet: ByteArray, header: Int, total: Int) {
        val key = Key(TunNat.short(packet, header), TunNat.int(packet, 16), TunNat.short(packet, header + 2))
        val flow = udp[key] ?: run {
            val address = InetAddress.getByAddress(packet.copyOfRange(16, 20))
            if (!allowed(address)) return
            val created = try {
                UdpFlow(key, TunNat.int(packet, 12), address)
            } catch (e: Exception) {
                return
            }
            udp.putIfAbsent(key, created)?.also { created.close() } ?: created.also { it.start() }
        }
        flow.send(packet, header + UDP_HEADER, total)
    }

    private inner class UdpFlow(private val key: Key, private val watch: Int, address: InetAddress) {
        private val socket = DatagramSocket().apply { connect(address, key.dstPort) }

        @Volatile
        var lastUsed = System.nanoTime()
            private set

        fun start() {
            thread(name = "w7link-nat-udp", isDaemon = true) {
                try {
                    val reply = DatagramPacket(ByteArray(MAX_UDP), MAX_UDP)
                    while (true) {
                        socket.receive(reply)
                        lastUsed = System.nanoTime()
                        val packet = ByteArray(IP_HEADER + UDP_HEADER + reply.length)
                        ipHeader(packet, key.dst, watch, UDP, packet.size)
                        TunNat.putShort(packet, IP_HEADER, key.dstPort)
                        TunNat.putShort(packet, IP_HEADER + 2, key.srcPort)
                        TunNat.putShort(packet, IP_HEADER + 4, UDP_HEADER + reply.length)
                        reply.data.copyInto(packet, IP_HEADER + UDP_HEADER, reply.offset, reply.offset + reply.length)
                        TunNat.putShort(packet, IP_HEADER + 6, TunNat.checksum(packet, IP_HEADER, packet.size, TunNat.pseudoHeader(packet, UDP, packet.size - IP_HEADER)).let { if (it == 0) 0xffff else it })
                        write(packet)
                    }
                } catch (_: Exception) {
                } finally {
                    close()
                }
            }
        }

        fun send(packet: ByteArray, from: Int, to: Int) {
            lastUsed = System.nanoTime()
            runCatching { socket.send(DatagramPacket(packet, from, to - from)) }.onFailure { close() }
        }

        fun close() {
            udp.remove(key, this)
            runCatching { socket.close() }
        }
    }

    // --- ICMP ---

    private fun onPing(packet: ByteArray, header: Int, total: Int) {
        val reply = packet.copyOf(total)
        ipHeader(reply, TunNat.int(packet, 16), TunNat.int(packet, 12), ICMP, total)
        reply[header] = 0 // echo reply; the id, sequence and payload stay
        TunNat.putShort(reply, header + 2, 0)
        TunNat.putShort(reply, header + 2, TunNat.checksum(reply, header, total, 0))
        write(reply)
    }

    private fun ipHeader(packet: ByteArray, src: Int, dst: Int, protocol: Int, total: Int) {
        packet[0] = 0x45
        packet[1] = 0
        TunNat.putShort(packet, 2, total)
        TunNat.putShort(packet, 4, ipId.incrementAndGet() and 0xffff)
        TunNat.putShort(packet, 6, 0)
        packet[8] = 64
        packet[9] = protocol.toByte()
        TunNat.putInt(packet, 12, src)
        TunNat.putInt(packet, 16, dst)
        TunNat.putShort(packet, 10, 0)
        TunNat.putShort(packet, 10, TunNat.checksum(packet, 0, IP_HEADER, 0))
    }

    private companion object {
        const val IP_HEADER = 20
        const val TCP_HEADER = 20
        const val UDP_HEADER = 8
        const val ICMP = 1
        const val TCP = 6
        const val UDP = 17
        const val ICMP_ECHO = 8
        const val FIN = 0x01
        const val SYN = 0x02
        const val RST = 0x04
        const val PSH = 0x08
        const val ACK = 0x10
        const val OPTION_MSS = 2

        /** A segment this big fits the link's data packet together with its headers. */
        const val MSS = ProxyLink.MAX_PAYLOAD - IP_HEADER - TCP_HEADER
        const val MAX_UDP = ProxyLink.MAX_IP_PACKET - IP_HEADER - UDP_HEADER
        const val RECEIVE_WINDOW = 64 * 1024 - 1
        const val CONNECT_TIMEOUT_MS = 20_000
        const val RETRANSMIT_MS = 2_000L
        const val WINDOW_WAIT_MS = 500L
        const val MAX_RETRIES = 8
        const val SWEEP_MS = 10_000L
        const val UDP_IDLE_NS = 90_000_000_000L
        const val TCP_IDLE_NS = 20L * 60 * 1_000_000_000L
        val END = ByteArray(0)
        val FIN_MARK = ByteArray(0)
    }
}
