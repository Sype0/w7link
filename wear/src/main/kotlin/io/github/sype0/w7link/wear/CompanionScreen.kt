// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.wear

import android.Manifest
import android.app.Activity
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Bluetooth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.Button
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.Text
import com.heartline.wear.R
import com.heartline.wear.ui.theme.WearColors
import io.github.sype0.w7link.common.LinkHub

/** The running link service, re-read by the caller whenever the service reports a change. */
@Composable
fun rememberLink(): LinkService? {
    var changes by remember { mutableIntStateOf(0) }
    DisposableEffect(Unit) {
        val listener: () -> Unit = { changes++ }
        LinkService.uiListeners.add(listener)
        onDispose { LinkService.uiListeners.remove(listener) }
    }
    // Reading the counter is what subscribes the caller.
    return LinkService.instance.takeIf { changes >= 0 }
}

/** True while the link can't come up without the user: no permission yet, never paired, or a code to confirm. */
@Composable
fun companionNeedsUser(): Boolean {
    val context = LocalContext.current
    val link = rememberLink()
    return !LinkService.hasPermissions(context) || link == null || !link.paired || link.state == LinkService.State.PAIRING
}

private val wanted = arrayOf(
    Manifest.permission.BLUETOOTH_ADVERTISE,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
    Manifest.permission.ACTIVITY_RECOGNITION,
)

/** Pairing with the phone and, once connected, its media controls and the small tools. */
@Composable
fun WatchCompanionScreen() {
    val context = LocalContext.current
    val link = rememberLink()
    var granted by remember { mutableStateOf(LinkService.hasPermissions(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = LinkService.hasPermissions(context)
    }
    LaunchedEffect(granted) { if (granted) LinkService.start(context) }
    val state = link?.state ?: LinkService.State.WAITING
    // Samsung's own controller drives the session the link service publishes; the keys below stand in where it's missing.
    val controller = remember { context.packageManager.getLaunchIntentForPackage(MEDIA_CONTROLLER) }
    val vpnConsent = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (it.resultCode == Activity.RESULT_OK) NetService.start(context)
    }
    // Wear OS may have no screen to ask for VPN access on; then only adb can grant it.
    var vpnBlocked by remember { mutableStateOf(false) }
    val list = rememberTransformingLazyColumnState()
    ScreenScaffold(scrollState = list) { padding ->
        TransformingLazyColumn(state = list, contentPadding = padding) {
            item { ListHeader { Text(stringResource(R.string.cmp_title)) } }
            when {
                !granted -> {
                    item { Note(stringResource(R.string.cmp_permission_hint)) }
                    item { Action(Icons.Rounded.Bluetooth, stringResource(R.string.cmp_grant)) { permissions.launch(wanted) } }
                }
                state == LinkService.State.PAIRING -> {
                    item { Note(stringResource(R.string.cmp_pair_compare)) }
                    item {
                        Text(
                            link?.pairCode.orEmpty(),
                            style = MaterialTheme.typography.displayMedium,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    item { Action(Icons.Rounded.Check, stringResource(R.string.cmp_pair_confirm), primary = true) { link?.confirmPairing() } }
                    item { Action(null, stringResource(R.string.cmp_cancel)) { link?.cancelPairing() } }
                }
                state == LinkService.State.CONNECTED && link != null -> {
                    item {
                        val battery = if (link.phoneBattery >= 0) "\n" + stringResource(R.string.cmp_phone_battery, link.phoneBattery) else ""
                        Note((LinkHub.peerName ?: stringResource(R.string.cmp_state_connected)) + battery)
                    }
                    val media = link.media
                    item {
                        val track = listOf(media?.optString("title").orEmpty(), media?.optString("artist").orEmpty()).filter { it.isNotEmpty() }
                        Text(
                            if (media == null || track.isEmpty()) stringResource(R.string.cmp_nothing_playing) else track.joinToString("\n"),
                            style = MaterialTheme.typography.titleSmall,
                            textAlign = TextAlign.Center,
                            maxLines = 3,
                            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                        )
                    }
                    if (controller != null) {
                        item { Action(Icons.Rounded.MusicNote, stringResource(R.string.cmp_media_controls)) { context.startActivity(controller) } }
                    } else {
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
                        }
                    }
                    item {
                        Action(
                            Icons.Rounded.NotificationsActive,
                            stringResource(if (link.findingPhone) R.string.cmp_stop_find else R.string.cmp_find_phone),
                            primary = link.findingPhone,
                        ) { link.findPhone(!link.findingPhone) }
                    }
                    item {
                        Action(
                            Icons.Rounded.Public,
                            stringResource(if (NetService.running) R.string.cmp_internet_off else R.string.cmp_internet_on),
                            primary = NetService.running,
                        ) {
                            if (NetService.running) {
                                NetService.stop(context)
                            } else {
                                try {
                                    val consent = VpnService.prepare(context)
                                    if (consent == null) NetService.start(context) else vpnConsent.launch(consent)
                                } catch (e: Exception) {
                                    vpnBlocked = true
                                }
                            }
                        }
                    }
                    if (vpnBlocked) {
                        item { Note(stringResource(R.string.cmp_internet_adb, context.packageName)) }
                    }
                    item { Note(stringResource(R.string.cmp_steps_today, link.stepsToday)) }
                    item { Action(Icons.Rounded.LinkOff, stringResource(R.string.cmp_unpair)) { link.unpair() } }
                }
                else -> {
                    item { Note(stringResource(LinkService.statusText(state))) }
                    if (link?.paired == true) {
                        item { Note(stringResource(R.string.cmp_steps_today, link.stepsToday)) }
                        item { Action(Icons.Rounded.LinkOff, stringResource(R.string.cmp_unpair)) { link.unpair() } }
                    } else {
                        item { Note(stringResource(R.string.cmp_pair_hint)) }
                    }
                }
            }
        }
    }
}

private const val MEDIA_CONTROLLER = "com.samsung.android.mediacontroller"

@Composable
private fun Note(text: String) = Text(
    text,
    style = MaterialTheme.typography.bodySmall,
    color = WearColors.onSurfaceVariant,
    textAlign = TextAlign.Center,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
)

@Composable
private fun Action(icon: ImageVector?, label: String, primary: Boolean = false, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = if (primary) ButtonDefaults.buttonColors() else ButtonDefaults.filledTonalButtonColors(),
        icon = icon?.let { { Icon(it, contentDescription = null, modifier = Modifier.size(22.dp)) } },
        label = { Text(label, maxLines = 2) },
    )
}

@Composable
private fun MediaKey(icon: ImageVector, description: Int, onClick: () -> Unit) {
    FilledTonalIconButton(onClick = onClick) {
        Icon(icon, contentDescription = stringResource(description))
    }
}
