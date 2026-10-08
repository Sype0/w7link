// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.heartline.phone.R
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.VerticalGap
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.theme.HeartlineTheme
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.Proto
import java.io.ByteArrayInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import kotlin.concurrent.thread

/** Sends an APK picked on the phone to the watch, which installs it once the user confirms there. */
object ApkInstall {
    sealed interface Status {
        data object Idle : Status

        /** [total] is -1 when the file's size isn't known. */
        data class Sending(val name: String, val sent: Long, val total: Long) : Status

        /** All of it is on the watch, which hasn't said yet how the install went. */
        data class Confirming(val name: String) : Status

        data class Done(val name: String, val ok: Boolean, val message: String) : Status
    }

    var status: Status by mutableStateOf(Status.Idle)
        private set

    fun send(context: Context, uri: Uri) {
        if (status is Status.Sending) return
        val resolver = context.applicationContext.contentResolver
        var name = uri.lastPathSegment ?: "APK"
        var size = -1L
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { row ->
                if (row.moveToFirst()) {
                    row.getString(0)?.let { name = it }
                    if (!row.isNull(1)) size = row.getLong(1)
                }
            }
        }
        status = Status.Sending(name, 0, size)
        thread(name = "w7link-apk") {
            val sent = runCatching {
                resolver.openInputStream(uri)!!.use { apk ->
                    val header = ByteArrayInputStream(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(size).array())
                    LinkHub.sendStream(Proto.APK_STREAM, SequenceInputStream(header, Counted(apk) { status = Status.Sending(name, it, size) }))
                }
            }.getOrDefault(false)
            // The watch may already have answered, when it gave up partway.
            if (status is Status.Sending) status = if (sent) Status.Confirming(name) else Status.Done(name, false, "")
        }
    }

    fun onResult(ok: Boolean, message: String) {
        val name = when (val now = status) {
            is Status.Sending -> now.name
            is Status.Confirming -> now.name
            else -> return
        }
        status = Status.Done(name, ok, message)
    }

    private class Counted(input: InputStream, private val onProgress: (Long) -> Unit) : FilterInputStream(input) {
        private var count = 0L

        override fun read(target: ByteArray, offset: Int, length: Int): Int {
            val n = super.read(target, offset, length)
            if (n > 0) {
                count += n
                onProgress(count)
            }
            return n
        }
    }
}

/** The Apps tab: pick an APK on the phone and the watch installs it. */
@Composable
fun WatchAppsScreen(listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    val colors = HeartlineTheme.colors
    val connected = rememberLink()?.state == LinkService.State.CONNECTED
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) ApkInstall.send(context, uri)
    }
    val status = ApkInstall.status
    ReachabilityScaffold(title = stringResource(R.string.cmp_tab_apps), listState = listState) {
        item {
            RoundedCard(Modifier.gutter()) {
                Text(
                    stringResource(if (connected || status is ApkInstall.Status.Sending) R.string.cmp_apk_hint else R.string.cmp_apk_needs_watch),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.onSurfaceVariant,
                )
                val progress = when (status) {
                    ApkInstall.Status.Idle -> null
                    is ApkInstall.Status.Sending ->
                        stringResource(R.string.cmp_apk_sending, status.name, if (status.total > 0) (status.sent * 100 / status.total).toInt() else 0)
                    is ApkInstall.Status.Confirming -> stringResource(R.string.cmp_apk_confirm, status.name)
                    is ApkInstall.Status.Done ->
                        if (status.ok) stringResource(R.string.cmp_apk_done, status.name) else stringResource(R.string.cmp_apk_failed, status.name, status.message)
                }
                if (progress != null) {
                    VerticalGap(16)
                    Text(progress, style = MaterialTheme.typography.bodyMedium, color = colors.onBackground)
                }
                if (status is ApkInstall.Status.Sending) {
                    VerticalGap(12)
                    if (status.total > 0) {
                        LinearProgressIndicator(progress = { status.sent.toFloat() / status.total }, modifier = Modifier.fillMaxWidth())
                    } else {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                } else if (connected) {
                    VerticalGap(16)
                    PillButton(stringResource(R.string.cmp_apk_pick), { pick.launch(arrayOf("application/vnd.android.package-archive")) })
                }
            }
        }
    }
}
