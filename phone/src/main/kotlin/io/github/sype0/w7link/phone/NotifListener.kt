package io.github.sype0.w7link.phone

import android.app.ActivityOptions
import android.app.Notification
import android.app.NotificationManager
import android.app.RemoteInput
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Base64
import android.util.Log
import io.github.sype0.w7link.common.Proto
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

class NotifListener : NotificationListenerService() {
    /** Notification key to the content last forwarded, so identical re-posts don't buzz the watch again. */
    private val sent = HashMap<String, String>()
    private val icons = HashMap<String, String?>()
    private var silencedCall: String? = null

    override fun onListenerConnected() {
        instance = this
        LinkService.instance?.media?.start()
    }

    override fun onListenerDisconnected() {
        instance = null
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        val link = LinkService.instance ?: return
        if (link.state != LinkService.State.CONNECTED) return
        val n = sbn.notification
        val pkg = sbn.packageName
        if (pkg == packageName) return
        if (n.flags and (Notification.FLAG_GROUP_SUMMARY or Notification.FLAG_LOCAL_ONLY) != 0) return
        val call = n.category == Notification.CATEGORY_CALL
        if (sbn.isOngoing && !call) return
        link.noteSeen(pkg)
        if (link.isMuted(pkg)) return
        val ranking = NotificationListenerService.Ranking()
        if (currentRanking.getRanking(sbn.key, ranking)) {
            // Silent notifications and anything Do Not Disturb is hiding stay on the phone.
            if (ranking.importance < NotificationManager.IMPORTANCE_DEFAULT) return
            if (!ranking.matchesInterruptionFilter()) return
        }

        val title = n.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (n.extras.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: n.extras.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty().take(MAX_TEXT)
        if (title.isEmpty() && text.isEmpty()) return
        val signature = "$title\n$text"
        if (sent[sbn.key] == signature) return
        sent[sbn.key] = signature

        val actions = JSONArray()
        n.actions?.take(MAX_ACTIONS)?.forEach {
            actions.put(
                JSONObject()
                    .put("title", it.title?.toString().orEmpty())
                    .put("reply", it.remoteInputs?.isNotEmpty() == true)
            )
        }
        link.send(
            Proto.msg(
                "notif",
                "key" to sbn.key,
                "app" to appLabel(pkg),
                "title" to title,
                "text" to text,
                "when" to sbn.postTime,
                "call" to call,
                "icon" to icons.getOrPut(pkg) { appIcon(pkg) },
                "actions" to actions,
            )
        )
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.key == silencedCall) {
            silencedCall = null
            ringer(AudioManager.ADJUST_UNMUTE)
        }
        if (sent.remove(sbn.key) != null) LinkService.instance?.send(Proto.msg("notif_rm", "key" to sbn.key))
    }

    fun dismiss(key: String) {
        try {
            cancelNotification(key)
        } catch (e: SecurityException) {
            Log.w(TAG, "dismiss", e)
        }
    }

    /** Runs the action the user picked on the watch; [index] counts into the original notification's actions. */
    fun act(key: String, index: Int, reply: String?) {
        val sbn = try {
            getActiveNotifications(arrayOf(key))?.firstOrNull()
        } catch (e: SecurityException) {
            null
        }
        if (sbn == null) return
        if (index == ACTION_SILENCE) {
            silencedCall = key
            ringer(AudioManager.ADJUST_MUTE)
            return
        }
        val action = sbn.notification.actions?.getOrNull(index) ?: return
        val fill = Intent()
        val inputs = action.remoteInputs
        if (!inputs.isNullOrEmpty() && reply != null) {
            val results = Bundle()
            for (input in inputs) results.putCharSequence(input.resultKey, reply)
            RemoteInput.addResultsToIntent(inputs, fill, results)
        }
        try {
            // Answering a call opens the dialer; from Android 14 the sender has to opt in to that.
            val options = if (Build.VERSION.SDK_INT >= 34) {
                ActivityOptions.makeBasic()
                    .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
                    .toBundle()
            } else {
                null
            }
            action.actionIntent.send(this, 0, fill, null, null, null, options)
        } catch (e: Exception) {
            Log.w(TAG, "action failed", e)
        }
    }

    private fun ringer(direction: Int) {
        try {
            getSystemService(AudioManager::class.java).adjustStreamVolume(AudioManager.STREAM_RING, direction, 0)
        } catch (e: SecurityException) {
            Log.w(TAG, "ringer", e)
        }
    }

    private fun appLabel(pkg: String): String =
        try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (e: Exception) {
            pkg
        }

    private fun appIcon(pkg: String): String? =
        try {
            val drawable = packageManager.getApplicationIcon(pkg)
            val bitmap = Bitmap.createBitmap(ICON_PX, ICON_PX, Bitmap.Config.ARGB_8888)
            drawable.setBounds(0, 0, ICON_PX, ICON_PX)
            drawable.draw(Canvas(bitmap))
            val png = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, png)
            Base64.encodeToString(png.toByteArray(), Base64.NO_WRAP)
        } catch (e: Exception) {
            null
        }

    companion object {
        private const val TAG = "W7Link"
        private const val MAX_TEXT = 600
        private const val MAX_ACTIONS = 3
        private const val ICON_PX = 48

        /** Watch-side "silence the ringer" action, which the original notification doesn't have. */
        const val ACTION_SILENCE = -2

        @Volatile
        var instance: NotifListener? = null
    }
}
