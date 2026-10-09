// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common.sysproxy

import java.io.DataInputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProxyLinkTest {
    private val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
    private val watchSide = Socket(InetAddress.getLoopbackAddress(), server.localPort).apply { soTimeout = 10_000 }
    private val phoneSide = server.accept()
    private val frames = DataInputStream(watchSide.getInputStream())
    private val opened = CompletableFuture<ProxyLink>()
    private val packets = LinkBlockingPackets()
    private val link = ProxyLink(phoneSide.getInputStream(), phoneSide.getOutputStream(), object : ProxyLink.Listener {
        override fun onOpen(link: ProxyLink) {
            opened.complete(link)
        }

        override fun onPacket(link: ProxyLink, packet: ByteArray) {
            packets.queue.add(packet)
        }

        override fun onClosed(link: ProxyLink, reason: String) {}
    })

    private class LinkBlockingPackets {
        val queue = LinkedBlockingQueue<ByteArray>()
    }

    @After
    fun tearDown() {
        link.close()
        watchSide.close()
        server.close()
    }

    // --- the watch's side, by hand ---

    private fun send(body: ByteArray) {
        watchSide.getOutputStream().run {
            write(byteArrayOf((body.size ushr 8).toByte(), body.size.toByte()))
            write(body)
            flush()
        }
    }

    private fun read(): ByteArray {
        val n = frames.readUnsignedShort()
        return ByteArray(n).also { frames.readFully(it) }
    }

    private fun handshake(code: Int) = send(byteArrayOf((0xe0 or code).toByte(), 0x08, 0x01, 0x10, 0x00, 0x18, 0x08, 0x20, 0x08))

    private fun connect() {
        link.start()
        handshake(1)
        val ack = read()
        assertEquals(0xe2, ack[0].toInt() and 0xff)
        val reader = ProtoReader(ack, 1)
        var version = 0
        while (reader.next()) if (reader.field == 1) version = reader.varint.toInt()
        assertEquals(1, version)
        handshake(3)
        assertNotNull(opened.get(5, TimeUnit.SECONDS))
    }

    /** A plausible IPv4 packet of [size] bytes, filled with a pattern. */
    private fun ipPacket(size: Int) = ByteArray(size) { (it * 7).toByte() }.also {
        it[0] = 0x45
        it[2] = (size ushr 8).toByte()
        it[3] = size.toByte()
    }

    @Test
    fun handshakeIsAnsweredAndTheNetworkConfigFollows() {
        connect()
        link.sendNetworkConfig(listOf(ProxyLink.LinkInfo(7, 1L shl 1, 1L shl 12)))
        val config = read()
        assertEquals(0xe4, config[0].toInt() and 0xff)
        val reader = ProtoReader(config, 1)
        assertTrue(reader.next())
        assertEquals(1, reader.field)
        val info = ProtoReader(reader.bytes)
        val fields = HashMap<Int, Long>()
        while (info.next()) fields[info.field] = info.varint
        assertEquals(7L, fields[1])
        assertEquals(1L shl 1, fields[2])
        assertEquals(1L shl 12, fields[3])
    }

    @Test
    fun aPacketSplitOverTwoDataPacketsArrivesWholeAndIsAcked() {
        connect()
        val packet = ipPacket(700)
        send(byteArrayOf(0x20) + packet.copyOfRange(0, 510))
        send(byteArrayOf(0x41) + packet.copyOfRange(510, 700))
        assertArrayEquals(packet, packets.queue.poll(5, TimeUnit.SECONDS))
        // The second part asked for an ack: it comes at once, naming it.
        val ack = read()
        assertEquals(1, ack.size)
        assertEquals(0x01, ack[0].toInt() and 0xff)
    }

    @Test
    fun packetsForTheWatchAreSplitAndResentUntilAcked() {
        connect()
        val packet = ipPacket(700)
        assertTrue(link.send(packet))
        val first = read()
        val second = read()
        assertEquals(0x20, first[0].toInt() and 0xff)
        assertEquals(0x41, second[0].toInt() and 0xff)
        assertArrayEquals(packet, first.copyOfRange(1, first.size) + second.copyOfRange(1, second.size))
        // Nothing acked: both come again under the same numbers.
        val again = read()
        assertEquals(0x20, again[0].toInt() and 0xff)
        assertArrayEquals(first, again)
        assertArrayEquals(second, read())
        send(byteArrayOf(0x01))
        // Once acked, the next packet takes the next number.
        assertTrue(link.send(ipPacket(100)))
        val next = read()
        assertEquals(0x42, next[0].toInt() and 0xff)
        assertEquals(101, next.size)
    }
}
