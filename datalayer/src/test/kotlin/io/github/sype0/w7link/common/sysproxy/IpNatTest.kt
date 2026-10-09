// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common.sysproxy

import io.github.sype0.w7link.common.TunNat
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class IpNatTest {
    private val out = LinkedBlockingQueue<ByteArray>()
    private val nat = IpNat({ out.add(it) })
    private val loopback = 0x7f000001
    private val watch = 0x0a000002

    @After
    fun tearDown() = nat.close()

    private fun next(): ByteArray = out.poll(5, TimeUnit.SECONDS).also { assertNotNull("no packet came", it) }!!

    private fun ip(protocol: Int, payload: ByteArray): ByteArray {
        val packet = ByteArray(20 + payload.size)
        packet[0] = 0x45
        TunNat.putShort(packet, 2, packet.size)
        packet[8] = 64
        packet[9] = protocol.toByte()
        TunNat.putInt(packet, 12, watch)
        TunNat.putInt(packet, 16, loopback)
        TunNat.putShort(packet, 10, TunNat.checksum(packet, 0, 20, 0))
        payload.copyInto(packet, 20)
        return packet
    }

    private fun tcp(srcPort: Int, dstPort: Int, seq: Int, ack: Int, flags: Int, data: ByteArray = ByteArray(0)): ByteArray {
        val segment = ByteArray(20 + data.size)
        TunNat.putShort(segment, 0, srcPort)
        TunNat.putShort(segment, 2, dstPort)
        TunNat.putInt(segment, 4, seq)
        TunNat.putInt(segment, 8, ack)
        segment[12] = 0x50
        segment[13] = flags.toByte()
        TunNat.putShort(segment, 14, 65535)
        data.copyInto(segment, 20)
        val packet = ip(6, segment)
        TunNat.putShort(packet, 36, TunNat.checksum(packet, 20, packet.size, TunNat.pseudoHeader(packet, 6, packet.size - 20)))
        return packet
    }

    private fun ByteArray.tcpFlags() = this[33].toInt() and 0x3f
    private fun ByteArray.tcpSeq() = TunNat.int(this, 24)
    private fun ByteArray.tcpAck() = TunNat.int(this, 28)
    private fun ByteArray.tcpData() = copyOfRange(20 + (this[32].toInt() ushr 4 and 0xf) * 4, size)

    @Test
    fun aTcpConnectionIsCarriedBothWaysAndClosed() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val received = LinkedBlockingQueue<String>()
        thread(isDaemon = true) {
            server.accept().use { client ->
                val buffer = ByteArray(64)
                val n = client.getInputStream().read(buffer)
                received.add(String(buffer, 0, n))
                client.getOutputStream().write("world".toByteArray())
                client.getOutputStream().flush()
                // Wait for the watch's side to read, then hang up.
                received.add(String(buffer, 0, client.getInputStream().read(buffer).coerceAtLeast(0)))
            }
        }
        val seq = 1000
        nat.onPacket(tcp(40000, server.localPort, seq, 0, SYN))
        val synAck = next()
        assertEquals(SYN or ACK, synAck.tcpFlags())
        assertEquals(seq + 1, synAck.tcpAck())
        assertEquals(0x60, synAck[32].toInt() and 0xf0) // an option: the MSS
        val theirSeq = synAck.tcpSeq() + 1
        nat.onPacket(tcp(40000, server.localPort, seq + 1, theirSeq, ACK or PSH, "hello".toByteArray()))
        assertEquals("hello", received.poll(5, TimeUnit.SECONDS))
        val ack = next()
        assertEquals(ACK, ack.tcpFlags())
        assertEquals(seq + 6, ack.tcpAck())
        val data = next()
        assertEquals(ACK or PSH, data.tcpFlags())
        assertEquals(theirSeq, data.tcpSeq())
        assertArrayEquals("world".toByteArray(), data.tcpData())
        nat.onPacket(tcp(40000, server.localPort, seq + 6, theirSeq + 5, ACK or PSH, "bye".toByteArray()))
        assertEquals("bye", received.poll(5, TimeUnit.SECONDS))
        assertEquals(seq + 9, next().tcpAck())
        // The server closed: a FIN comes, the watch answers in kind, and the flow is gone.
        val fin = next()
        assertEquals(FIN or ACK, fin.tcpFlags())
        assertEquals(theirSeq + 5, fin.tcpSeq())
        nat.onPacket(tcp(40000, server.localPort, seq + 9, theirSeq + 6, FIN or ACK))
        val last = next()
        assertEquals(ACK, last.tcpFlags())
        assertEquals(seq + 10, last.tcpAck())
        assertEquals(0, nat.flows)
        server.close()
    }

    @Test
    fun aUdpDatagramIsAnsweredFromWhereItWent() {
        val echo = DatagramSocket(0, InetAddress.getLoopbackAddress())
        thread(isDaemon = true) {
            val packet = DatagramPacket(ByteArray(64), 64)
            echo.receive(packet)
            echo.send(DatagramPacket("pong".toByteArray(), 4, packet.socketAddress))
        }
        val datagram = ByteArray(12)
        TunNat.putShort(datagram, 0, 50000)
        TunNat.putShort(datagram, 2, echo.localPort)
        TunNat.putShort(datagram, 4, 12)
        "ping".toByteArray().copyInto(datagram, 8)
        nat.onPacket(ip(17, datagram))
        val reply = next()
        assertEquals(17, reply[9].toInt())
        assertEquals(loopback, TunNat.int(reply, 12))
        assertEquals(watch, TunNat.int(reply, 16))
        assertEquals(echo.localPort, TunNat.short(reply, 20))
        assertEquals(50000, TunNat.short(reply, 22))
        assertArrayEquals("pong".toByteArray(), reply.copyOfRange(28, reply.size))
        echo.close()
    }

    @Test
    fun aPingIsAnsweredHere() {
        val echo = ByteArray(12)
        echo[0] = 8
        TunNat.putShort(echo, 4, 0x1234)
        TunNat.putShort(echo, 6, 1)
        TunNat.putShort(echo, 2, TunNat.checksum(echo, 0, echo.size, 0))
        nat.onPacket(ip(1, echo))
        val reply = next()
        assertEquals(0, reply[20].toInt())
        assertEquals(0x1234, TunNat.short(reply, 24))
        assertEquals(loopback, TunNat.int(reply, 12))
        assertEquals(0, TunNat.checksum(reply, 20, reply.size, 0))
    }

    private companion object {
        const val FIN = 0x01
        const val SYN = 0x02
        const val PSH = 0x08
        const val ACK = 0x10
    }
}
