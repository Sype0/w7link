package io.github.sype0.w7link.wear

import android.app.NotificationManager
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.github.sype0.w7link.common.Proto

/** Taps on a mirrored notification: dismissing it, or picking one of its actions. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val key = intent.getStringExtra(EXTRA_KEY) ?: return
        val link = LinkService.instance
        if (intent.action == ACTION_DISMISS) {
            link?.send(Proto.msg("dismiss", "key" to key))
            return
        }
        val reply = RemoteInput.getResultsFromIntent(intent)?.getCharSequence(REPLY_KEY)?.toString()
        link?.send(Proto.msg("action", "key" to key, "idx" to intent.getIntExtra(EXTRA_INDEX, -1), "text" to reply))
        // A sent reply would otherwise leave the notification showing a spinner.
        if (reply != null) context.getSystemService(NotificationManager::class.java).cancel(key, LinkService.NOTIFICATION_MIRROR)
    }

    companion object {
        const val ACTION_DISMISS = "io.github.sype0.w7link.wear.DISMISS"
        const val ACTION_PICK = "io.github.sype0.w7link.wear.PICK"
        const val EXTRA_KEY = "key"
        const val EXTRA_INDEX = "idx"
        const val REPLY_KEY = "reply"
    }
}
