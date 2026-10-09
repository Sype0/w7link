// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common.sysproxy

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread

/**
 * The phone's end of Wear OS's "phone-based wearable device tethering" (sysproxy v2), whose watch
 * end is in Android's own sources (com.android.networkstack.tethering.companionproxy). The watch
 * opens an L2CAP channel to the phone and the two exchange packets on it, each a 16-bit big-endian
 * length and up to 512 bytes:
 *
 *  - a header byte whose top three bits are the type and low five bits a sequence number or a
 *    control code; control packets (type 7) carry a protobuf body: reset, the three handshake
 *    steps, the network config and link stats;
 *  - data packets (types 1 and 2, the latter asking for a prompt ack) carry a slice of a byte
 *    stream of IP packets laid end to end, under a sequence number 0..31, acknowledged by a type 0
 *    byte with the last received number that may precede a data packet; unacknowledged data is
 *    resent after four seconds, and at most a window of packets is in flight.
 *
 * The watch is the client: it starts the handshake, this side acks it, the watch finishes it, and
 * from then on IP packets flow both ways and this side tells the watch what uplink the phone has.
 */
class ProxyLink(
    private val input: InputStream,
    private val output: OutputStream,
    private val listener: Listener,
) : Closeable {
    interface Listener {
        /** The handshake is done; the owner should send a [NetworkConfig] now. */
        fun onOpen(link: ProxyLink)

        /** One whole IP packet from the watch; not on the link's own thread for long. */
        fun onPacket(link: ProxyLink, packet: ByteArray)

        fun onClosed(link: ProxyLink, reason: String)
    }

    class LinkInfo(val netId: Int, val transports: Long, val capabilities: Long)

    private enum class State { NEW, HANDSHAKE, OPEN, CLOSED }

    private class Chunk(val data: ByteArray, val requestAck: Boolean) {
        /** The sequence number, given at the first send and kept for resends. */
        var sn = -1
    }

    private val lock = Any()
    private val timer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "w7link-proxy-timer").apply { isDaemon = true } }
    private val closed = AtomicBoolean()

    @Volatile
    private var state = State.NEW
    private var txWindow = DEFAULT_WINDOW
    private var rxWindow = DEFAULT_WINDOW

    // Outbound: control packets go first, then data chunks, the sent ones kept until acked.
    private val control = ArrayDeque<ByteArray>()
    private val pending = ArrayDeque<Chunk>()
    private val unacked = ArrayDeque<Chunk>()
    private var pendingBytes = 0
    private var nextSn = 0
    private var retransmit: ScheduledFuture<*>? = null
    private var stalledMs = 0L

    // Inbound: what the watch sent, and what it is still owed an ack for.
    private var expectedSn = 0
    private var lastInSn = 0
    private var unackedIn = 0
    private var ackNow = false
    private var ackTimer: ScheduledFuture<*>? = null
    private var handshakeTimer: ScheduledFuture<*>? = null
    private val stream = ByteArrayOutputStream()

    @Volatile
    var packetsIn = 0L
        private set

    @Volatile
    var packetsOut = 0L
        private set

    val isOpen: Boolean
        get() = state == State.OPEN

    /** Starts reading; the watch's handshake comes first. */
    fun start() {
        thread(name = "w7link-proxy-rx", isDaemon = true) {
            try {
                val frames = DataInputStream(input)
                while (!closed.get()) {
                    val length = frames.readUnsignedShort()
                    if (length > MAX_PACKET) throw IOException("packet of $length bytes")
                    val body = ByteArray(length)
                    frames.readFully(body)
                    val packets = onFrame(body)
                    packets?.forEach { listener.onPacket(this, it) }
                }
            } catch (e: EOFException) {
                close("watch hung up")
            } catch (e: Exception) {
                close("link lost: $e")
            }
        }
    }

    /** Queues one IP packet for the watch; false when the link is down or too far behind. */
    fun send(packet: ByteArray): Boolean {
        synchronized(lock) {
            if (state != State.OPEN || pendingBytes + packet.size > MAX_PENDING_BYTES) return false
            var at = 0
            while (at < packet.size) {
                val n = minOf(MAX_PAYLOAD, packet.size - at)
                pending.addLast(Chunk(packet.copyOfRange(at, at + n), requestAck = false))
                at += n
            }
            pendingBytes += packet.size
            packetsOut++
            sendNext()
        }
        return true
    }

    /** Tells the watch what the phone is online through; an empty list means it is not. */
    fun sendNetworkConfig(links: List<LinkInfo>) {
        val body = ProtoWriter()
        links.forEach { link ->
            body.bytes(
                1,
                ProtoWriter().varint(1, link.netId.toLong()).varint(2, link.transports).varint(3, link.capabilities).toByteArray(),
            )
        }
        synchronized(lock) {
            if (state != State.OPEN) return
            control.addLast(controlPacket(CONTROL_NETWORK_CONFIG, body.toByteArray()))
            sendNext()
        }
    }

    override fun close() = close("closed")

    private fun close(reason: String) {
        if (!closed.compareAndSet(false, true)) return
        synchronized(lock) {
            val wasUp = state == State.HANDSHAKE || state == State.OPEN
            state = State.CLOSED
            retransmit?.cancel(false)
            ackTimer?.cancel(false)
            handshakeTimer?.cancel(false)
            if (wasUp) runCatching { writeFrame(controlPacket(CONTROL_RESET, ByteArray(0))) }
        }
        timer.shutdownNow()
        runCatching { input.close() }
        runCatching { output.close() }
        listener.onClosed(this, reason)
    }

    // --- inbound ---

    /** Handles one packet; returns the IP packets it completed, if any. */
    private fun onFrame(body: ByteArray): List<ByteArray>? {
        if (body.isEmpty()) return null
        synchronized(lock) {
            if (state == State.CLOSED) return null
            var at = 0
            var header = body[at++].toInt() and 0xff
            when (header and TYPE_MASK) {
                TYPE_CONTROL -> {
                    onControl(header and LOW_MASK, body, at)
                    return null
                }
                TYPE_DATA_ACK -> {
                    onAck(header and LOW_MASK)
                    if (at >= body.size) {
                        sendNext()
                        return null
                    }
                    header = body[at++].toInt() and 0xff
                }
            }
            val type = header and TYPE_MASK
            if (type != TYPE_DATA && type != TYPE_DATA_REQ_ACK) {
                close("bad packet type ${header ushr 5}")
                return null
            }
            val packets = onData(header and LOW_MASK, type == TYPE_DATA_REQ_ACK, body, at)
            sendNext()
            return packets
        }
    }

    private fun onControl(code: Int, body: ByteArray, at: Int) {
        when (code) {
            CONTROL_RESET -> close("reset by watch")
            CONTROL_HANDSHAKE_START -> {
                if (state != State.NEW) return close("handshake out of turn")
                if (!takeHandshake(body, at)) return
                state = State.HANDSHAKE
                control.addLast(handshakePacket(CONTROL_HANDSHAKE_ACK))
                handshakeTimer = timer.schedule(Runnable { synchronized(lock) { if (state == State.HANDSHAKE) close("handshake timed out") } }, HANDSHAKE_MS, TimeUnit.MILLISECONDS)
                sendNext()
            }
            CONTROL_HANDSHAKE_DONE -> {
                if (state != State.HANDSHAKE) return close("handshake out of turn")
                if (!takeHandshake(body, at)) return
                handshakeTimer?.cancel(false)
                state = State.OPEN
                timer.execute { listener.onOpen(this) }
            }
            CONTROL_HANDSHAKE_ACK -> close("the watch acked as if it were the phone")
            CONTROL_NETWORK_CONFIG, CONTROL_LINK_USAGE_STATS -> Unit
            else -> close("unknown control packet $code")
        }
    }

    /** Reads the watch's handshake values into the link's; false (and closed) when they don't fit. */
    private fun takeHandshake(body: ByteArray, at: Int): Boolean {
        var version = 0
        var theirRx = DEFAULT_WINDOW
        var theirTx = DEFAULT_WINDOW
        try {
            val reader = ProtoReader(body, at)
            while (reader.next()) {
                when (reader.field) {
                    1 -> version = reader.varint.toInt()
                    3 -> theirRx = reader.varint.toInt()
                    4 -> theirTx = reader.varint.toInt()
                }
            }
        } catch (e: IOException) {
            close("bad handshake: $e")
            return false
        }
        if (version != PROTOCOL_VERSION) {
            close("protocol version $version")
            return false
        }
        txWindow = minOf(txWindow, theirRx).coerceIn(2, MAX_WINDOW)
        rxWindow = minOf(rxWindow, theirTx).coerceIn(2, MAX_WINDOW)
        return true
    }

    private fun onAck(sn: Int) {
        if (unacked.none { it.sn == sn }) return
        while (unacked.isNotEmpty()) {
            val chunk = unacked.removeFirst()
            pendingBytes -= chunk.data.size
            if (chunk.sn == sn) break
        }
        stalledMs = 0
        retransmit?.cancel(false)
        retransmit = if (unacked.isEmpty()) null else scheduleRetransmit()
    }

    private fun onData(sn: Int, requestAck: Boolean, body: ByteArray, at: Int): List<ByteArray>? {
        var packets: List<ByteArray>? = null
        var ackSn = sn
        if (sn == expectedSn) {
            if (at < body.size) {
                stream.write(body, at, body.size - at)
                packets = takePackets()
            }
            expectedSn = (sn + 1) % MAX_SEQS
        } else {
            // Something the watch sent again because our ack was late, or nonsense.
            if ((MAX_SEQS + expectedSn - sn) % MAX_SEQS >= rxWindow) return null
            ackSn = lastInSn
        }
        lastInSn = ackSn
        unackedIn++
        if (requestAck) ackNow = true
        if (ackTimer == null) {
            ackTimer = timer.schedule(Runnable {
                synchronized(lock) {
                    ackTimer = null
                    if (state == State.OPEN || state == State.HANDSHAKE) {
                        ackNow = true
                        sendNext()
                    }
                }
            }, ACK_MS, TimeUnit.MILLISECONDS)
        }
        return packets
    }

    /** The whole IP packets at the head of the inbound stream, each delimited by its own header. */
    private fun takePackets(): List<ByteArray>? {
        var done: MutableList<ByteArray>? = null
        var buffer = stream.toByteArray()
        var at = 0
        while (at < buffer.size) {
            val version = buffer[at].toInt() ushr 4 and 0xf
            val total = when {
                version == 4 && buffer.size - at >= 20 -> be16(buffer, at + 2)
                version == 6 && buffer.size - at >= 40 -> 40 + be16(buffer, at + 4)
                version == 4 || version == 6 -> break
                else -> {
                    close("lost the packet boundary")
                    return done
                }
            }
            if (total < 20 || total > MAX_IP_PACKET) {
                close("IP packet of $total bytes")
                return done
            }
            if (buffer.size - at < total) break
            (done ?: ArrayList<ByteArray>().also { done = it }).add(buffer.copyOfRange(at, at + total))
            packetsIn++
            at += total
        }
        if (at > 0) {
            stream.reset()
            stream.write(buffer, at, buffer.size - at)
        }
        return done
    }

    // --- outbound ---

    /** Sends what can go now: control first, then data within the window, acks riding along. */
    private fun sendNext() {
        if (state == State.CLOSED) return
        try {
            while (control.isNotEmpty()) writeFrame(control.removeFirst())
            if (state != State.OPEN) return
            while (true) {
                val ack = ackNow || unackedIn > rxWindow / 2
                val canSend = pending.isNotEmpty() && unacked.size < txWindow
                if (!ack && !canSend) return
                val frame = ByteArrayOutputStream(MAX_PACKET)
                if (ack) frame.write(TYPE_DATA_ACK or lastInSn)
                if (canSend) {
                    val chunk = pending.removeFirst()
                    if (chunk.sn < 0) {
                        chunk.sn = nextSn
                        nextSn = (nextSn + 1) % MAX_SEQS
                    }
                    // Ask for a prompt ack when the window is about to stall us, or nothing follows.
                    val request = chunk.requestAck || unacked.size + 1 >= txWindow || pending.isEmpty()
                    frame.write((if (request) TYPE_DATA_REQ_ACK else TYPE_DATA) or chunk.sn)
                    frame.write(chunk.data)
                    unacked.addLast(chunk)
                    if (retransmit == null) retransmit = scheduleRetransmit()
                }
                writeFrame(frame.toByteArray())
                if (ack) {
                    ackNow = false
                    unackedIn = 0
                    ackTimer?.cancel(false)
                    ackTimer = null
                }
            }
        } catch (e: IOException) {
            close("write failed: $e")
        }
    }

    private fun scheduleRetransmit(): ScheduledFuture<*> = timer.schedule(Runnable {
        synchronized(lock) {
            retransmit = null
            if (state != State.OPEN || unacked.isEmpty()) return@synchronized
            stalledMs += RETRANSMIT_MS
            if (stalledMs > STALL_MS) {
                close("the watch stopped acking")
                return@synchronized
            }
            // Everything unacked goes again, in order, under the same numbers.
            while (unacked.isNotEmpty()) pending.addFirst(unacked.removeLast())
            sendNext()
        }
    }, RETRANSMIT_MS, TimeUnit.MILLISECONDS)

    private fun handshakePacket(code: Int): ByteArray = controlPacket(
        code,
        ProtoWriter()
            .varint(1, PROTOCOL_VERSION.toLong())
            .varint(2, 0)
            .varint(3, rxWindow.toLong())
            .varint(4, txWindow.toLong())
            .toByteArray(),
    )

    private fun controlPacket(code: Int, body: ByteArray): ByteArray =
        ByteArray(1 + body.size).also {
            it[0] = (TYPE_CONTROL or code).toByte()
            body.copyInto(it, 1)
        }

    private fun writeFrame(body: ByteArray) {
        val frame = ByteArray(2 + body.size)
        frame[0] = (body.size ushr 8).toByte()
        frame[1] = body.size.toByte()
        body.copyInto(frame, 2)
        output.write(frame)
        output.flush()
    }

    companion object {
        const val MAX_IP_PACKET = 1280

        /** An IP packet this long still goes to the watch in one piece, with an ack riding along. */
        const val MAX_PAYLOAD = 510

        private const val MAX_PACKET = 512
        private const val MAX_PENDING_BYTES = 96 * 1024
        private const val TYPE_MASK = 0x7 shl 5
        private const val TYPE_DATA_ACK = 0x0 shl 5
        private const val TYPE_DATA = 0x1 shl 5
        private const val TYPE_DATA_REQ_ACK = 0x2 shl 5
        private const val TYPE_CONTROL = 0x7 shl 5
        private const val LOW_MASK = 0x1f
        private const val CONTROL_RESET = 0
        private const val CONTROL_HANDSHAKE_START = 1
        private const val CONTROL_HANDSHAKE_ACK = 2
        private const val CONTROL_HANDSHAKE_DONE = 3
        private const val CONTROL_NETWORK_CONFIG = 4
        private const val CONTROL_LINK_USAGE_STATS = 5
        private const val PROTOCOL_VERSION = 1
        private const val MAX_SEQS = 32
        private const val MAX_WINDOW = MAX_SEQS - 1
        private const val DEFAULT_WINDOW = 8
        private const val RETRANSMIT_MS = 4_000L
        private const val STALL_MS = 12_000L
        private const val ACK_MS = 200L
        private const val HANDSHAKE_MS = 2_000L

        /** The ProxyConfig message the phone writes to the watch: its L2CAP PSM and a number that changes when the channel does. */
        fun configMessage(psm: Int, changeId: Int): ByteArray =
            ProtoWriter().varint(1, psm.toLong()).varint(2, changeId.toLong()).toByteArray()

        private fun be16(data: ByteArray, at: Int) = (data[at].toInt() and 0xff shl 8) or (data[at + 1].toInt() and 0xff)
    }
}
