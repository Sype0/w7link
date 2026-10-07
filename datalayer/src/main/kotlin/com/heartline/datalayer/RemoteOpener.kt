// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Selin and Heartline contributors

package com.heartline.datalayer

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import com.heartline.datalayer.diag.HLog
import io.github.sype0.w7link.common.LinkHub
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Opens a screen on the other device right away (no notification to tap), via a `heartline://`
 * deep link the other app's MainActivity handles. The request travels over the companion link;
 * the other app only honours it while it is on screen, because Android blocks activity launches
 * from the background.
 */
class RemoteOpener {
    /** @return true when the other device opened the screen. */
    suspend fun open(uri: String): Boolean {
        val reply = LinkHub.requestOpen(uri) ?: return false.also { HLog.w(TAG, "open $uri: no peer") }
        val ok = withContext(Dispatchers.IO) { runCatching { reply.get(TIMEOUT_S, TimeUnit.SECONDS) }.getOrDefault(false) }
        HLog.i(TAG, "open $uri -> $ok")
        return ok
    }

    private companion object {
        const val TAG = "Heartline/Open"
        const val TIMEOUT_S = 3L
    }
}

/** Answers the other device's open requests: launches the deep link when one of this app's activities is showing. */
class ForegroundOpener(private val app: Application) : Application.ActivityLifecycleCallbacks {
    @Volatile
    private var started = 0

    init {
        app.registerActivityLifecycleCallbacks(this)
        LinkHub.localOpener = ::open
    }

    private fun open(uri: String): Boolean {
        if (started == 0) return false
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(uri))
            .addCategory(Intent.CATEGORY_BROWSABLE)
            .setPackage(app.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(intent) }.onFailure { HLog.w("Heartline/Open", "open $uri failed", it) }.isSuccess
    }

    override fun onActivityStarted(activity: Activity) {
        started++
    }

    override fun onActivityStopped(activity: Activity) {
        started--
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}

    override fun onActivityResumed(activity: Activity) {}

    override fun onActivityPaused(activity: Activity) {}

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}

    override fun onActivityDestroyed(activity: Activity) {}
}

/** Deep links understood by each app's MainActivity. */
object DeepLinks {
    const val SCHEME = "heartline"
    const val WATCH_HOST = "watch"
    const val PHONE_HOST = "phone"

    fun watch(route: String) = "$SCHEME://$WATCH_HOST/$route"

    fun phone(route: String) = "$SCHEME://$PHONE_HOST/$route"

    /** The route part of a heartline:// link for [host], or null for anything else. */
    fun route(uri: Uri?, host: String): String? =
        uri?.takeIf { it.scheme == SCHEME && it.host == host }?.let { u ->
            val path = u.path.orEmpty().trimStart('/')
            if (u.query.isNullOrEmpty()) path else "$path?${u.query}"
        }?.takeIf { it.isNotEmpty() }
}
