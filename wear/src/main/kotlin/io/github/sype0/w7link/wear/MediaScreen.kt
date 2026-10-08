// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.wear

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import org.json.JSONObject

/**
 * The phone's playback, controlled from the watch: the track, previous / play-pause / next, the
 * volume, then what plays next (tap to jump there). The home's Media page, and the screen the Now
 * bar opens while something plays.
 */
@Composable
fun MediaScreen() {
    val link = rememberLink()
    val connected = link != null && link.state == LinkService.State.CONNECTED
    val media = if (connected) link?.media else null
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.cmp_media_title)) } }
            item {
                val seconds = (media?.optLong("dur") ?: 0L) / 1000
                val length = if (seconds > 0) "%d:%02d".format(seconds / 60, seconds % 60) else ""
                val track = listOf(media?.optString("title").orEmpty(), media?.optString("artist").orEmpty(), length).filter { it.isNotEmpty() }
                Text(
                    if (track.isEmpty()) stringResource(R.string.cmp_nothing_playing) else track.joinToString("\n"),
                    style = MaterialTheme.typography.titleSmall,
                    textAlign = TextAlign.Center,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                )
            }
            if (connected && link != null) {
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MediaKey(Icons.Rounded.SkipPrevious, R.string.cmp_prev) { link.mediaCommand("prev") }
                        MediaKey(if (media?.optBoolean("playing") == true) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, R.string.cmp_play_pause) {
                            link.mediaCommand("play_pause")
                        }
                        MediaKey(Icons.Rounded.SkipNext, R.string.cmp_next) { link.mediaCommand("next") }
                    }
                }
                item {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MediaKey(Icons.AutoMirrored.Rounded.VolumeDown, R.string.cmp_vol_down) { link.mediaCommand("vol_down") }
                        MediaKey(Icons.AutoMirrored.Rounded.VolumeUp, R.string.cmp_vol_up) { link.mediaCommand("vol_up") }
                    }
                }
                if (media != null) {
                    item { Note(stringResource(R.string.cmp_volume, media.optInt("vol"), media.optInt("volMax"))) }
                    // The phone sends the playing item first, then the ones after it.
                    val queue = media.optJSONArray("queue")
                    val count = queue?.length() ?: 0
                    if (count > 0) {
                        item { ListHeader { Text(stringResource(R.string.cmp_queue)) } }
                        val current = media.optLong("queueAt", -1L)
                        items(count) { i ->
                            val entry = queue!!.getJSONObject(i)
                            QueueRow(entry, playing = entry.optLong("id") == current) { link.playQueueItem(entry.optLong("id")) }
                        }
                    }
                } else {
                    item { Note(stringResource(R.string.cmp_media_hint)) }
                }
            } else {
                item { Note(stringResource(LinkService.statusText(link?.state ?: LinkService.State.WAITING))) }
            }
        }
    }
}

@Composable
private fun QueueRow(entry: JSONObject, playing: Boolean, onClick: () -> Unit) {
    val sub = entry.optString("sub")
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (playing) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = if (playing) {
            { Icon(Icons.Rounded.MusicNote, contentDescription = stringResource(R.string.cmp_queue_now), modifier = Modifier.size(20.dp)) }
        } else {
            null
        },
        label = { Text(entry.optString("title").ifEmpty { stringResource(R.string.cmp_queue_untitled) }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        secondaryLabel = if (sub.isEmpty()) null else ({ Text(sub, maxLines = 1, overflow = TextOverflow.Ellipsis) }),
    )
}
