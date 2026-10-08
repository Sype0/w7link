// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.Manifest
import android.content.Context
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.DirectionsWalk
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.BatteryAlert
import androidx.compose.material.icons.rounded.Call
import androidx.compose.material.icons.rounded.DarkMode
import androidx.compose.material.icons.rounded.DoNotDisturbOn
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.LinkOff
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.NotificationsActive
import androidx.compose.material.icons.rounded.NotificationsPaused
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Vibration
import androidx.compose.material.icons.rounded.VisibilityOff
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import com.heartline.phone.R
import com.heartline.phone.ui.components.CardRow
import com.heartline.phone.ui.components.IconBadge
import com.heartline.phone.ui.components.PillButton
import com.heartline.phone.ui.components.ReachabilityScaffold
import com.heartline.phone.ui.components.RoundedCard
import com.heartline.phone.ui.components.SectionHeader
import com.heartline.phone.ui.components.TonalPillButton
import com.heartline.phone.ui.components.VerticalGap
import com.heartline.phone.ui.components.gutter
import com.heartline.phone.ui.settings.ChoiceDialog
import com.heartline.phone.ui.settings.OneUiSwitch
import com.heartline.phone.ui.theme.HeartlineTheme
import com.heartline.phone.ui.theme.ThemeMode
import com.heartline.phone.ui.theme.ThemePrefs
import com.heartline.phone.ui.theme.ThemeStyle
import io.github.sype0.w7link.common.LinkHub
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

private val bluetoothPermissions = arrayOf(
    Manifest.permission.BLUETOOTH_SCAN,
    Manifest.permission.BLUETOOTH_CONNECT,
    Manifest.permission.POST_NOTIFICATIONS,
)

/**
 * Whatever the link needs from the user right now: the Bluetooth permission, the pairing code
 * to confirm, or a hint to open the watch app. Shows nothing once the watch is connected.
 * [onChanged] runs when the link's state changes, so the host can re-check the watch.
 */
@Composable
fun CompanionLinkControls(onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    val colors = HeartlineTheme.colors
    val link = rememberLink()
    var granted by remember { mutableStateOf(LinkService.hasPermissions(context)) }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        granted = LinkService.hasPermissions(context)
        LinkService.start(context)
    }
    val state = link?.state
    LaunchedEffect(state) { onChanged() }
    when {
        !granted -> {
            Hint(stringResource(R.string.cmp_permission_hint))
            VerticalGap(12)
            PillButton(stringResource(R.string.cmp_grant_bluetooth), onClick = { permissions.launch(bluetoothPermissions) })
        }
        state == LinkService.State.PAIRING -> {
            Hint(stringResource(R.string.cmp_pair_compare))
            Text(
                link?.pairCode.orEmpty(),
                style = MaterialTheme.typography.displaySmall,
                color = colors.onBackground,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )
            VerticalGap(12)
            PillButton(stringResource(R.string.cmp_pair_confirm), onClick = { link?.confirmPairing() })
            VerticalGap(8)
            TonalPillButton(stringResource(R.string.action_cancel), onClick = { link?.cancelPairing() })
        }
        state == LinkService.State.CONNECTED -> Unit
        state == LinkService.State.BT_OFF -> Hint(stringResource(R.string.cmp_state_bt_off))
        link?.paired != true -> Hint(stringResource(R.string.cmp_pair_hint))
        else -> Hint(stringResource(R.string.cmp_state_scanning))
    }
}

@Composable
private fun Hint(text: String) {
    VerticalGap(12)
    Text(text, style = MaterialTheme.typography.bodyMedium, color = HeartlineTheme.colors.onSurfaceVariant)
}

private fun notificationAccess(context: Context) =
    Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners").orEmpty().contains("${context.packageName}/")

/** Pairing, what gets forwarded to the watch, and the small tools around it. */
@Composable
fun CompanionScreen(onBack: (() -> Unit)? = null, onApps: () -> Unit = {}, listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    val colors = HeartlineTheme.colors
    val link = rememberLink()
    val state = link?.state ?: LinkService.State.NO_PERMISSION
    val connected = state == LinkService.State.CONNECTED
    var access by remember { mutableStateOf(notificationAccess(context)) }
    var muted by remember { mutableIntStateOf(CompanionPrefs.mutedApps(context).size) }
    LifecycleResumeEffect(Unit) {
        access = notificationAccess(context)
        muted = CompanionPrefs.mutedApps(context).size
        LinkService.start(context)
        onPauseOrDispose {}
    }
    var forget by remember { mutableStateOf(false) }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        val db = LinkService.instance?.health
        if (uri != null && db != null) {
            runCatching { context.contentResolver.openOutputStream(uri)!!.bufferedWriter().use { db.exportCsv(it) } }
        }
    }

    ReachabilityScaffold(title = stringResource(R.string.cmp_title), onBack = onBack, listState = listState) {
        item {
            RoundedCard(Modifier.gutter()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Watch, if (connected) colors.statusNormal else colors.onSurfaceVariant, size = 36)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (connected) LinkHub.peerName ?: stringResource(R.string.cmp_state_connected) else stringResource(LinkService.statusText(state)),
                            style = MaterialTheme.typography.titleMedium,
                            color = colors.onBackground,
                        )
                        if (connected && link != null && link.watchBattery >= 0) {
                            val charging = if (link.watchCharging) " · " + stringResource(R.string.cmp_charging) else ""
                            Text(
                                stringResource(R.string.cmp_watch_battery, link.watchBattery) + charging,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurfaceVariant,
                            )
                        }
                    }
                }
                CompanionLinkControls()
                if (connected && link != null) {
                    VerticalGap(14)
                    TonalPillButton(
                        stringResource(if (link.ringingWatch) R.string.cmp_stop_ring else R.string.cmp_ring_watch),
                        onClick = { link.ringWatch(!link.ringingWatch) },
                    )
                }
            }
        }

        if (!access) {
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    CardRow(
                        stringResource(R.string.cmp_notif_access_title),
                        subtitle = stringResource(R.string.cmp_notif_access_body),
                        leading = { IconBadge(Icons.Rounded.NotificationsActive, colors.statusWarn) },
                        onClick = { runCatching { context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } },
                        subtitleMaxLines = 3,
                    )
                }
            }
        }

        item { SectionHeader(stringResource(R.string.cmp_section_notifications)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                PrefRow(CompanionPref.NOTIFICATIONS, Icons.Rounded.Notifications, R.string.cmp_opt_notifications, R.string.cmp_opt_notifications_sub)
                PrefRow(CompanionPref.CALLS, Icons.Rounded.Call, R.string.cmp_opt_calls, R.string.cmp_opt_calls_sub)
                CardRow(
                    stringResource(R.string.cmp_opt_apps),
                    subtitle = if (muted == 0) stringResource(R.string.cmp_opt_apps_all) else stringResource(R.string.cmp_opt_apps_muted, muted),
                    leading = { IconBadge(Icons.Rounded.Apps, colors.primary) },
                    showDivider = true,
                    onClick = onApps,
                )
                PrefRow(CompanionPref.HIDE_CONTENT, Icons.Rounded.VisibilityOff, R.string.cmp_opt_hide, R.string.cmp_opt_hide_sub)
                PrefRow(CompanionPref.ONLY_WHEN_LOCKED, Icons.Rounded.Lock, R.string.cmp_opt_locked, R.string.cmp_opt_locked_sub)
                PrefRow(CompanionPref.RESPECT_DND, Icons.Rounded.DoNotDisturbOn, R.string.cmp_opt_dnd, R.string.cmp_opt_dnd_sub)
                PrefRow(CompanionPref.INCLUDE_SILENT, Icons.Rounded.NotificationsPaused, R.string.cmp_opt_silent, R.string.cmp_opt_silent_sub)
                PrefRow(CompanionPref.INCLUDE_ONGOING, Icons.Rounded.PushPin, R.string.cmp_opt_ongoing, R.string.cmp_opt_ongoing_sub, divider = false)
            }
        }

        item { SectionHeader(stringResource(R.string.cmp_section_tools)) }
        item {
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                PrefRow(CompanionPref.MEDIA, Icons.Rounded.MusicNote, R.string.cmp_opt_media, R.string.cmp_opt_media_sub) {
                    LinkService.instance?.media?.push(true)
                }
                PrefRow(CompanionPref.INTERNET, Icons.Rounded.Public, R.string.cmp_opt_internet, R.string.cmp_opt_internet_sub)
                PrefRow(CompanionPref.LOW_BATTERY_ALERT, Icons.Rounded.BatteryAlert, R.string.cmp_opt_low_battery, R.string.cmp_opt_low_battery_sub)
                PrefRow(CompanionPref.DISCONNECT_ALERT, Icons.Rounded.Vibration, R.string.cmp_opt_disconnect, R.string.cmp_opt_disconnect_sub, divider = false) {
                    LinkService.instance?.sendPrefs()
                }
            }
        }

        item { SectionHeader(stringResource(R.string.cmp_section_steps)) }
        item {
            val latest = remember(link?.healthVersion) { link?.health?.latest(1)?.firstOrNull() }
            RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                CardRow(
                    if (latest == null) {
                        stringResource(R.string.cmp_steps_none)
                    } else {
                        stringResource(R.string.cmp_steps_latest, latest.steps, SimpleDateFormat("d MMM HH:mm", Locale.getDefault()).format(Date(latest.ts)))
                    },
                    leading = { IconBadge(Icons.AutoMirrored.Rounded.DirectionsWalk, colors.body) },
                    showDivider = latest != null,
                )
                if (latest != null) {
                    CardRow(
                        stringResource(R.string.cmp_steps_export),
                        leading = { IconBadge(Icons.Rounded.Download, colors.onSurfaceVariant) },
                        onClick = { export.launch("w7link-steps.csv") },
                    )
                }
            }
        }

        if (link?.paired == true) {
            item {
                RoundedCard(Modifier.gutter(), contentPadding = 0.dp) {
                    CardRow(
                        stringResource(R.string.cmp_unpair),
                        leading = { IconBadge(Icons.Rounded.LinkOff, colors.statusAlert) },
                        onClick = { forget = true },
                    )
                }
            }
        }
    }

    if (forget) {
        AlertDialog(
            onDismissRequest = { forget = false },
            title = { Text(stringResource(R.string.cmp_unpair)) },
            text = { Text(stringResource(R.string.cmp_unpair_body)) },
            confirmButton = {
                TextButton(onClick = {
                    forget = false
                    LinkService.instance?.unpair()
                }) { Text(stringResource(R.string.cmp_unpair_confirm)) }
            },
            dismissButton = { TextButton(onClick = { forget = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PrefRow(pref: CompanionPref, icon: ImageVector, title: Int, subtitle: Int, divider: Boolean = true, onChanged: () -> Unit = {}) {
    val context = LocalContext.current
    var value by remember { mutableStateOf(pref.get(context)) }
    CardRow(
        stringResource(title),
        subtitle = stringResource(subtitle),
        leading = { IconBadge(icon, HeartlineTheme.colors.primary) },
        trailing = {
            OneUiSwitch(value) {
                value = it
                pref.set(context, it)
                onChanged()
            }
        },
        showDivider = divider,
        subtitleMaxLines = 3,
    )
}

/** Which apps may reach the watch: every app with a launcher icon, plus any other that has notified. */
@Composable
fun CompanionAppsScreen(onBack: (() -> Unit)? = null, listState: LazyListState = rememberLazyListState()) {
    val context = LocalContext.current
    var muted by remember { mutableStateOf(CompanionPrefs.mutedApps(context)) }
    val apps by produceState<List<Pair<String, String>>?>(null) {
        value = withContext(Dispatchers.IO) {
            val pm = context.packageManager
            val launchable = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
                .map { it.activityInfo.packageName }
            (launchable + CompanionPrefs.seenApps(context)).distinct().filter { it != context.packageName }.map { pkg ->
                val label = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)
                label to pkg
            }.sortedBy { it.first.lowercase() }
        }
    }
    ReachabilityScaffold(title = stringResource(R.string.cmp_opt_apps), onBack = onBack, listState = listState) {
        item {
            val list = apps
            RoundedCard(Modifier.gutter(), contentPadding = if (list == null) 20.dp else 0.dp) {
                if (list == null) {
                    Text(stringResource(R.string.cmp_apps_loading), style = MaterialTheme.typography.bodyMedium, color = HeartlineTheme.colors.onSurfaceVariant)
                }
                list?.forEachIndexed { i, (label, pkg) ->
                    CardRow(
                        label,
                        trailing = {
                            OneUiSwitch(pkg !in muted) { on ->
                                muted = if (on) muted - pkg else muted + pkg
                                CompanionPrefs.setMutedApps(context, muted)
                            }
                        },
                        showDivider = i < list.lastIndex,
                    )
                }
            }
        }
    }
}

/** Theme choices: wallpaper-based Material You colours or the classic palette, and light or dark. */
@Composable
fun AppearanceCard(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val colors = HeartlineTheme.colors
    var pickStyle by remember { mutableStateOf(false) }
    var pickMode by remember { mutableStateOf(false) }
    val styles = listOf(
        stringResource(R.string.cmp_theme_material) to ThemeStyle.MATERIAL_YOU,
        stringResource(R.string.cmp_theme_classic) to ThemeStyle.CLASSIC,
    )
    val modes = listOf(
        stringResource(R.string.cmp_dark_system) to ThemeMode.SYSTEM,
        stringResource(R.string.cmp_dark_off) to ThemeMode.LIGHT,
        stringResource(R.string.cmp_dark_on) to ThemeMode.DARK,
    )
    RoundedCard(modifier, contentPadding = 0.dp) {
        CardRow(
            stringResource(R.string.cmp_theme),
            subtitle = styles.first { it.second == ThemePrefs.style }.first,
            leading = { IconBadge(Icons.Rounded.Palette, colors.primary) },
            showDivider = true,
            onClick = { pickStyle = true },
        )
        CardRow(
            stringResource(R.string.cmp_dark),
            subtitle = modes.first { it.second == ThemePrefs.mode }.first,
            leading = { IconBadge(Icons.Rounded.DarkMode, colors.primary) },
            onClick = { pickMode = true },
        )
    }
    if (pickStyle) {
        ChoiceDialog(stringResource(R.string.cmp_theme), styles, ThemePrefs.style, onDismiss = { pickStyle = false }) { ThemePrefs.setStyle(context, it) }
    }
    if (pickMode) {
        ChoiceDialog(stringResource(R.string.cmp_dark), modes, ThemePrefs.mode, onDismiss = { pickMode = false }) { ThemePrefs.setMode(context, it) }
    }
}
