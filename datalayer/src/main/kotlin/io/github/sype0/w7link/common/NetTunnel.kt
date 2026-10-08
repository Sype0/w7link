// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URI
import java.nio.ByteBuffer
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * TCP connections and DNS lookups carried over the link, so the watch can use the phone's internet.
 * The watch (entry side) asks with [open] and [resolve]; the phone (exit side) connects out when
 * its [exit] agrees and looks names up at its [resolver]. A frame is an id, an operation and its
 * bytes, sent through [sendFrame].
 */
class NetTunnel(private val sendFrame: (ByteArray) -> Boolean) {
    /** Whether this end connects to an address for the peer; null (the default) refuses everything. */
    @Volatile
    var exit: ((InetAddress) -> Boolean)? = null

    /** Exit side: the DNS server lookups are sent to; null (the default) leaves them unanswered. */
    @Volatile
    var resolver: (() -> InetAddress?)? = null

    private val pipes = ConcurrentHashMap<Int, Pipe>()
    private val lookups = ConcurrentHashMap<Int, (ByteArray) -> Unit>()
    private val opening = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val nextId = AtomicInteger()

    /** Called on the link's thread. */
    fun onFrame(body: ByteArray) {
        if (body.size < HEADER) return
        val id = ByteBuffer.wrap(body).int
        val payload = body.copyOfRange(HEADER, body.size)
        when (body[4].toInt()) {
            OPEN -> thread(name = "w7link-net-open", isDaemon = true) { connectOut(id, String(payload)) }
            OPENED -> opening.remove(id)?.complete(true)
            FAILED -> opening.remove(id)?.complete(false)
            DATA -> pipes[id]?.offer(payload)
            CLOSE -> pipes[id]?.finish()
            DNS -> thread(name = "w7link-net-dns", isDaemon = true) { lookUp(id, payload) }
            DNS_REPLY -> lookups.remove(id)?.invoke(payload)
        }
    }

    /** Entry side: sends a DNS [query] to the peer's resolver; [onReply] gets the answer, on the link's thread, if one comes. */
    fun resolve(query: ByteArray, onReply: (ByteArray) -> Unit) {
        // Unanswered lookups are the client's to retry; they only must not pile up here.
        if (lookups.size >= MAX_LOOKUPS) lookups.clear()
        val id = nextId.incrementAndGet()
        lookups[id] = onReply
        if (!send(id, DNS, query)) lookups.remove(id)
    }

    private fun lookUp(id: Int, query: ByteArray) {
        runCatching {
            val server = resolver?.invoke() ?: return
            DatagramSocket().use { socket ->
                socket.soTimeout = DNS_TIMEOUT_MS
                socket.send(DatagramPacket(query, query.size, server, DNS_PORT))
                val reply = DatagramPacket(ByteArray(MAX_DNS), MAX_DNS)
                socket.receive(reply)
                send(id, DNS_REPLY, reply.data, reply.length)
            }
        }
    }

    /**
     * Entry side: ties [local] to [host]:[port] reached through the peer. Null when the peer
     * couldn't connect, and then [local] is left to the caller. Nothing flows until [Pipe.start].
     */
    fun open(local: Socket, host: String, port: Int): Pipe? {
        val id = nextId.incrementAndGet()
        val pipe = Pipe(id, local)
        val reply = CompletableFuture<Boolean>()
        // Registered first: the peer's data may arrive right behind its answer.
        pipes[id] = pipe
        opening[id] = reply
        val ok = send(id, OPEN, "$host:$port".toByteArray()) && runCatching { reply.get(OPEN_TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false)
        opening.remove(id)
        if (ok) return pipe
        pipes.remove(id)
        send(id, CLOSE)
        return null
    }

    /** The link is gone: every connection with it. */
    fun closeAll() {
        pipes.values.toList().forEach { it.close(false) }
        opening.values.toList().forEach { it.complete(false) }
        opening.clear()
        lookups.clear()
    }

    private fun connectOut(id: Int, target: String) {
        val socket = Socket()
        try {
            val allow = exit ?: throw IOException("not sharing")
            val colon = target.lastIndexOf(':')
            val address = InetAddress.getByName(target.substring(0, colon))
            if (!allow(address)) throw IOException("refused")
            socket.connect(InetSocketAddress(address, target.substring(colon + 1).toInt()), CONNECT_TIMEOUT_MS)
            socket.tcpNoDelay = true
            val pipe = Pipe(id, socket)
            pipes[id] = pipe
            if (send(id, OPENED)) pipe.start() else pipe.close(false)
        } catch (e: Exception) {
            runCatching { socket.close() }
            send(id, FAILED)
        }
    }

    private fun send(id: Int, op: Int, data: ByteArray = END, length: Int = data.size): Boolean {
        val frame = ByteBuffer.allocate(HEADER + length).putInt(id).put(op.toByte()).put(data, 0, length).array()
        return runCatching { sendFrame(frame) }.getOrDefault(false)
    }

    /** One tunnelled connection: [socket]'s bytes go to the peer, the peer's come back into it. */
    inner class Pipe internal constructor(private val id: Int, private val socket: Socket) {
        private val inbound = LinkedBlockingQueue<ByteArray>(QUEUE)
        private val closed = AtomicBoolean()

        /** [first] goes to the peer ahead of whatever [socket] sends. */
        fun start(first: ByteArray? = null) {
            thread(name = "w7link-net-in", isDaemon = true) {
                try {
                    val out = socket.getOutputStream()
                    while (true) {
                        val chunk = inbound.take()
                        if (chunk === END) break
                        out.write(chunk)
                        out.flush()
                    }
                } catch (_: Exception) {
                } finally {
                    close(true)
                }
            }
            thread(name = "w7link-net-out", isDaemon = true) {
                try {
                    if (first == null || send(id, DATA, first)) {
                        val input = socket.getInputStream()
                        val buffer = ByteArray(CHUNK)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0 || !send(id, DATA, buffer, n)) break
                        }
                    }
                } catch (_: Exception) {
                } finally {
                    close(true)
                }
            }
        }

        /** On the link's thread, which a reader that stopped taking data must not hold up for long. */
        internal fun offer(chunk: ByteArray) {
            if (!closed.get() && !inbound.offer(chunk, STALL_S, TimeUnit.SECONDS)) close(true)
        }

        /** The peer closed: what it sent before that is still written out. */
        internal fun finish() {
            if (!inbound.offer(END, STALL_S, TimeUnit.SECONDS)) close(false)
        }

        fun close(tell: Boolean) {
            if (!closed.compareAndSet(false, true)) return
            pipes.remove(id, this)
            runCatching { socket.close() }
            inbound.clear()
            inbound.offer(END)
            if (tell) send(id, CLOSE)
        }
    }

    private companion object {
        const val OPEN = 1
        const val OPENED = 2
        const val FAILED = 3
        const val DATA = 4
        const val CLOSE = 5
        const val DNS = 6
        const val DNS_REPLY = 7
        const val DNS_PORT = 53
        const val DNS_TIMEOUT_MS = 5_000
        const val MAX_DNS = 4096
        const val MAX_LOOKUPS = 256
        const val HEADER = 5
        const val CHUNK = 16 * 1024
        const val QUEUE = 64
        const val STALL_S = 10L
        const val OPEN_TIMEOUT_S = 20L
        const val CONNECT_TIMEOUT_MS = 10_000
        val END = ByteArray(0)
    }
}

/**
 * An HTTP proxy on this device's loopback whose connections leave through [tunnel]: CONNECT for
 * HTTPS and anything else, plain HTTP requests one per connection.
 */
class LocalProxy(private val tunnel: NetTunnel) : Closeable {
    private val server = ServerSocket(0, 50, InetAddress.getLoopbackAddress())

    val port: Int
        get() = server.localPort

    init {
        thread(name = "w7link-proxy", isDaemon = true) {
            try {
                while (true) {
                    val client = server.accept()
                    thread(name = "w7link-proxy-conn", isDaemon = true) { serve(client) }
                }
            } catch (_: IOException) {
            }
        }
    }

    override fun close() {
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        try {
            client.tcpNoDelay = true
            val lines = String(readHead(client.getInputStream()), Charsets.ISO_8859_1).split("\r\n")
            val request = lines[0].split(' ')
            if (request.size != 3) return answer(client, "400 Bad Request")
            if (request[0].equals("CONNECT", ignoreCase = true)) {
                val colon = request[1].lastIndexOf(':')
                val host = request[1].substring(0, colon).removeSurrounding("[", "]")
                val pipe = tunnel.open(client, host, request[1].substring(colon + 1).toInt()) ?: return answer(client, "502 Bad Gateway")
                client.getOutputStream().run {
                    write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray())
                    flush()
                }
                pipe.start()
            } else {
                val uri = URI(request[1])
                val host = uri.host ?: return answer(client, "400 Bad Request")
                val path = uri.rawPath.orEmpty().ifEmpty { "/" } + (uri.rawQuery?.let { "?$it" } ?: "")
                // One request per connection: the next could be for another host.
                val kept = lines.drop(1).filter { line ->
                    line.isNotEmpty() && HOP_HEADERS.none { line.startsWith("$it:", ignoreCase = true) }
                }
                val head = (listOf("${request[0]} $path ${request[2]}") + kept + "Connection: close" + "" + "").joinToString("\r\n")
                val pipe = tunnel.open(client, host, if (uri.port > 0) uri.port else 80) ?: return answer(client, "502 Bad Gateway")
                pipe.start(head.toByteArray(Charsets.ISO_8859_1))
            }
        } catch (e: Exception) {
            runCatching { client.close() }
        }
    }

    /** Up to and including the blank line, and not a byte more: the body belongs to the tunnel. */
    private fun readHead(input: InputStream): ByteArray {
        val head = ByteArrayOutputStream()
        var matched = 0
        while (matched < 4) {
            val b = input.read()
            if (b < 0 || head.size() >= MAX_HEAD) throw IOException("bad request head")
            head.write(b)
            matched = if (b == HEAD_END[matched].code) matched + 1 else if (b == '\r'.code) 1 else 0
        }
        return head.toByteArray()
    }

    private fun answer(client: Socket, status: String) {
        runCatching {
            client.getOutputStream().write("HTTP/1.1 $status\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
            client.close()
        }
    }

    private companion object {
        const val MAX_HEAD = 16 * 1024
        const val HEAD_END = "\r\n\r\n"
        val HOP_HEADERS = listOf("Connection", "Proxy-Connection", "Proxy-Authorization", "Keep-Alive")
    }
}
