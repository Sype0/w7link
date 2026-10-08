// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.phone

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.util.Log
import java.io.IOException
import java.util.UUID
import kotlin.concurrent.thread

/**
 * The phone end of Wear OS's own internet-over-Bluetooth: the watch's system keeps calling its
 * companion phone on this RFCOMM service and, once something answers its protocol there, gives the
 * whole watch a network. The protocol isn't published, so for now this only answers the call and
 * logs what the watch says, to learn it.
 */
@SuppressLint("MissingPermission")
class SysProxy(private val adapter: BluetoothAdapter) {
    @Volatile
    private var server: BluetoothServerSocket? = null

    fun start() {
        if (server != null) return
        val listener = try {
            adapter.listenUsingRfcommWithServiceRecord("W7Link proxy", SERVICE)
        } catch (e: Exception) {
            Log.w(TAG, "listen", e)
            return
        }
        server = listener
        Log.i(TAG, "listening")
        thread(name = "w7link-sysproxy", isDaemon = true) {
            try {
                while (true) {
                    val socket = listener.accept()
                    thread(name = "w7link-sysproxy-conn", isDaemon = true) { listen(socket) }
                }
            } catch (e: IOException) {
                Log.i(TAG, "listener closed: $e")
            }
        }
    }

    fun stop() {
        runCatching { server?.close() }
        server = null
    }

    private fun listen(socket: BluetoothSocket) {
        Log.i(TAG, "call from ${socket.remoteDevice.address.takeLast(5)}")
        try {
            socket.use {
                val input = it.inputStream
                val buffer = ByteArray(1024)
                var total = 0
                while (total < MAX_LOGGED) {
                    val n = input.read(buffer)
                    if (n < 0) break
                    total += n
                    Log.i(TAG, "rx $n: " + buffer.take(n).joinToString("") { b -> "%02x".format(b) })
                }
            }
        } catch (e: IOException) {
            Log.i(TAG, "call ended: $e")
        }
    }

    private companion object {
        const val TAG = "W7Proxy"
        const val MAX_LOGGED = 64 * 1024
        val SERVICE: UUID = UUID.fromString("fafbdd20-83f0-4389-addf-917ac9dae5b2")
    }
}
