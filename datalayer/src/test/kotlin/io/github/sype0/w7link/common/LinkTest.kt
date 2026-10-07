// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import java.io.Closeable
import java.io.InputStream
import java.io.OutputStream
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec
import java.util.concurrent.CompletableFuture
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LinkTest {
    private fun keys(): KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** One direction of a socket: bytes written to [output] come out of [input], whichever threads use them. */
    private class Wire(tamper: () -> Boolean = { false }) {
        private val bytes = LinkedBlockingQueue<Int>()
        val output = object : OutputStream() {
            override fun write(b: Int) {
                bytes.put(b and 0xff)
            }

            override fun write(b: ByteArray, off: Int, len: Int) {
                for (i in off until off + len) {
                    val flip = if (tamper() && i == off + len - 1) 1 else 0
                    write(b[i].toInt() xor flip)
                }
            }
        }
        val input = object : InputStream() {
            override fun read(): Int = bytes.poll(10, TimeUnit.SECONDS) ?: -1
        }
    }

    /** Two channels joined back to back, handshake done. */
    private fun pair(
        phoneKeys: KeyPair = keys(),
        watchKeys: KeyPair = keys(),
        toWatch: Wire = Wire(),
    ): Pair<SecureChannel, SecureChannel> {
        val toPhone = Wire()
        val phone = SecureChannel(Closeable { }, toPhone.input, toWatch.output)
        val watch = SecureChannel(Closeable { }, toWatch.input, toPhone.output)
        val responder = thread { watch.handshake(watchKeys, initiator = false) }
        phone.handshake(phoneKeys, initiator = true)
        responder.join()
        return phone to watch
    }

    @After
    fun tearDown() {
        LinkHub.detach()
        LinkHub.receiver = null
    }

    @Test
    fun `both sides agree on the code and learn each other's key`() {
        val phoneKeys = keys()
        val watchKeys = keys()
        val (phone, watch) = pair(phoneKeys, watchKeys)
        assertEquals(phone.code, watch.code)
        assertEquals(6, phone.code.length)
        assertArrayEquals(watchKeys.public.encoded, phone.peerKey)
        assertArrayEquals(phoneKeys.public.encoded, watch.peerKey)
    }

    @Test
    fun `a different key gives a different code`() {
        val phoneKeys = keys()
        val codes = (1..4).map { pair(phoneKeys).first.code }.toSet()
        assertNotEquals(1, codes.size)
    }

    @Test
    fun `frames arrive intact and in order in both directions`() {
        val (phone, watch) = pair()
        val big = ByteArray(200_000) { (it * 31).toByte() }
        thread {
            phone.sendFrame(Proto.KIND_ENVELOPE, big)
            phone.sendFrame(Proto.KIND_JSON, "second".toByteArray())
        }
        val (kind, body) = watch.receiveFrame()
        assertEquals(Proto.KIND_ENVELOPE, kind)
        assertArrayEquals(big, body)
        assertEquals("second", String(watch.receiveFrame().second))
        thread { watch.sendFrame(Proto.KIND_STREAM, ByteArray(0)) }
        val reply = phone.receiveFrame()
        assertEquals(Proto.KIND_STREAM, reply.first)
        assertEquals(0, reply.second.size)
    }

    @Test
    fun `a tampered frame is rejected`() {
        var tamper = false
        // Flips one bit of every write the phone makes once the handshake is over.
        val (phone, watch) = pair(toWatch = Wire { tamper })
        tamper = true
        thread { phone.sendFrame(Proto.KIND_JSON, "hello".toByteArray()) }
        assertThrows(Exception::class.java) { watch.receiveFrame() }
    }

    /** Wires LinkHub's sending side straight back into its receiving side. */
    private fun loopback() = LinkHub.attach { kind, body ->
        LinkHub.onFrame(kind, body)
        true
    }

    @Test
    fun `an envelope reaches the receiver with its path and data`() {
        val got = CompletableFuture<Pair<String, ByteArray>>()
        LinkHub.receiver = object : LinkHub.Receiver {
            override fun onMessage(path: String, data: ByteArray) {
                got.complete(path to data)
            }

            override fun onStream(path: String, input: InputStream) = Unit
        }
        loopback()
        assertTrue(LinkHub.sendEnvelope("/hl/v1/hello", byteArrayOf(1, 2, 3)))
        val (path, data) = got.get(5, TimeUnit.SECONDS)
        assertEquals("/hl/v1/hello", path)
        assertArrayEquals(byteArrayOf(1, 2, 3), data)
    }

    @Test
    fun `a stream is reassembled across chunks, including exact multiples of the chunk size`() {
        for (size in listOf(0, 1, 32 * 1024, 32 * 1024 + 1, 250_000)) {
            val got = CompletableFuture<Pair<String, ByteArray>>()
            LinkHub.receiver = object : LinkHub.Receiver {
                override fun onMessage(path: String, data: ByteArray) = Unit

                override fun onStream(path: String, input: InputStream) {
                    thread { got.complete(path to input.readBytes()) }
                }
            }
            loopback()
            val data = ByteArray(size) { (it * 7).toByte() }
            assertTrue(LinkHub.sendStream("/hl/v1/logs/segment/abc", data.inputStream()))
            val (path, received) = got.get(10, TimeUnit.SECONDS)
            assertEquals("/hl/v1/logs/segment/abc", path)
            assertArrayEquals("size $size", data, received)
        }
    }

    @Test
    fun `nothing is sent without a peer`() {
        assertFalse(LinkHub.connected)
        assertFalse(LinkHub.sendEnvelope("/x", ByteArray(1)))
        assertFalse(LinkHub.sendStream("/x", ByteArray(1).inputStream()))
    }
}
