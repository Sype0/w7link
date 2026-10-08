// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.wear

import android.content.Context
import android.content.Intent
import android.net.ProxyInfo
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import com.heartline.wear.R
import io.github.sype0.w7link.common.LinkHub
import io.github.sype0.w7link.common.LocalProxy
import java.io.FileInputStream
import kotlin.concurrent.thread

/**
 * The phone's internet for the whole watch. A VPN is the one way an app can give the system a
 * network; this one carries no packets, it only names the proxy whose connections go over the link.
 * So apps that honour the system proxy get online (HTTP and HTTPS); anything else finds no route.
 */
class NetService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxy: LocalProxy? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stop()
            return START_NOT_STICKY
        }
        if (tun == null) start()
        return START_STICKY
    }

    private fun start() {
        val local = LocalProxy(LinkHub.net)
        val established = try {
            Builder()
                .setSession(getString(R.string.cmp_internet_on))
                .addAddress("10.111.0.2", 32)
                .addRoute("0.0.0.0", 0)
                .setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", local.port))
                .setBlocking(true)
                .establish()
        } catch (e: Exception) {
            Log.w(TAG, "vpn", e)
            null
        }
        if (established == null) {
            local.close()
            stopSelf()
            return
        }
        proxy = local
        tun = established
        // Whatever isn't proxied ends up here and goes nowhere.
        thread(name = "w7link-tun", isDaemon = true) {
            runCatching {
                val packets = FileInputStream(established.fileDescriptor)
                val packet = ByteArray(32 * 1024)
                while (packets.read(packet) >= 0) Unit
            }
        }
        setRunning(true)
    }

    private fun stop() {
        proxy?.close()
        proxy = null
        runCatching { tun?.close() }
        tun = null
        setRunning(false)
        stopSelf()
    }

    override fun onRevoke() {
        stop()
        super.onRevoke()
    }

    override fun onDestroy() {
        stop()
        super.onDestroy()
    }

    private fun setRunning(on: Boolean) {
        running = on
        LinkService.uiListeners.forEach { it() }
    }

    companion object {
        private const val TAG = "W7Link"
        private const val ACTION_STOP = "io.github.sype0.w7link.wear.NET_STOP"

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            context.startService(Intent(context, NetService::class.java))
        }

        fun stop(context: Context) {
            context.startService(Intent(context, NetService::class.java).setAction(ACTION_STOP))
        }
    }
}
