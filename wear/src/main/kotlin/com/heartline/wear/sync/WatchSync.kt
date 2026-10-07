// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.wear.sync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.heartline.datalayer.diag.HLog
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.LinkTransport
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import com.heartline.shared.diag.LogOffload
import com.heartline.shared.sync.Envelope
import com.heartline.shared.sync.WatchSyncEngine
import com.heartline.wear.data.WatchRecordStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

/** Pushes the outbox to the phone; retried with backoff until everything is acked. */
class SyncWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params),
    KoinComponent {
    private val engine: WatchSyncEngine by inject()
    private val store: WatchRecordStore by inject()
    private val logs: LogOffload by inject()

    override suspend fun doWork(): Result {
        engine.flush()
        // Log segments and raw sensor sessions that haven't reached the phone yet.
        logs.run()
        return if (store.pending().isEmpty() && !logs.stalled) Result.success() else Result.retry()
    }

    companion object {
        private const val NAME = "heartline-sync"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncWorker>()
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.REPLACE, request)
        }
    }
}

/** Receives acks and delete requests from the phone, over the companion link. */
class WatchLinkReceiver(
    private val engine: WatchSyncEngine,
    private val transport: LinkTransport,
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
            runCatching { engine.handle(Envelope(path, input.use { it.readBytes() })) }
                .onFailure { HLog.w(LinkTransport.TAG, "stream $path failed", it) }
        }
    }
}
