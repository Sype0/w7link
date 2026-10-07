// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.phone.sync

import com.heartline.datalayer.diag.HLog
import com.heartline.shared.diag.RemoteLogs
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.PhoneSyncEngine
import com.heartline.shared.sync.Protocol
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.LinkTransport
import java.io.InputStream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Receives records from the watch over the companion link, which the link service keeps up even
 * when the phone UI isn't running. [scope] is app-wide: a save must not be cancelled half way.
 */
class PhoneLinkReceiver(
    private val engine: PhoneSyncEngine,
    private val transport: LinkTransport,
    private val remoteLogs: RemoteLogs,
    private val inbox: WatchLogInbox,
    private val scope: CoroutineScope,
) : LinkHub.Receiver {
    override fun onMessage(path: String, data: ByteArray) {
        val envelope = Envelope(path, data)
        scope.launch {
            runCatching {
                transport.deliver(envelope)
                engine.handle(envelope)
            }.onFailure { HLog.w(LinkTransport.TAG, "could not handle $path", it) }
        }
    }

    override fun onStream(path: String, input: InputStream) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                input.use {
                    if (path.startsWith(Protocol.LOGS_SEGMENT_PREFIX)) {
                        // A segment of the watch's log: streamed into a file, not read into memory.
                        remoteLogs.onSegment(path.removePrefix(Protocol.LOGS_SEGMENT_PREFIX), it)
                    } else if (path.startsWith(Protocol.LOGS_ARCHIVE_PREFIX)) {
                        // A finished log segment or raw session the watch moves here: into the archive, then acked.
                        val parts = path.removePrefix(Protocol.LOGS_ARCHIVE_PREFIX).split('/', limit = 2)
                        inbox.receive(parts[0], parts.getOrElse(1) { "" }, it)
                    } else {
                        engine.handle(Envelope(path, it.readBytes()))
                    }
                }
            }.onFailure { HLog.w(LinkTransport.TAG, "stream $path failed", it) }
        }
    }
}
