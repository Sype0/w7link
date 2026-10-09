// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.util.Base64
import android.util.Log
import com.heartline.datalayer.DeepLinks
import com.heartline.phone.MainActivity
import com.heartline.phone.R
import io.github.sype0.w7link.common.Keys
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.Proto
import io.github.sype0.w7link.common.SecureChannel
import org.json.JSONObject
import java.io.IOException
import java.net.InetAddress
import java.security.KeyPair
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Owns the link to the watch: scans for its advertisement, opens the L2CAP
 * channel, and routes messages between it and the rest of the app.
 */
@SuppressLint("MissingPermission")
class LinkService : Service() {
    enum class State { NO_PERMISSION, BT_OFF, SCANNING, CONNECTING, PAIRING, CONNECTED }

    @Volatile
    var state = State.NO_PERMISSION
        private set

    /** Shown on both screens while pairing; the user checks that they match. */
    @Volatile
    var pairCode: String? = null
        private set

    @Volatile
    var watchBattery = -1
        private set

    @Volatile
    var watchCharging = false
        private set

    @Volatile
    var ringingWatch = false
        private set

    /** Bumped with every stored step count, so the screen knows to read the newest one. */
    @Volatile
    var healthVersion = 0
        private set
    private var lowBatteryShown = false

    lateinit var media: MediaBridge
        private set
    lateinit var health: HealthDb
        private set

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var keys: KeyPair
    private lateinit var notifications: NotificationManager

    @Volatile
    private var channel: SecureChannel? = null
    private var scanning = false
    private var connecting = false
    private var localOk = false
    private var remoteOk = false
    private var finder: MediaPlayer? = null
    private var savedAlarmVolume = -1

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val data = result.scanRecord?.getManufacturerSpecificData(Proto.MANUFACTURER_ID) ?: return
            if (data.size < 2 || connecting || channel != null) return
            val psm = ((data[0].toInt() and 0xff) shl 8) or (data[1].toInt() and 0xff)
            stopScan()
            connecting = true
            setState(State.CONNECTING)
            thread(name = "w7link-io") { runLink(result.device, psm) }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.w(TAG, "scan failed: $errorCode")
            scanning = false
            main.postDelayed(restartScan, RETRY_MS)
        }
    }

    private val restartScan = Runnable {
        stopScan()
        startScan()
    }

    private val stopFinder = Runnable { findPhone(false) }

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    scanning = false
                    startScan()
                }
                Intent.ACTION_BATTERY_CHANGED -> sendBattery(intent)
            }
        }
    }
    private var lastBattery = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = getSharedPreferences("link", MODE_PRIVATE)
        keys = Keys.load(getSharedPreferences("keys", MODE_PRIVATE))
        health = HealthDb(this)
        notifications = getSystemService(NotificationManager::class.java)
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, getString(R.string.cmp_channel_link), NotificationManager.IMPORTANCE_LOW)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_FIND, getString(R.string.cmp_channel_find), NotificationManager.IMPORTANCE_HIGH)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.cmp_channel_alerts), NotificationManager.IMPORTANCE_DEFAULT)
        )
        media = MediaBridge(this, main) { send(it) }
        // The watch's connections go out from here, but never back into this phone itself.
        LinkHub.net.exit = { CompanionPref.INTERNET.get(this) && !it.isLoopbackAddress && !it.isAnyLocalAddress }
        // The network's own servers first; the public ones only answer when none of them does.
        LinkHub.net.resolver = {
            if (!CompanionPref.INTERNET.get(this)) {
                emptyList()
            } else {
                val network = getSystemService(ConnectivityManager::class.java)
                network.getLinkProperties(network.activeNetwork)?.dnsServers.orEmpty() + PUBLIC_DNS.map(InetAddress::getByName)
            }
        }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            startForeground(NOTIFICATION_LINK, statusNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } catch (e: Exception) {
            // Without the Bluetooth permissions the system refuses this service type.
            Log.w(TAG, "cannot run in foreground", e)
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_FIND_STOP) findPhone(false)
        if (!registered) {
            registered = true
            registerReceiver(systemEvents, IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            })
            media.start()
        }
        startScan()
        if (sysProxy == null && CompanionPref.INTERNET.get(this)) {
            getSystemService(BluetoothManager::class.java).adapter?.takeIf { it.isEnabled }?.let { sysProxy = SysProxy(it).apply { start() } }
        }
        return START_STICKY
    }

    private var registered = false
    private var sysProxy: SysProxy? = null

    override fun onDestroy() {
        instance = null
        LinkHub.net.exit = null
        LinkHub.net.resolver = null
        if (registered) unregisterReceiver(systemEvents)
        main.removeCallbacksAndMessages(null)
        stopScan()
        sysProxy?.stop()
        media.stop()
        findPhone(false)
        channel?.close()
        sender.shutdown()
        health.close()
        super.onDestroy()
    }

    // --- link ---

    private fun startScan() {
        if (scanning || connecting || channel != null) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        val scanner = if (adapter?.isEnabled == true) adapter.bluetoothLeScanner else null
        if (scanner == null) {
            setState(State.BT_OFF)
            return
        }
        val filter = ScanFilter.Builder().setServiceUuid(ParcelUuid(Proto.SERVICE_UUID)).build()
        // Fast while the user is waiting to pair, cheap for the everyday reconnect.
        val mode = if (prefs.getString(PREF_PEER, null) == null) {
            ScanSettings.SCAN_MODE_LOW_LATENCY
        } else {
            ScanSettings.SCAN_MODE_LOW_POWER
        }
        try {
            scanner.startScan(listOf(filter), ScanSettings.Builder().setScanMode(mode).build(), scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "scan", e)
            setState(State.NO_PERMISSION)
            return
        }
        scanning = true
        setState(State.SCANNING)
        // Android quietly downgrades scans that run longer than 30 minutes.
        main.removeCallbacks(restartScan)
        main.postDelayed(restartScan, RESCAN_MS)
    }

    private fun stopScan() {
        main.removeCallbacks(restartScan)
        if (!scanning) return
        scanning = false
        try {
            getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stop scan", e)
        }
    }

    private fun runLink(device: BluetoothDevice, psm: Int) {
        var socket: BluetoothSocket? = null
        try {
            socket = device.createInsecureL2capChannel(psm)
            socket.connect()
            val link = SecureChannel(socket, socket.inputStream, socket.outputStream)
            link.handshake(keys, initiator = true)
            val peer = Base64.encodeToString(link.peerKey, Base64.NO_WRAP)
            val known = prefs.getString(PREF_PEER, null)
            if (known != null && known != peer) throw IOException("not our watch")
            channel = link
            if (known == null) {
                localOk = false
                remoteOk = false
                pairCode = link.code
                setState(State.PAIRING)
            } else {
                attachHub(link)
                setState(State.CONNECTED)
                onConnected()
            }
            while (true) {
                val (kind, body) = link.receiveFrame()
                try {
                    if (kind == Proto.KIND_JSON) {
                        handle(JSONObject(String(body)))
                    } else if (state == State.CONNECTED) {
                        // Heartline's sync traffic; only a paired peer gets this far.
                        LinkHub.onFrame(kind, body)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "bad message", e)
                }
            }
        } catch (e: Exception) {
            Log.i(TAG, "link closed: $e")
        } finally {
            LinkHub.detach()
            channel = null
            pairCode = null
            try {
                socket?.close()
            } catch (_: IOException) {
            }
            setState(State.SCANNING)
            main.postDelayed({
                connecting = false
                ringingWatch = false
                startScan()
            }, RETRY_MS)
        }
    }

    /** Before the state says connected: whoever reacts to that must find the sync transport ready. */
    private fun attachHub(link: SecureChannel) {
        LinkHub.attach { kind, body ->
            try {
                link.sendFrame(kind, body)
                true
            } catch (e: Exception) {
                link.close()
                false
            }
        }
    }

    private fun onConnected() {
        send(Proto.msg("hello", "name" to Build.MODEL))
        main.post {
            lastBattery = ""
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sendBattery(it) }
            media.push(true)
            sendPrefs()
        }
    }

    fun send(message: JSONObject) = send(message, false)

    private fun send(message: JSONObject, whilePairing: Boolean) {
        val link = channel ?: return
        if (state != State.CONNECTED && !whilePairing) return
        sender.execute {
            try {
                link.send(message)
            } catch (e: Exception) {
                link.close()
            }
        }
    }

    private fun handle(m: JSONObject) {
        val type = m.getString("t")
        if (type == "pair_ok") {
            remoteOk = true
            finishPairing()
            return
        }
        if (state != State.CONNECTED || LinkHub.onJson(m)) return
        when (type) {
            "dismiss" -> NotifListener.instance?.dismiss(m.getString("key"))
            "action" -> NotifListener.instance?.act(
                m.getString("key"),
                m.getInt("idx"),
                if (m.has("text")) m.getString("text") else null,
            )
            "media_cmd" -> if (CompanionPref.MEDIA.get(this)) media.command(m.getString("cmd"), m.optLong("pos"))
            "battery" -> {
                watchBattery = m.getInt("level")
                watchCharging = m.getBoolean("charging")
                lowBatteryAlert()
                notifyUi()
            }
            "health" -> {
                val ts = m.getLong("ts")
                health.insert(ts, m.getInt("hr"), m.getInt("steps"))
                healthVersion++
                send(Proto.msg("health_ack", "ts" to ts))
                notifyUi()
            }
            "find_phone" -> main.post { findPhone(m.getBoolean("on")) }
            "apk_result" -> ApkInstall.onResult(m.optBoolean("ok"), m.optString("message"))
        }
    }

    // --- pairing ---

    fun confirmPairing() {
        if (state != State.PAIRING) return
        localOk = true
        send(Proto.msg("pair_ok"), true)
        finishPairing()
    }

    @Synchronized
    private fun finishPairing() {
        val link = channel ?: return
        if (state != State.PAIRING || !localOk || !remoteOk) return
        prefs.edit().putString(PREF_PEER, Base64.encodeToString(link.peerKey, Base64.NO_WRAP)).apply()
        pairCode = null
        attachHub(link)
        setState(State.CONNECTED)
        onConnected()
    }

    fun cancelPairing() {
        if (state == State.PAIRING) channel?.close()
    }

    fun unpair() {
        prefs.edit().remove(PREF_PEER).apply()
        channel?.close()
        main.post(restartScan)
    }

    val paired: Boolean
        get() = prefs.getString(PREF_PEER, null) != null

    // --- small tools ---

    /** Options the watch applies itself. Sent on connect and whenever one changes. */
    fun sendPrefs() {
        send(Proto.msg("prefs", "disconnectAlert" to CompanionPref.DISCONNECT_ALERT.get(this)))
    }

    /** One notification per discharge once the watch is nearly empty. */
    private fun lowBatteryAlert() {
        val low = watchBattery in 0..LOW_BATTERY_PERCENT && !watchCharging
        if (!low) {
            if (watchCharging || watchBattery > LOW_BATTERY_PERCENT + 5) {
                lowBatteryShown = false
                notifications.cancel(NOTIFICATION_LOW_BATTERY)
            }
            return
        }
        if (lowBatteryShown || !CompanionPref.LOW_BATTERY_ALERT.get(this)) return
        lowBatteryShown = true
        notifications.notify(
            NOTIFICATION_LOW_BATTERY,
            Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(getString(R.string.cmp_low_battery_title, watchBattery))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun ringWatch(on: Boolean) {
        ringingWatch = on
        send(Proto.msg("find", "on" to on))
        notifyUi()
    }

    private fun sendBattery(intent: Intent) {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        if (level < 0 || scale <= 0) return
        val charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val percent = level * 100 / scale
        val summary = "$percent $charging"
        if (summary == lastBattery || state != State.CONNECTED) return
        lastBattery = summary
        send(Proto.msg("battery", "level" to percent, "charging" to charging))
    }

    private fun findPhone(on: Boolean) {
        main.removeCallbacks(stopFinder)
        val audio = getSystemService(AudioManager::class.java)
        finder?.let {
            it.release()
            finder = null
            if (savedAlarmVolume >= 0) audio.setStreamVolume(AudioManager.STREAM_ALARM, savedAlarmVolume, 0)
            savedAlarmVolume = -1
        }
        notifications.cancel(NOTIFICATION_FIND)
        if (!on) return
        try {
            savedAlarmVolume = audio.getStreamVolume(AudioManager.STREAM_ALARM)
            audio.setStreamVolume(AudioManager.STREAM_ALARM, audio.getStreamMaxVolume(AudioManager.STREAM_ALARM), 0)
            finder = MediaPlayer().apply {
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
                setDataSource(
                    this@LinkService,
                    RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                        ?: RingtoneManager.getDefaultUri(RingtoneManager.TYPE_RINGTONE),
                )
                isLooping = true
                prepare()
                start()
            }
        } catch (e: Exception) {
            Log.w(TAG, "find phone", e)
        }
        val stop = PendingIntent.getService(
            this, 0,
            Intent(this, LinkService::class.java).setAction(ACTION_FIND_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            NOTIFICATION_FIND,
            Notification.Builder(this, CHANNEL_FIND)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(getString(R.string.cmp_find_title))
                .setContentText(getString(R.string.cmp_find_text))
                .setContentIntent(stop)
                .setAutoCancel(true)
                .build(),
        )
        main.postDelayed(stopFinder, FIND_TIMEOUT_MS)
    }

    // --- status ---

    private fun setState(next: State) {
        state = next
        main.post {
            if (instance === this) notifications.notify(NOTIFICATION_LINK, statusNotification())
        }
        notifyUi()
    }

    private fun notifyUi() {
        main.post { uiListeners.forEach { it() } }
    }

    private fun statusNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(Intent.ACTION_VIEW, Uri.parse(DeepLinks.phone(COMPANION_ROUTE)), this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_LINK)
            .setSmallIcon(R.drawable.ic_w7link)
            .setContentTitle(getString(statusText(state)))
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val TAG = "W7Link"
        private val PUBLIC_DNS = listOf("1.1.1.1", "8.8.8.8")
        private const val CHANNEL_LINK = "link"
        private const val CHANNEL_FIND = "find"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_LOW_BATTERY = 3
        private const val LOW_BATTERY_PERCENT = 15
        private const val NOTIFICATION_LINK = 1
        private const val NOTIFICATION_FIND = 2
        private const val PREF_PEER = "peer"
        private const val RETRY_MS = 4_000L
        private const val RESCAN_MS = 20 * 60_000L
        private const val FIND_TIMEOUT_MS = 60_000L
        const val ACTION_FIND_STOP = "io.github.sype0.w7link.phone.FIND_STOP"

        /** The companion screen's route in the app, also used as its deep link. */
        const val COMPANION_ROUTE = "companion"

        @Volatile
        var instance: LinkService? = null

        /** Invoked on the main thread whenever something the companion screens show has changed. */
        val uiListeners = CopyOnWriteArraySet<() -> Unit>()

        fun hasPermissions(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
                context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

        fun start(context: Context) {
            if (!hasPermissions(context)) return
            try {
                context.startForegroundService(Intent(context, LinkService::class.java))
            } catch (e: Exception) {
                // Not allowed while the app is in the background; the next app open or boot starts it.
                Log.w(TAG, "link service not started", e)
            }
        }

        fun statusText(state: State) = when (state) {
            State.NO_PERMISSION -> R.string.cmp_state_no_permission
            State.BT_OFF -> R.string.cmp_state_bt_off
            State.SCANNING -> R.string.cmp_state_scanning
            State.CONNECTING -> R.string.cmp_state_connecting
            State.PAIRING -> R.string.cmp_state_pairing
            State.CONNECTED -> R.string.cmp_state_connected
        }
    }
}
