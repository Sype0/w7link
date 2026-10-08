// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.wear

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.RemoteInput
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.BitmapFactory
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaMetadata
import android.media.Ringtone
import android.media.RingtoneManager
import android.media.VolumeProvider
import android.media.session.MediaSession
import android.media.session.PlaybackState
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.ParcelUuid
import android.os.PowerManager
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Base64
import android.util.Log
import com.heartline.wear.MainActivity
import com.heartline.wear.R
import io.github.sype0.w7link.common.Keys
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.Proto
import io.github.sype0.w7link.common.SecureChannel
import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.security.KeyPair
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * The watch end of the link: advertises over BLE, accepts the phone's L2CAP
 * channel, shows what the phone sends, and counts steps. Heart data is Heartline's job.
 */
@SuppressLint("MissingPermission")
class LinkService : Service(), SensorEventListener {
    enum class State { NO_PERMISSION, BT_OFF, WAITING, PAIRING, CONNECTED }

    @Volatile
    var state = State.NO_PERMISSION
        private set

    /** Shown on both screens while pairing; the user checks that they match. */
    @Volatile
    var pairCode: String? = null
        private set

    @Volatile
    var phoneBattery = -1
        private set

    @Volatile
    var phoneCharging = false
        private set

    @Volatile
    var media: JSONObject? = null
        private set

    @Volatile
    var findingPhone = false
        private set

    val stepsToday: Int
        get() = if (prefs.getString(PREF_STEP_DAY, null) == today()) prefs.getInt(PREF_STEPS, 0) else 0

    private val main = Handler(Looper.getMainLooper())
    private val sender = Executors.newSingleThreadExecutor()
    private lateinit var prefs: SharedPreferences
    private lateinit var keys: KeyPair
    private lateinit var notifications: NotificationManager
    private lateinit var wakeLock: PowerManager.WakeLock

    @Volatile
    private var channel: SecureChannel? = null
    private var acceptThread: Thread? = null

    @Volatile
    private var server: BluetoothServerSocket? = null
    private var advertising = false
    private var localOk = false
    private var remoteOk = false
    private var registered = false

    private var sampling = false
    private val pending = ArrayList<JSONObject>()

    private var ringtone: Ringtone? = null

    /** The phone's playback as a session of this watch, which is what Samsung's Media Controller shows. */
    private var session: MediaSession? = null
    private var sessionVolume: VolumeProvider? = null

    private val sessionCallback = object : MediaSession.Callback() {
        override fun onPlay() {
            if (media?.optBoolean("playing") != true) mediaCommand("play_pause")
        }

        override fun onPause() {
            if (media?.optBoolean("playing") == true) mediaCommand("play_pause")
        }

        override fun onSkipToNext() {
            mediaCommand("next")
        }

        override fun onSkipToPrevious() {
            mediaCommand("prev")
        }
    }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertise failed: $errorCode")
            advertising = false
        }
    }

    private val finishSample = Runnable { completeSample() }
    private val stopRing = Runnable { ring(false) }

    /** The left-behind alert, held back a little so a brief dropout doesn't buzz. */
    private val lostAlert = Runnable {
        if (state == State.CONNECTED || !prefs.getBoolean(PREF_DISCONNECT_ALERT, false)) return@Runnable
        notifications.notify(
            NOTIFICATION_LOST,
            Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(getString(R.string.cmp_lost_title))
                .setContentText(getString(R.string.cmp_lost_text))
                .setAutoCancel(true)
                .build(),
        )
    }

    private val systemEvents = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    if (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) == BluetoothAdapter.STATE_ON) {
                        listen()
                    } else {
                        advertising = false
                        closeServer()
                    }
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
        notifications = getSystemService(NotificationManager::class.java)
        wakeLock = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "w7link:sample")
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_LINK, getString(R.string.cmp_channel_link), NotificationManager.IMPORTANCE_MIN)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_MIRROR, getString(R.string.cmp_channel_mirror), NotificationManager.IMPORTANCE_HIGH)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_CALL, getString(R.string.cmp_channel_call), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 800, 500, 800, 500, 800, 500, 800)
            }
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_FIND, getString(R.string.cmp_channel_find), NotificationManager.IMPORTANCE_HIGH)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, getString(R.string.cmp_channel_alerts), NotificationManager.IMPORTANCE_HIGH)
        )
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_MEDIA, getString(R.string.cmp_channel_media), NotificationManager.IMPORTANCE_LOW)
        )
        JSONArray(prefs.getString(PREF_PENDING, null) ?: "[]").let { saved ->
            for (i in 0 until saved.length()) pending.add(saved.getJSONObject(i))
        }
        LinkHub.streamRoutes[Proto.APK_STREAM] = { input -> thread(name = "w7link-apk") { installApk(input) } }
        instance = this
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!enterForeground()) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!registered) {
            registered = true
            registerReceiver(systemEvents, IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(Intent.ACTION_BATTERY_CHANGED)
            })
            scheduleSample()
        }
        when (intent?.action) {
            ACTION_SAMPLE -> {
                scheduleSample()
                sample()
            }
            ACTION_RING_STOP -> ring(false)
        }
        listen()
        return START_STICKY
    }

    override fun onDestroy() {
        instance = null
        LinkHub.streamRoutes.remove(Proto.APK_STREAM)
        if (registered) unregisterReceiver(systemEvents)
        main.removeCallbacksAndMessages(null)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        ring(false)
        notifications.cancel(NOTIFICATION_MEDIA)
        session?.release()
        session = null
        channel?.close()
        closeServer()
        stopAdvertising()
        sender.shutdown()
        super.onDestroy()
    }

    private fun granted(permission: String) = checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    private fun enterForeground(): Boolean {
        val notification = statusNotification()
        if (granted(Manifest.permission.ACTIVITY_RECOGNITION)) {
            try {
                startForeground(
                    NOTIFICATION_LINK, notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE or ServiceInfo.FOREGROUND_SERVICE_TYPE_HEALTH,
                )
                return true
            } catch (e: Exception) {
                Log.w(TAG, "health service type refused", e)
            }
        }
        return try {
            startForeground(NOTIFICATION_LINK, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
            true
        } catch (e: Exception) {
            // Without the Bluetooth permissions the system refuses this service type.
            Log.w(TAG, "cannot run in foreground", e)
            false
        }
    }

    // --- link ---

    private fun listen() {
        if (acceptThread?.isAlive == true) return
        val adapter = getSystemService(BluetoothManager::class.java).adapter
        if (adapter?.isEnabled != true) {
            setState(State.BT_OFF)
            return
        }
        acceptThread = thread(name = "w7link-io") { acceptLoop(adapter) }
    }

    private fun acceptLoop(adapter: BluetoothAdapter) {
        val listener = try {
            adapter.listenUsingInsecureL2capChannel()
        } catch (e: Exception) {
            Log.w(TAG, "listen", e)
            setState(State.NO_PERMISSION)
            return
        }
        server = listener
        val psm = listener.psm
        try {
            while (true) {
                main.post { startAdvertising(psm) }
                setState(State.WAITING)
                val socket = listener.accept()
                // One phone at a time; nothing else should find us while it is connected.
                main.post { stopAdvertising() }
                serve(socket)
            }
        } catch (e: IOException) {
            Log.i(TAG, "listener closed: $e")
        } finally {
            main.post { stopAdvertising() }
            closeServer()
        }
    }

    private fun closeServer() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun startAdvertising(psm: Int) {
        if (advertising) return
        val advertiser = getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeAdvertiser ?: return
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_MEDIUM)
            .setConnectable(true)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(Proto.SERVICE_UUID))
            .addManufacturerData(Proto.MANUFACTURER_ID, byteArrayOf((psm shr 8).toByte(), psm.toByte()))
            .build()
        try {
            advertiser.startAdvertising(settings, data, advertiseCallback)
            advertising = true
        } catch (e: Exception) {
            Log.w(TAG, "advertise", e)
        }
    }

    private fun stopAdvertising() {
        if (!advertising) return
        advertising = false
        try {
            getSystemService(BluetoothManager::class.java).adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        } catch (e: Exception) {
            Log.w(TAG, "stop advertising", e)
        }
    }

    private fun serve(socket: BluetoothSocket) {
        try {
            val link = SecureChannel(socket, socket.inputStream, socket.outputStream)
            link.handshake(keys, initiator = false)
            val peer = Base64.encodeToString(link.peerKey, Base64.NO_WRAP)
            val known = prefs.getString(PREF_PEER, null)
            if (known != null && known != peer) throw IOException("not our phone")
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
            if (state == State.CONNECTED) main.postDelayed(lostAlert, LOST_DELAY_MS)
            LinkHub.detach()
            NetService.onLink(this, false)
            channel = null
            pairCode = null
            findingPhone = false
            media = null
            try {
                socket.close()
            } catch (_: IOException) {
            }
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
        NetService.onLink(this, true)
        main.removeCallbacks(lostAlert)
        notifications.cancel(NOTIFICATION_LOST)
        main.post {
            lastBattery = ""
            registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { sendBattery(it) }
            synchronized(pending) { pending.forEach { send(it) } }
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
            "notif" -> showNotification(m)
            "notif_rm" -> notifications.cancel(m.getString("key"), NOTIFICATION_MIRROR)
            "media" -> {
                media = if (m.getBoolean("has")) m else null
                main.post { publishMedia() }
                notifyUi()
            }
            "battery" -> {
                phoneBattery = m.getInt("level")
                phoneCharging = m.getBoolean("charging")
                notifyUi()
            }
            "find" -> main.post { ring(m.getBoolean("on")) }
            "prefs" -> prefs.edit().putBoolean(PREF_DISCONNECT_ALERT, m.optBoolean("disconnectAlert")).apply()
            "health_ack" -> {
                val upTo = m.getLong("ts")
                synchronized(pending) {
                    pending.removeAll { it.getLong("ts") <= upTo }
                    savePending()
                }
            }
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
        notifyUi()
    }

    val paired: Boolean
        get() = prefs.getString(PREF_PEER, null) != null

    // --- mirrored notifications ---

    private fun showNotification(m: JSONObject) {
        val key = m.getString("key")
        val call = m.optBoolean("call")
        val text = m.optString("text")
        val builder = Notification.Builder(this, if (call) CHANNEL_CALL else CHANNEL_MIRROR)
            .setSmallIcon(R.drawable.ic_w7link)
            .setContentTitle(m.optString("title"))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setSubText(m.optString("app"))
            .setWhen(m.optLong("when", System.currentTimeMillis()))
            .setShowWhen(true)
            .setDeleteIntent(actionIntent(ActionReceiver.ACTION_DISMISS, key, 0, false))
        if (m.has("icon")) {
            val png = Base64.decode(m.getString("icon"), Base64.NO_WRAP)
            BitmapFactory.decodeByteArray(png, 0, png.size)?.let { builder.setLargeIcon(it) }
        }
        val actions = m.optJSONArray("actions") ?: JSONArray()
        for (i in 0 until actions.length()) {
            val action = actions.getJSONObject(i)
            val reply = action.optBoolean("reply")
            val title = action.optString("title")
            val item = Notification.Action.Builder(null, title, actionIntent(ActionReceiver.ACTION_PICK, key, i, reply))
            if (reply) item.addRemoteInput(RemoteInput.Builder(ActionReceiver.REPLY_KEY).setLabel(title).build())
            builder.addAction(item.build())
        }
        if (call) {
            builder.setCategory(Notification.CATEGORY_CALL).setOngoing(true).setOnlyAlertOnce(true)
            builder.addAction(
                Notification.Action.Builder(
                    null, getString(R.string.cmp_silence), actionIntent(ActionReceiver.ACTION_PICK, key, ACTION_SILENCE, false),
                ).build()
            )
        }
        notifications.notify(key, NOTIFICATION_MIRROR, builder.build())
    }

    private fun actionIntent(action: String, key: String, index: Int, mutable: Boolean): PendingIntent {
        val intent = Intent(this, ActionReceiver::class.java)
            .setAction(action)
            // Extras don't distinguish PendingIntents; the data URI does.
            .setData(Uri.Builder().scheme("w7link").authority(action).appendPath(key).appendPath(index.toString()).build())
            .putExtra(ActionReceiver.EXTRA_KEY, key)
            .putExtra(ActionReceiver.EXTRA_INDEX, index)
        // The system writes the typed reply into the intent, so that one has to be mutable.
        val mutability = if (mutable) PendingIntent.FLAG_MUTABLE else PendingIntent.FLAG_IMMUTABLE
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or mutability
        return PendingIntent.getBroadcast(this, 0, intent, flags)
    }

    // --- health ---

    private fun scheduleSample() {
        val intent = PendingIntent.getForegroundService(
            this, 0,
            Intent(this, LinkService::class.java).setAction(ACTION_SAMPLE),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        getSystemService(AlarmManager::class.java).setAndAllowWhileIdle(
            AlarmManager.ELAPSED_REALTIME_WAKEUP, SystemClock.elapsedRealtime() + SAMPLE_INTERVAL_MS, intent,
        )
    }

    /** Reads the step counter once, then turns the sensor back off. */
    fun sample() {
        if (sampling || !granted(Manifest.permission.ACTIVITY_RECOGNITION)) return
        val sensors = getSystemService(SensorManager::class.java)
        val steps = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return
        sampling = true
        wakeLock.acquire(SAMPLE_WINDOW_MS + 5_000)
        sensors.registerListener(this, steps, SensorManager.SENSOR_DELAY_NORMAL, main)
        main.postDelayed(finishSample, SAMPLE_WINDOW_MS)
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type == Sensor.TYPE_STEP_COUNTER) addSteps(event.values[0].toLong())
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    /** The sensor counts since boot; turn that into a per-day total. */
    private fun addSteps(sinceBoot: Long) {
        val last = prefs.getLong(PREF_STEP_COUNTER, -1)
        val delta = when {
            last < 0 -> 0L
            sinceBoot >= last -> sinceBoot - last
            else -> sinceBoot
        }
        prefs.edit()
            .putLong(PREF_STEP_COUNTER, sinceBoot)
            .putString(PREF_STEP_DAY, today())
            .putInt(PREF_STEPS, stepsToday + delta.toInt())
            .apply()
    }

    private fun completeSample() {
        if (!sampling) return
        main.removeCallbacks(finishSample)
        getSystemService(SensorManager::class.java).unregisterListener(this)
        sampling = false
        if (wakeLock.isHeld) wakeLock.release()
        val record = Proto.msg("health", "ts" to System.currentTimeMillis(), "hr" to 0, "steps" to stepsToday)
        synchronized(pending) {
            pending.add(record)
            // Kept until the phone confirms it; about three weeks' worth if it never does.
            while (pending.size > MAX_PENDING) pending.removeAt(0)
            savePending()
        }
        send(record)
        notifyUi()
    }

    private fun savePending() {
        prefs.edit().putString(PREF_PENDING, JSONArray(pending).toString()).apply()
    }

    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    // --- small tools ---

    fun findPhone(on: Boolean) {
        findingPhone = on
        send(Proto.msg("find_phone", "on" to on))
        notifyUi()
    }

    fun mediaCommand(command: String) = send(Proto.msg("media_cmd", "cmd" to command))

    /** Mirrors [media] into the session, or drops the session when the phone has nothing or is gone. Main thread. */
    private fun publishMedia() {
        val now = media.takeIf { state == State.CONNECTED }
        if (now == null) {
            notifications.cancel(NOTIFICATION_MEDIA)
            session?.release()
            session = null
            sessionVolume = null
            return
        }
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val current = session ?: MediaSession(this, TAG).also {
            it.setCallback(sessionCallback, main)
            it.setSessionActivity(open)
            session = it
        }
        val max = now.optInt("volMax")
        val volume = sessionVolume?.takeIf { it.maxVolume == max }
            ?: object : VolumeProvider(VolumeProvider.VOLUME_CONTROL_RELATIVE, max, now.optInt("vol")) {
                override fun onAdjustVolume(direction: Int) {
                    if (direction > 0) mediaCommand("vol_up") else if (direction < 0) mediaCommand("vol_down")
                }
            }.also {
                sessionVolume = it
                current.setPlaybackToRemote(it)
            }
        volume.currentVolume = now.optInt("vol")
        current.setMetadata(
            MediaMetadata.Builder()
                .putString(MediaMetadata.METADATA_KEY_TITLE, now.optString("title"))
                .putString(MediaMetadata.METADATA_KEY_ARTIST, now.optString("artist"))
                .build()
        )
        val playing = now.optBoolean("playing")
        current.setPlaybackState(
            PlaybackState.Builder()
                .setActions(
                    PlaybackState.ACTION_PLAY or PlaybackState.ACTION_PAUSE or PlaybackState.ACTION_PLAY_PAUSE or
                        PlaybackState.ACTION_SKIP_TO_NEXT or PlaybackState.ACTION_SKIP_TO_PREVIOUS
                )
                .setState(
                    if (playing) PlaybackState.STATE_PLAYING else PlaybackState.STATE_PAUSED,
                    PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                    if (playing) 1f else 0f,
                )
                .build()
        )
        current.isActive = true
        // The system's own media surfaces go by this notification; its buttons come from the session.
        notifications.notify(
            NOTIFICATION_MEDIA,
            Notification.Builder(this, CHANNEL_MEDIA)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(now.optString("title"))
                .setContentText(now.optString("artist"))
                .setContentIntent(open)
                .setStyle(Notification.MediaStyle().setMediaSession(current.sessionToken))
                .setVisibility(Notification.VISIBILITY_PUBLIC)
                .setOngoing(playing)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    // --- apps from the phone ---

    /** Feeds an APK the phone streams into an install session; [InstallReceiver] hears how it went. */
    private fun installApk(input: InputStream) {
        val installer = packageManager.packageInstaller
        var id = -1
        try {
            input.use { raw ->
                val data = DataInputStream(raw)
                val size = data.readLong()
                id = installer.createSession(PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL))
                installer.openSession(id).use { session ->
                    session.openWrite("app.apk", 0, size).use { out ->
                        data.copyTo(out)
                        session.fsync(out)
                    }
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                    session.commit(PendingIntent.getBroadcast(this, id, Intent(this, InstallReceiver::class.java), flags).intentSender)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "apk install", e)
            if (id >= 0) runCatching { installer.abandonSession(id) }
            installResult(false, e.message.orEmpty())
        }
    }

    /** The system's install question, kept as a notification for when it can't open by itself. */
    fun askInstall(confirm: Intent) {
        notifications.notify(
            NOTIFICATION_INSTALL,
            Notification.Builder(this, CHANNEL_ALERTS)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(getString(R.string.cmp_install_title))
                .setContentText(getString(R.string.cmp_install_text))
                .setContentIntent(PendingIntent.getActivity(this, 0, confirm, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
                .setAutoCancel(true)
                .build(),
        )
    }

    fun installResult(ok: Boolean, message: String) {
        notifications.cancel(NOTIFICATION_INSTALL)
        send(Proto.msg("apk_result", "ok" to ok, "message" to message))
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

    private fun ring(on: Boolean) {
        main.removeCallbacks(stopRing)
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator.cancel()
        ringtone?.stop()
        ringtone = null
        notifications.cancel(NOTIFICATION_FIND)
        if (!on) return
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 600, 400), 0))
        try {
            ringtone = RingtoneManager.getRingtone(this, RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM))?.apply {
                audioAttributes = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ALARM).build()
                isLooping = true
                play()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ring", e)
        }
        val stop = PendingIntent.getService(
            this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_RING_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        notifications.notify(
            NOTIFICATION_FIND,
            Notification.Builder(this, CHANNEL_FIND)
                .setSmallIcon(R.drawable.ic_w7link)
                .setContentTitle(getString(R.string.cmp_ring_title))
                .setContentText(getString(R.string.cmp_ring_text))
                .setContentIntent(stop)
                .setAutoCancel(true)
                .build(),
        )
        main.postDelayed(stopRing, RING_TIMEOUT_MS)
    }

    // --- status ---

    private fun setState(next: State) {
        state = next
        main.post {
            if (instance === this) {
                notifications.notify(NOTIFICATION_LINK, statusNotification())
                publishMedia()
            }
        }
        notifyUi()
    }

    private fun notifyUi() {
        main.post { uiListeners.forEach { it() } }
    }

    private fun statusNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
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
        private const val CHANNEL_LINK = "link"
        private const val CHANNEL_MIRROR = "mirror"
        private const val CHANNEL_CALL = "call"
        private const val CHANNEL_FIND = "find"
        private const val CHANNEL_ALERTS = "alerts"
        private const val NOTIFICATION_LOST = 4
        private const val NOTIFICATION_INSTALL = 5
        private const val NOTIFICATION_MEDIA = 6
        private const val CHANNEL_MEDIA = "media"
        private const val PREF_DISCONNECT_ALERT = "disconnectAlert"
        private const val LOST_DELAY_MS = 20_000L
        private const val NOTIFICATION_LINK = 1
        private const val NOTIFICATION_FIND = 2
        const val NOTIFICATION_MIRROR = 3
        private const val PREF_PEER = "peer"
        private const val PREF_PENDING = "pending"
        private const val PREF_STEP_COUNTER = "stepCounter"
        private const val PREF_STEP_DAY = "stepDay"
        private const val PREF_STEPS = "steps"
        private const val SAMPLE_INTERVAL_MS = 10 * 60_000L
        private const val SAMPLE_WINDOW_MS = 3_000L
        private const val MAX_PENDING = 3_000
        private const val RING_TIMEOUT_MS = 30_000L

        /** Must match the phone's NotifListener.ACTION_SILENCE. */
        private const val ACTION_SILENCE = -2
        const val ACTION_SAMPLE = "io.github.sype0.w7link.wear.SAMPLE"
        const val ACTION_RING_STOP = "io.github.sype0.w7link.wear.RING_STOP"

        @Volatile
        var instance: LinkService? = null

        /** Invoked on the main thread whenever something the companion screens show has changed. */
        val uiListeners = CopyOnWriteArraySet<() -> Unit>()

        fun hasPermissions(context: Context) =
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_ADVERTISE) == PackageManager.PERMISSION_GRANTED &&
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
            State.WAITING -> R.string.cmp_state_waiting
            State.PAIRING -> R.string.cmp_state_pairing
            State.CONNECTED -> R.string.cmp_state_connected
        }
    }
}
