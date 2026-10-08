// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import java.util.concurrent.ConcurrentHashMap

/**
 * Turns the packets of a VPN interface into ordinary sockets without a TCP stack of its own: a TCP
 * packet an app sends out is sent back in, readdressed to a relay server on this device, so the
 * system's own TCP ends the connection there; the relay's packets are readdressed the other way,
 * and the app sees them come from where it connected. The relay asks [destination] where each
 * connection was really going. DNS queries (UDP) go to [resolve] and are answered as packets.
 * IPv4 only; anything else is dropped.
 *
 * [local] is the interface's address, [write] puts a packet into the interface.
 */
class TunNat(
    private val local: Int,
    private val relayPort: Int,
    private val resolve: (query: ByteArray, onReply: (ByteArray) -> Unit) -> Unit,
    private val write: (ByteArray) -> Unit,
) {
    class Destination(val address: Int, val port: Int) {
        val host: String
            get() = "${address ushr 24}.${address ushr 16 and 0xff}.${address ushr 8 and 0xff}.${address and 0xff}"
    }

    // By the app's port. Never emptied: a late packet of a closed connection still needs its entry.
    private val connections = ConcurrentHashMap<Int, Destination>()

    /** Where the app behind the relay's connection from [clientPort] was connecting to. */
    fun destination(clientPort: Int): Destination? = connections[clientPort]

    /** One packet read from the interface, in the first [length] bytes of [packet], which is overwritten. */
    fun onPacket(packet: ByteArray, length: Int) {
        if (length < IP_HEADER || packet[0].toInt() ushr 4 and 0xf != 4) return
        val header = (packet[0].toInt() and 0xf) * 4
        val total = short(packet, 2)
        // No fragments: only a first, whole packet has the ports.
        if (header < IP_HEADER || total > length || short(packet, 6) and 0x3fff != 0) return
        if (int(packet, 12) != local) return
        when (packet[9].toInt()) {
            TCP -> if (total >= header + TCP_HEADER) tcp(packet, header, total)
            UDP -> if (total >= header + UDP_HEADER && short(packet, header + 2) == DNS_PORT) dns(packet, header, total)
        }
    }

    private fun tcp(packet: ByteArray, header: Int, total: Int) {
        val remote = int(packet, 16)
        val sourcePort = short(packet, header)
        if (sourcePort == relayPort) {
            // The relay answering: make it come from where the app connected.
            val to = connections[short(packet, header + 2)] ?: return
            putShort(packet, header, to.port)
        } else {
            val flags = packet[header + 13].toInt()
            if (flags and SYN != 0 && flags and ACK == 0) connections[sourcePort] = Destination(remote, short(packet, header + 2))
            if (!connections.containsKey(sourcePort)) return
            putShort(packet, header + 2, relayPort)
        }
        // Either way it turns around: from the far address, to this device.
        putInt(packet, 12, remote)
        putInt(packet, 16, local)
        putShort(packet, 10, 0)
        putShort(packet, 10, checksum(packet, 0, header, 0))
        putShort(packet, header + 16, 0)
        putShort(packet, header + 16, checksum(packet, header, total, pseudoHeader(packet, TCP, total - header)))
        write(packet.copyOf(total))
    }

    private fun dns(packet: ByteArray, header: Int, total: Int) {
        val server = int(packet, 16)
        val clientPort = short(packet, header)
        val ipHeader = packet.copyOf(IP_HEADER)
        resolve(packet.copyOfRange(header + UDP_HEADER, total)) { answer ->
            val size = IP_HEADER + UDP_HEADER + answer.size
            if (size <= 0xffff) {
                val reply = ipHeader.copyOf(size)
                reply[0] = 0x45
                putShort(reply, 2, size)
                putShort(reply, 6, 0)
                putInt(reply, 12, server)
                putInt(reply, 16, local)
                putShort(reply, 10, 0)
                putShort(reply, 10, checksum(reply, 0, IP_HEADER, 0))
                putShort(reply, IP_HEADER, DNS_PORT)
                putShort(reply, IP_HEADER + 2, clientPort)
                putShort(reply, IP_HEADER + 4, UDP_HEADER + answer.size)
                // No UDP checksum, which IPv4 allows.
                putShort(reply, IP_HEADER + 6, 0)
                answer.copyInto(reply, IP_HEADER + UDP_HEADER)
                write(reply)
            }
        }
    }

    companion object {
        private const val TCP = 6
        private const val UDP = 17
        private const val IP_HEADER = 20
        private const val TCP_HEADER = 20
        private const val UDP_HEADER = 8
        private const val DNS_PORT = 53
        private const val SYN = 0x02
        private const val ACK = 0x10

        fun short(packet: ByteArray, at: Int) = (packet[at].toInt() and 0xff shl 8) or (packet[at + 1].toInt() and 0xff)

        fun int(packet: ByteArray, at: Int) = (short(packet, at) shl 16) or short(packet, at + 2)

        fun putShort(packet: ByteArray, at: Int, value: Int) {
            packet[at] = (value ushr 8).toByte()
            packet[at + 1] = value.toByte()
        }

        fun putInt(packet: ByteArray, at: Int, value: Int) {
            putShort(packet, at, value ushr 16)
            putShort(packet, at + 2, value and 0xffff)
        }

        /** The addresses, protocol and length a TCP checksum also covers, as the sum to start from. */
        fun pseudoHeader(packet: ByteArray, protocol: Int, length: Int): Long =
            short(packet, 12).toLong() + short(packet, 14) + short(packet, 16) + short(packet, 18) + protocol + length

        /** The internet checksum of [from] until [to]; zero over data that already carries a right one. */
        fun checksum(packet: ByteArray, from: Int, to: Int, start: Long): Int {
            var sum = start
            var i = from
            while (i + 1 < to) {
                sum += short(packet, i)
                i += 2
            }
            if (i < to) sum += packet[i].toInt() and 0xff shl 8
            while (sum ushr 16 != 0L) sum = (sum and 0xffff) + (sum ushr 16)
            return sum.toInt().inv() and 0xffff
        }
    }
}
