// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.content.ComponentName
import android.content.Context
import android.media.AudioManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.SystemClock
import android.view.KeyEvent
import io.github.sype0.w7link.common.Proto
import org.json.JSONArray
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
    private var lastPosition = -1L
    private var lastPositionAt = 0L
    private var lastSpeed = 0f

    private val callback = object : MediaController.Callback() {
        override fun onMetadataChanged(metadata: MediaMetadata?) = push(false)
        override fun onPlaybackStateChanged(state: PlaybackState?) = push(false)
        override fun onQueueChanged(queue: MutableList<MediaSession.QueueItem>?) = push(false)
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
        val playback = controller?.playbackState
        val playing = playback?.state == PlaybackState.STATE_PLAYING
        // Where playback is now, not where it was when the player last reported.
        val position = when {
            playback == null || playback.position < 0 -> -1L
            playing -> playback.position + ((SystemClock.elapsedRealtime() - playback.lastPositionUpdateTime) * playback.playbackSpeed).toLong()
            else -> playback.position
        }
        val message = Proto.msg(
            "media",
            "has" to (controller != null),
            "title" to (meta?.getString(MediaMetadata.METADATA_KEY_TITLE) ?: ""),
            "artist" to (meta?.getString(MediaMetadata.METADATA_KEY_ARTIST) ?: ""),
            "playing" to playing,
            "dur" to (meta?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L),
            "pos" to position,
            "speed" to (playback?.playbackSpeed?.toDouble() ?: 1.0),
            "queue" to upNext(controller?.queue, playback?.activeQueueItemId ?: -1L),
            "queueAt" to (playback?.activeQueueItemId ?: -1L),
            "vol" to audio.getStreamVolume(AudioManager.STREAM_MUSIC),
            "volMax" to audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
        )
        // The position moves by itself; it alone is no reason to send again.
        val text = JSONObject(message.toString()).apply { remove("pos") }.toString() + (position < 0)
        if (!force && text == lastSent && !positionJumped(position)) return
        lastSent = text
        lastPosition = position
        lastPositionAt = SystemClock.elapsedRealtime()
        lastSpeed = if (playing) playback?.playbackSpeed ?: 1f else 0f
        send(message)
    }

    /** The playing item and the ones after it, as many as fit a message comfortably. */
    private fun upNext(queue: List<MediaSession.QueueItem>?, active: Long): JSONArray {
        val items = JSONArray()
        if (queue == null) return items
        val from = queue.indexOfFirst { it.queueId == active }.coerceAtLeast(0)
        for (item in queue.subList(from, minOf(queue.size, from + MAX_QUEUE))) {
            items.put(
                JSONObject()
                    .put("id", item.queueId)
                    .put("title", item.description.title?.toString().orEmpty())
                    .put("sub", item.description.subtitle?.toString().orEmpty())
            )
        }
        return items
    }

    /** True after a seek: the position is not where steady playback from the last one sent would be. */
    private fun positionJumped(position: Long): Boolean {
        if (position < 0 || lastPosition < 0) return false
        val expected = lastPosition + ((SystemClock.elapsedRealtime() - lastPositionAt) * lastSpeed).toLong()
        return kotlin.math.abs(position - expected) > SEEK_SLACK_MS
    }

    fun command(cmd: String, position: Long = 0) {
        when (cmd) {
            "seek" -> current?.transportControls?.seekTo(position)
            // For this one the number is a queue item's id.
            "queue" -> current?.transportControls?.skipToQueueItem(position)
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

    private companion object {
        const val SEEK_SLACK_MS = 2_000L
        const val MAX_QUEUE = 25
    }
}
