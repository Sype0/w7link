// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import android.content.SharedPreferences
import android.util.Base64
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.X509EncodedKeySpec
import java.util.Timer
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.concurrent.schedule

object Proto {
    /** Advertised by the watch; the phone scans for it. */
    val SERVICE_UUID: UUID = UUID.fromString("7c1d0f6e-3b52-4e0a-9d6f-77374c696e6b")

    /** Manufacturer data under this (unassigned) id carries the L2CAP PSM, big endian. */
    const val MANUFACTURER_ID = 0xFFFF

    /** Frame kinds. JSON carries the companion's own messages; the other two carry Heartline's sync. */
    const val KIND_JSON = 0
    const val KIND_ENVELOPE = 1
    const val KIND_STREAM = 2

    /** A [NetTunnel] frame. */
    const val KIND_NET = 3

    /** An APK for the watch to install: its size as a long, then its bytes. */
    const val APK_STREAM = "/w7link/apk"

    fun msg(type: String, vararg fields: Pair<String, Any?>): JSONObject {
        val o = JSONObject().put("t", type)
        for ((k, v) in fields) o.put(k, v)
        return o
    }
}

object Keys {
    /** The app's long-term identity key, created on first use. */
    fun load(prefs: SharedPreferences): KeyPair {
        val priv = prefs.getString("priv", null)
        val pub = prefs.getString("pub", null)
        if (priv != null && pub != null) {
            val kf = KeyFactory.getInstance("EC")
            return KeyPair(
                kf.generatePublic(X509EncodedKeySpec(Base64.decode(pub, Base64.NO_WRAP))),
                kf.generatePrivate(PKCS8EncodedKeySpec(Base64.decode(priv, Base64.NO_WRAP))),
            )
        }
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp256r1"))
        }.generateKeyPair()
        prefs.edit()
            .putString("priv", Base64.encodeToString(pair.private.encoded, Base64.NO_WRAP))
            .putString("pub", Base64.encodeToString(pair.public.encoded, Base64.NO_WRAP))
            .apply()
        return pair
    }
}

/**
 * Encrypted, authenticated frames over a plain stream (an unencrypted BLE L2CAP
 * channel). A frame is a kind byte ([Proto.KIND_JSON] and friends) and a body. Both sides hold a static P-256 key; the session keys come
 * from ECDH plus fresh nonces. The initiator commits to its key and nonce before
 * seeing the responder's, so [code] cannot be ground to a match by someone in
 * the middle; comparing it on both screens authenticates the first pairing.
 */
class SecureChannel(
    private val socket: Closeable,
    input: InputStream,
    private val output: OutputStream,
) : Closeable {
    private val input = DataInputStream(input)
    private lateinit var txKey: SecretKeySpec
    private lateinit var rxKey: SecretKeySpec
    private var txCounter = 0L
    private var rxCounter = 0L

    lateinit var peerKey: ByteArray
        private set
    lateinit var code: String
        private set

    fun handshake(self: KeyPair, initiator: Boolean, timeoutMs: Long = 15_000) {
        // Bluetooth sockets have no read timeout; a silent peer must not hold the link forever.
        val watchdog = Timer(true)
        watchdog.schedule(timeoutMs) { close() }
        try {
            val myPub = self.public.encoded
            val myNonce = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val peerPub: ByteArray
            val peerNonce: ByteArray
            if (initiator) {
                writeFrame(sha256(myPub, myNonce))
                peerPub = readFrame(512)
                peerNonce = readFrame(64)
                writeFrame(myPub)
                writeFrame(myNonce)
            } else {
                val commitment = readFrame(64)
                writeFrame(myPub)
                writeFrame(myNonce)
                peerPub = readFrame(512)
                peerNonce = readFrame(64)
                if (!MessageDigest.isEqual(commitment, sha256(peerPub, peerNonce))) {
                    throw IOException("handshake commitment mismatch")
                }
            }
            val secret = KeyAgreement.getInstance("ECDH").run {
                init(self.private)
                doPhase(KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(peerPub)), true)
                generateSecret()
            }
            val transcript = if (initiator) {
                concat(myPub, peerPub, myNonce, peerNonce)
            } else {
                concat(peerPub, myPub, peerNonce, myNonce)
            }
            val i2r = SecretKeySpec(hmac(secret, transcript, "i2r"), "AES")
            val r2i = SecretKeySpec(hmac(secret, transcript, "r2i"), "AES")
            txKey = if (initiator) i2r else r2i
            rxKey = if (initiator) r2i else i2r
            val digest = ByteBuffer.wrap(sha256(transcript)).int and 0x7fffffff
            code = "%06d".format(digest % 1_000_000)
            peerKey = peerPub
        } finally {
            watchdog.cancel()
        }
    }

    fun send(message: JSONObject) = sendFrame(Proto.KIND_JSON, message.toString().toByteArray())

    fun sendFrame(kind: Int, body: ByteArray) {
        synchronized(output) {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, txKey, GCMParameterSpec(128, nonce(txCounter++)))
            writeFrame(cipher.doFinal(byteArrayOf(kind.toByte()) + body))
        }
    }

    /** The next frame's kind and body. Only one thread may read. */
    fun receiveFrame(): Pair<Int, ByteArray> {
        val frame = readFrame(MAX_FRAME)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, rxKey, GCMParameterSpec(128, nonce(rxCounter++)))
        val plain = cipher.doFinal(frame)
        if (plain.isEmpty()) throw IOException("empty frame")
        return (plain[0].toInt() and 0xff) to plain.copyOfRange(1, plain.size)
    }

    override fun close() {
        try {
            socket.close()
        } catch (_: IOException) {
        }
    }

    private fun writeFrame(body: ByteArray) {
        val buf = ByteArrayOutputStream(body.size + 4)
        DataOutputStream(buf).run {
            writeInt(body.size)
            write(body)
        }
        output.write(buf.toByteArray())
        output.flush()
    }

    private fun readFrame(max: Int): ByteArray {
        val size = input.readInt()
        if (size < 0 || size > max) throw IOException("bad frame size $size")
        return ByteArray(size).also { input.readFully(it) }
    }

    private fun nonce(counter: Long) = ByteBuffer.allocate(12).putInt(0).putLong(counter).array()

    private fun sha256(vararg parts: ByteArray): ByteArray =
        MessageDigest.getInstance("SHA-256").run {
            parts.forEach { update(it) }
            digest()
        }

    private fun hmac(key: ByteArray, data: ByteArray, label: String): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(SecretKeySpec(key, "HmacSHA256"))
            update(data)
            doFinal(label.toByteArray())
        }

    private fun concat(vararg parts: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> parts.forEach { out.write(it) } }.toByteArray()

    companion object {
        const val MAX_FRAME = 1 shl 20
    }
}
