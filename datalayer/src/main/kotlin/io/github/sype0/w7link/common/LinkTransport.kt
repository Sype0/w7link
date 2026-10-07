// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PeerDirectory
import com.heartline.shared.sync.PeerProbe
import com.heartline.shared.sync.SyncTransport
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext

/**
 * [SyncTransport] over the companion's own encrypted Bluetooth link, in place of the
 * Wearable Data Layer, so the phone needs no Google Play services. Inbound data is
 * pushed in through [deliver] by the app's [LinkHub.Receiver].
 */
class LinkTransport :
    SyncTransport,
    PeerDirectory {
    private val inbox = MutableSharedFlow<Envelope>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.SUSPEND)

    override val incoming: Flow<Envelope> = inbox.asSharedFlow()

    suspend fun deliver(envelope: Envelope) {
        HLog.i(TAG, "received ${envelope.path} (${envelope.data.size} B)")
        inbox.emit(envelope)
    }

    /** The link reaches exactly one paired device, and only when both apps run, so there is no "app missing". */
    override suspend fun probe(): PeerProbe = if (LinkHub.connected) PeerProbe.REACHABLE else PeerProbe.NO_DEVICE

    fun peerName(): String? = LinkHub.peerName

    override suspend fun send(path: String, data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        LinkHub.sendEnvelope(path, data).also { log(it, "sent", path, data.size.toLong()) }
    }

    override suspend fun sendLarge(path: String, data: ByteArray): Boolean = if (data.size <= SMALL) send(path, data) else sendStream(path, data.inputStream())

    override suspend fun sendStream(path: String, input: InputStream): Boolean = withContext(Dispatchers.IO) {
        runCatching { input.use { LinkHub.sendStream(path, it) } }
            .onFailure { HLog.w(TAG, "stream $path failed", it) }
            .getOrDefault(false)
            .also { log(it, "streamed", path, -1) }
    }

    private fun log(ok: Boolean, verb: String, path: String, size: Long) {
        if (ok) HLog.i(TAG, "$verb $path" + if (size >= 0) " ($size B)" else "") else HLog.w(TAG, "$path: no peer")
    }

    companion object {
        const val TAG = "Heartline/Sync"
        private const val SMALL = 48 * 1024
    }
}
