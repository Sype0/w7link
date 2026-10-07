// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.view.KeyEvent
import io.github.sype0.w7link.common.Proto
import org.json.JSONObject

/** Mirrors the phone's current media session to the watch and applies its commands. */
class MediaBridge(
    private val context: Context,
    private val handler: Handler,
    private val send: (JSONObject) -> Unit,
) : MediaSessionManager.OnActiveSessionsChangedListener {
    private val sessions = context.getSystemService(MediaSessionManager::class.java)
    private val audio = context.getSystemService(AudioManager::class.java)
    private val listener = ComponentName(context, NotifListener::class.java)
    private var current: MediaController? = null
    private var started = false
    private var lastSent: String? = null

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push(false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = push(false)
        override fun onSessionDestroyed() = refresh()
    }

    /** Needs notification access; called again once the listener connects. */
    fun start() {
        handler.post {
            if (!started) {
                try {
                    sessions.addOnActiveSessionsChangedListener(this, listener, handler)
                    started = true
                } catch (_: SecurityException) {
                }
            }
            refresh()
        }
    }

    fun stop() {
        handler.post {
            if (started) sessions.removeOnActiveSessionsChangedListener(this)
            started = false
            current?.unregisterCallback(callback)
            current = null
        }
    }

    override fun onActiveSessionsChanged(controllers: MutableList<MediaController>?) {
        val next = controllers?.firstOrNull()
        if (next?.sessionToken != current?.sessionToken) {
            current?.unregisterCallback(callback)
            current = next
            next?.registerCallback(callback, handler)
        }
        push(false)
    }

    private fun refresh() {
        if (!started) return
        try {
            onActiveSessionsChanged(sessions.getActiveSessions(listener))
        } catch (_: SecurityException) {
        }
    }

    fun push(force: Boolean) {
        val controller = current.takeIf { CompanionPref.MEDIA.get(context) }
        val meta = controller?.metadata
        val message = Proto.msg(
            "media",
            "has" to (controller != null),
            "title" to (meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""),
            "artist" to (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""),
            "playing" to (controller?.playbackState?.state == PlaybackState.STATE_PLAYING),
            "vol" to audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            "volMax" to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        val text = message.toString()
        if (!force && text == lastSent) return
        lastSent = text
        send(message)
    }

    fun command(cmd: String) {
        when (cmd) {
            // Media keys reach whichever app last played, even without an active session.
            "play_pause" -> key(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)
            "next" -> key(KeyEvent.KEYCODE_MEDIA_NEXT)
            "prev" -> key(KeyEvent.KEYCODE_MEDIA_PREVIOUS)
            "vol_up" -> volume(AudioManager.ADJUST_RAISE)
            "vol_down" -> volume(AudioManager.ADJUST_LOWER)
        }
    }

    private fun key(code: Int) {
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audio.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    private fun volume(direction: Int) {
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        handler.post { push(false) }
    }
}
