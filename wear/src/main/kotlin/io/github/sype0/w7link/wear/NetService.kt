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
import io.github.sype0.w7link.common.TunNat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

/**
 * The phone's internet for the whole watch, up while the phone is connected. A VPN is the one way
 * an app can give the system a network. [TunNat] hands its TCP connections to a relay socket here
 * and its DNS queries to the phone, and each connection then travels over the link; apps that
 * honour the system proxy use the local HTTP proxy instead. Other UDP, ICMP and IPv6 go nowhere.
 */
class NetService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var proxy: LocalProxy? = null
    private var relay: ServerSocket? = null

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
                .addAddress(ADDRESS, 32)
                .addRoute("0.0.0.0", 0)
                .addDnsServer(DNS)
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
        val address = InetAddress.getByName(ADDRESS)
        val server = try {
            ServerSocket(0, 50, address)
        } catch (e: Exception) {
            Log.w(TAG, "relay", e)
            stop()
            return
        }
        relay = server
        val out = FileOutputStream(established.fileDescriptor)
        val nat = TunNat(TunNat.int(address.address, 0), server.localPort, LinkHub.net::resolve) { packet ->
            synchronized(out) { runCatching { out.write(packet) } }
        }
        thread(name = "w7link-relay", isDaemon = true) {
            try {
                while (true) {
                    val client = server.accept()
                    thread(name = "w7link-relay-conn", isDaemon = true) {
                        val pipe = nat.destination(client.port)?.let { LinkHub.net.open(client, it.host, it.port) }
                        if (pipe == null) runCatching { client.close() } else pipe.start()
                    }
                }
            } catch (_: IOException) {
            }
        }
        thread(name = "w7link-tun", isDaemon = true) {
            runCatching {
                val packets = FileInputStream(established.fileDescriptor)
                val packet = ByteArray(32 * 1024)
                while (true) {
                    val n = packets.read(packet)
                    if (n < 0) break
                    nat.onPacket(packet, n)
                }
            }
        }
        setRunning(true)
    }

    private fun stop() {
        proxy?.close()
        proxy = null
        runCatching { relay?.close() }
        relay = null
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
        private const val ADDRESS = "10.111.0.2"

        // Nothing lives at this address; queries sent to it are answered by the phone's resolver.
        private const val DNS = "10.111.0.1"
        private const val PREF_WANTED = "internet"

        /** On unless the user turned it off on the watch. */
        fun setWanted(context: Context, on: Boolean) =
            context.getSharedPreferences("link", Context.MODE_PRIVATE).edit().putBoolean(PREF_WANTED, on).apply()

        /**
         * Follows the link: up when the phone connects, as long as VPN access was given once, and
         * down when it goes, so the watch falls back to its own Wi-Fi.
         */
        fun onLink(context: Context, connected: Boolean) {
            try {
                if (!connected) {
                    if (running) stop(context)
                } else if (context.getSharedPreferences("link", Context.MODE_PRIVATE).getBoolean(PREF_WANTED, true) && VpnService.prepare(context) == null) {
                    start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "internet", e)
            }
        }

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
