// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import io.github.sype0.w7link.common.sysproxy.IpNat
import io.github.sype0.w7link.common.sysproxy.ProxyLink
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The phone end of the watch's own internet-over-Bluetooth, the way Wear OS does it with its
 * companion ("phone-based wearable device tethering"): the phone listens on an L2CAP channel and
 * writes its PSM to a GATT characteristic the watch's system serves; the watch then connects there
 * and the whole watch is online through [ProxyLink] and [IpNat], with no VPN on either side. The
 * watch only takes the write from the phone it was set up with, over a bonded link.
 *
 * Older watches ask for the first version of this on an RFCOMM service instead; that protocol is
 * not public, so a call there is only noted.
 */
@SuppressLint("MissingPermission")
class SysProxy(private val context: Context, private val adapter: BluetoothAdapter, private val onChange: () -> Unit) {
    enum class Status { WAITING, OFFERING, OFFERED, NO_SERVICE, CONNECTED, OLD_PROXY, FAILED }

    @Volatile
    var status = Status.WAITING
        private set

    /** What went wrong, for [Status.FAILED]. */
    @Volatile
    var detail = ""
        private set

    val flows: Int
        get() = nat?.flows ?: 0

    @Volatile
    private var server: BluetoothServerSocket? = null

    @Volatile
    private var oldServer: BluetoothServerSocket? = null
    private var psm = 0
    private val changeId = (System.currentTimeMillis() / 1000).toInt() and 0x7fffffff

    @Volatile
    private var watch: BluetoothDevice? = null

    @Volatile
    private var link: ProxyLink? = null

    @Volatile
    private var nat: IpNat? = null

    @Volatile
    private var session: BluetoothSocket? = null
    private val main = Handler(Looper.getMainLooper())
    private val offer = Runnable { writeConfig() }
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val networkWatch = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetwork()
        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) = refreshNetwork()
        override fun onLost(network: Network) = refreshNetwork()
    }

    fun start() {
        if (server != null) return
        val listener = try {
            adapter.listenUsingL2capChannel()
        } catch (e: Exception) {
            Log.w(TAG, "listen", e)
            fail("L2CAP: $e")
            return
        }
        server = listener
        psm = listener.psm
        Log.i(TAG, "listening on psm $psm")
        thread(name = "w7link-sysproxy", isDaemon = true) {
            try {
                while (true) onSession(listener.accept())
            } catch (e: IOException) {
                Log.i(TAG, "listener closed: $e")
            }
        }
        runCatching { connectivity.registerDefaultNetworkCallback(networkWatch) }
        listenOld()
        main.post(offer)
    }

    fun stop() {
        main.removeCallbacks(offer)
        runCatching { connectivity.unregisterNetworkCallback(networkWatch) }
        runCatching { server?.close() }
        server = null
        runCatching { oldServer?.close() }
        oldServer = null
        link?.close()
    }

    /** The watch the link is up with, or null when it went; the offer follows it. */
    fun onWatch(device: BluetoothDevice?) {
        watch = device
        main.removeCallbacks(offer)
        if (device != null) main.post(offer)
    }

    /** Tells the connected watch what the phone is online through now, or that it is not. */
    fun refreshNetwork() {
        main.post { link?.takeIf { it.isOpen }?.sendNetworkConfig(currentLinks()) }
    }

    // --- offering the channel ---

    /** Writes the PSM to the watch's proxy config characteristic, and tries again later while nothing connects. */
    private fun writeConfig() {
        main.removeCallbacks(offer)
        val device = watch ?: return
        if (psm == 0) return
        if (link?.isOpen == true) {
            main.postDelayed(offer, RE_OFFER_MS)
            return
        }
        set(Status.OFFERING)
        try {
            device.connectGatt(context, false, ConfigWriter(), BluetoothDevice.TRANSPORT_LE)
        } catch (e: Exception) {
            fail("GATT: $e")
        }
        main.postDelayed(offer, RE_OFFER_MS)
    }

    private inner class ConfigWriter : BluetoothGattCallback() {
        private var written = false

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                gatt.discoverServices()
            } else {
                gatt.close()
                if (!written && this@SysProxy.status == Status.OFFERING) fail("GATT connection $status")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val config = gatt.getService(SERVICE_UUID)?.getCharacteristic(CONFIG_UUID)
            if (config == null) {
                Log.i(TAG, "the watch serves no proxy config characteristic")
                set(Status.NO_SERVICE)
                gatt.disconnect()
                return
            }
            val value = ProxyLink.configMessage(psm, changeId)
            val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(config, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothGatt.GATT_SUCCESS
            } else {
                @Suppress("DEPRECATION")
                config.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                config.value = value
                @Suppress("DEPRECATION")
                gatt.writeCharacteristic(config)
            }
            if (!ok) {
                fail("GATT write refused")
                gatt.disconnect()
            }
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) {
                written = true
                Log.i(TAG, "offered psm $psm to the watch")
                set(Status.OFFERED)
            } else {
                // The watch refuses the write from any phone but the one it was set up with.
                fail("GATT write status $status")
            }
            gatt.disconnect()
        }
    }

    // --- the session ---

    private fun onSession(socket: BluetoothSocket) {
        Log.i(TAG, "connection from ${socket.remoteDevice.address.takeLast(5)}")
        link?.close()
        session?.let { runCatching { it.close() } }
        session = socket
        val relay = IpNat({ packet -> link?.send(packet) }) { address ->
            CompanionPref.INTERNET.get(context) && !address.isLoopbackAddress && !address.isAnyLocalAddress
        }
        nat = relay
        val proxy = ProxyLink(socket.inputStream, socket.outputStream, object : ProxyLink.Listener {
            override fun onOpen(link: ProxyLink) {
                Log.i(TAG, "the watch is online through this phone")
                set(Status.CONNECTED)
                link.sendNetworkConfig(currentLinks())
            }

            override fun onPacket(link: ProxyLink, packet: ByteArray) = relay.onPacket(packet)

            override fun onClosed(link: ProxyLink, reason: String) {
                Log.i(TAG, "session over: $reason")
                relay.close()
                runCatching { socket.close() }
                if (this@SysProxy.link === link) {
                    this@SysProxy.link = null
                    nat = null
                    set(Status.WAITING)
                    main.postDelayed(offer, RETRY_MS)
                }
            }
        })
        link = proxy
        proxy.start()
    }

    /** The phone's own uplink, as the watch wants to hear of it; none while sharing is off. */
    private fun currentLinks(): List<ProxyLink.LinkInfo> {
        if (!CompanionPref.INTERNET.get(context)) return emptyList()
        val network = connectivity.activeNetwork ?: return emptyList()
        val caps = connectivity.getNetworkCapabilities(network) ?: return emptyList()
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return emptyList()
        var transports = 0L
        caps.transportTypes.forEach { transports = transports or (1L shl it) }
        var capabilities = 0L
        caps.capabilities.forEach { capabilities = capabilities or (1L shl it) }
        return listOf(ProxyLink.LinkInfo(network.toString().toIntOrNull() ?: 1, transports, capabilities))
    }

    // --- the old proxy ---

    private fun listenOld() {
        val listener = try {
            adapter.listenUsingRfcommWithServiceRecord("W7Link proxy", OLD_PROXY_UUID)
        } catch (e: Exception) {
            Log.w(TAG, "old listen", e)
            return
        }
        oldServer = listener
        thread(name = "w7link-sysproxy-v1", isDaemon = true) {
            try {
                while (true) {
                    val socket = listener.accept()
                    thread(name = "w7link-sysproxy-v1-conn", isDaemon = true) { noteOld(socket) }
                }
            } catch (e: IOException) {
                Log.i(TAG, "old listener closed: $e")
            }
        }
    }

    private fun noteOld(socket: BluetoothSocket) {
        Log.i(TAG, "old proxy call from ${socket.remoteDevice.address.takeLast(5)}")
        if (status == Status.WAITING || status == Status.NO_SERVICE) set(Status.OLD_PROXY)
        try {
            socket.use {
                val input = it.inputStream
                val buffer = ByteArray(1024)
                var total = 0
                while (total < MAX_LOGGED) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    Log.i(TAG, "v1 rx $n: " + buffer.take(n).joinToString("") { b -> "%02x".format(b) })
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "old call ended: $e")
        }
    }

    private fun set(status: Status) {
        this.status = status
        onChange()
    }

    private fun fail(detail: String) {
        Log.w(TAG, detail)
        this.detail = detail
        set(Status.FAILED)
    }

    private companion object {
        const val TAG = "W7Proxy"
        const val MAX_LOGGED = 16 * 1024
        const val RE_OFFER_MS = 45_000L
        const val RETRY_MS = 5_000L

        /** The watch's proxy config service and its characteristic (GattDiscoveryClient in Android's sources). */
        val SERVICE_UUID: UUID = UUID.fromString("92cda598-78c1-4878-94ec-36fb0eda7e0d")
        val CONFIG_UUID: UUID = UUID.fromString("2164b0d4-8beb-4895-84da-fa27c7ab21f9")

        /** The RFCOMM service the first version of the proxy calls (WearProxyConstants.PROXY_UUID). */
        val OLD_PROXY_UUID: UUID = UUID.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2")
    }
}
