// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject

/**
 * Where the link service and the health sync code meet. The service attaches a
 * frame sender while a paired peer is connected and feeds inbound frames to
 * [onFrame]; the sync side sends through [sendEnvelope] / [sendStream] and gets
 * inbound data on its [receiver].
 */
object LinkHub {
    interface Receiver {
        fun onMessage(path: String, data: ByteArray)

        /** Called on the link's thread: hand [input] to another thread and return. */
        fun onStream(path: String, input: InputStream)
    }

    /** Sends one frame; false when the link is gone. */
    fun interface FrameSender {
        fun send(kind: Int, body: ByteArray): Boolean
    }

    @Volatile
    var receiver: Receiver? = null

    /** The watch's internet through the phone: TCP connections carried as [Proto.KIND_NET] frames. */
    val net = NetTunnel { sendFrame(Proto.KIND_NET, it) }

    /** Streams the companion takes itself, by path; like [Receiver.onStream], each runs on the link's thread. */
    val streamRoutes = ConcurrentHashMap<String, (InputStream) -> Unit>()

    /** The other device's model name, once it has said hello. */
    @Volatile
    var peerName: String? = null

    /** Opens a deep link in this app, or returns false when it can't come to the front right now. */
    @Volatile
    var localOpener: ((String) -> Boolean)? = null

    @Volatile
    private var sender: FrameSender? = null
    private val opens = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val connectListeners = CopyOnWriteArrayList<() -> Unit>()
    private val inbound = ConcurrentHashMap<Int, ChunkStream>()
    private val nextStream = AtomicInteger()

    val connected: Boolean
        get() = sender != null

    /** [listener] runs on the link's thread each time a paired peer connects. */
    fun onConnect(listener: () -> Unit) {
        connectListeners.add(listener)
    }

    fun attach(frames: FrameSender) {
        sender = frames
        connectListeners.forEach { runCatching(it) }
    }

    fun detach() {
        sender = null
        peerName = null
        net.closeAll()
        inbound.values.forEach { it.abort() }
        inbound.clear()
        opens.values.forEach { it.complete(false) }
        opens.clear()
    }

    private fun sendJson(message: JSONObject) = sendFrame(Proto.KIND_JSON, message.toString().toByteArray())

    /** Asks the other device to open [uri]; completes with whether it did. Null when there is no peer. */
    fun requestOpen(uri: String): CompletableFuture<Boolean>? {
        val id = nextStream.incrementAndGet()
        val reply = CompletableFuture<Boolean>()
        opens[id] = reply
        if (!sendJson(Proto.msg("open", "id" to id, "uri" to uri))) {
            opens.remove(id)
            return null
        }
        return reply
    }

    /** Handles the companion messages that belong to the hub; false for everything else. */
    fun onJson(message: JSONObject): Boolean {
        when (message.optString("t")) {
            "hello" -> peerName = message.optString("name").ifEmpty { null }
            "open" -> {
                val opened = localOpener?.invoke(message.getString("uri")) ?: false
                sendJson(Proto.msg("open_result", "id" to message.getInt("id"), "ok" to opened))
            }
            "open_result" -> opens.remove(message.getInt("id"))?.complete(message.getBoolean("ok"))
            else -> return false
        }
        return true
    }

    fun sendFrame(kind: Int, body: ByteArray): Boolean = sender?.send(kind, body) ?: false

    fun sendEnvelope(path: String, data: ByteArray): Boolean {
        val frame = ByteArrayOutputStream(data.size + path.length + 8)
        DataOutputStream(frame).run {
            writeUTF(path)
            write(data)
        }
        return sendFrame(Proto.KIND_ENVELOPE, frame.toByteArray())
    }

    /** Sends [input] in chunks, so a large transfer neither sits in memory nor holds up other frames. */
    fun sendStream(path: String, input: InputStream): Boolean {
        val id = nextStream.incrementAndGet()
        val buffer = ByteArray(CHUNK)
        var first = true
        while (true) {
            val n = readFully(input, buffer)
            val last = n < CHUNK
            val frame = ByteArrayOutputStream(n + path.length + 16)
            DataOutputStream(frame).run {
                writeInt(id)
                writeByte((if (first) FLAG_FIRST else 0) or (if (last) FLAG_LAST else 0))
                if (first) writeUTF(path)
                write(buffer, 0, n)
            }
            if (!sendFrame(Proto.KIND_STREAM, frame.toByteArray())) return false
            if (last) return true
            first = false
        }
    }

    fun onFrame(kind: Int, body: ByteArray) {
        val input = DataInputStream(ByteArrayInputStream(body))
        when (kind) {
            Proto.KIND_ENVELOPE -> {
                val path = input.readUTF()
                receiver?.onMessage(path, input.readBytes())
            }
            Proto.KIND_NET -> net.onFrame(body)
            Proto.KIND_STREAM -> {
                val id = input.readInt()
                val flags = input.readUnsignedByte()
                if (flags and FLAG_FIRST != 0) {
                    val path = input.readUTF()
                    val stream = ChunkStream()
                    inbound[id] = stream
                    val route = streamRoutes[path]
                    if (route != null) route(stream) else receiver?.onStream(path, stream) ?: stream.abort()
                }
                val stream = inbound[id] ?: return
                val delivered = stream.offer(input.readBytes())
                if (!delivered || flags and FLAG_LAST != 0) {
                    if (delivered) stream.finish() else stream.abort()
                    inbound.remove(id)
                }
            }
        }
    }

    private fun readFully(input: InputStream, buffer: ByteArray): Int {
        var total = 0
        while (total < buffer.size) {
            val n = input.read(buffer, total, buffer.size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    /** An InputStream fed chunk by chunk from the link's thread and read from another. */
    private class ChunkStream : InputStream() {
        private val chunks = LinkedBlockingQueue<ByteArray>(QUEUE)
        private var current = ByteArray(0)
        private var position = 0

        @Volatile
        private var aborted = false

        /** False when the reader stopped taking data; the link must not wait on it forever. */
        fun offer(chunk: ByteArray): Boolean = !aborted && chunks.offer(chunk, READER_TIMEOUT_S, TimeUnit.SECONDS)

        fun finish() {
            if (!chunks.offer(END, READER_TIMEOUT_S, TimeUnit.SECONDS)) abort()
        }

        fun abort() {
            aborted = true
        }

        override fun read(): Int {
            val one = ByteArray(1)
            return if (read(one, 0, 1) < 0) -1 else one[0].toInt() and 0xff
        }

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            if (length == 0) return 0
            while (position >= current.size) {
                if (current === END) return -1
                val next = chunks.poll(1, TimeUnit.SECONDS)
                if (next == null) {
                    if (aborted) throw IOException("link lost mid-stream")
                    continue
                }
                current = next
                position = 0
            }
            val n = minOf(length, current.size - position)
            System.arraycopy(current, position, target, offset, n)
            position += n
            return n
        }
    }

    private const val CHUNK = 32 * 1024
    private const val QUEUE = 16
    private const val READER_TIMEOUT_S = 30L
    private const val FLAG_FIRST = 1
    private const val FLAG_LAST = 2
    private val END = ByteArray(0)
}
