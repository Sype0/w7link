// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NetTunnelTest {
    // The two ends, wired straight into each other in place of the link.
    private lateinit var phone: NetTunnel
    private val watch = NetTunnel { phone.onFrame(it); true }
    private val proxy = LocalProxy(watch)
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    init {
        phone = NetTunnel { watch.onFrame(it); true }
        phone.exit = { true }
    }

    @After
    fun tearDown() {
        proxy.close()
        server.close()
    }

    private fun client() = Socket(InetAddress.getLoopbackAddress(), proxy.port).apply { soTimeout = 15_000 }

    private fun Socket.readHead(): String {
        val head = StringBuilder()
        while (!head.endsWith("\r\n\r\n")) {
            val b = getInputStream().read()
            if (b < 0) break
            head.append(b.toChar())
        }
        return head.toString()
    }

    @Test
    fun plainHttpRequestReachesTheServerInOriginForm() {
        val seen = CompletableFuture<String>()
        thread {
            server.accept().use { peer ->
                seen.complete(peer.readHead())
                peer.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 5\r\n\r\nhello".toByteArray())
            }
        }
        client().use { client ->
            client.getOutputStream().write(
                "GET http://127.0.0.1:${server.localPort}/a?b=1 HTTP/1.1\r\nHost: 127.0.0.1\r\nProxy-Connection: keep-alive\r\n\r\n".toByteArray()
            )
            val reply = String(client.getInputStream().readBytes())
            assertTrue(reply, reply.startsWith("HTTP/1.1 200 OK") && reply.endsWith("hello"))
        }
        val head = seen.get(15, TimeUnit.SECONDS)
        assertTrue(head, head.startsWith("GET /a?b=1 HTTP/1.1\r\nHost: 127.0.0.1\r\n"))
        assertTrue(head, head.contains("Connection: close\r\n") && !head.contains("Proxy-Connection"))
    }

    @Test
    fun connectCarriesBytesBothWays() {
        thread {
            server.accept().use { peer ->
                val buffer = ByteArray(4)
                var n = 0
                while (n < 4) n += peer.getInputStream().read(buffer, n, 4 - n)
                peer.getOutputStream().write(buffer.reversedArray())
            }
        }
        client().use { client ->
            client.getOutputStream().write("CONNECT 127.0.0.1:${server.localPort} HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(client.readHead().startsWith("HTTP/1.1 200"))
            client.getOutputStream().write("ping".toByteArray())
            // The server closes after answering, and that has to reach this end too.
            assertEquals("gnip", String(client.getInputStream().readBytes()))
        }
    }

    @Test
    fun aPhoneThatIsNotSharingRefuses() {
        phone.exit = null
        client().use { client ->
            client.getOutputStream().write("CONNECT 127.0.0.1:${server.localPort} HTTP/1.1\r\n\r\n".toByteArray())
            assertTrue(client.readHead().startsWith("HTTP/1.1 502"))
        }
    }
}
