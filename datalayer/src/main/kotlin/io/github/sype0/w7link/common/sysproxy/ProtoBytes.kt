// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common.sysproxy

import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * Just enough protocol buffers for the proxy's control messages: varint fields and
 * length-delimited ones, written and read by hand, since that is all they use.
 */
internal class ProtoWriter {
    private val out = ByteArrayOutputStream()

    fun varint(field: Int, value: Long): ProtoWriter {
        tag(field, 0)
        raw(value)
        return this
    }

    fun bytes(field: Int, value: ByteArray): ProtoWriter {
        tag(field, 2)
        raw(value.size.toLong())
        out.write(value)
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun tag(field: Int, wireType: Int) = raw(((field shl 3) or wireType).toLong())

    private fun raw(value: Long) {
        var v = value
        while (v and 0x7fL.inv() != 0L) {
            out.write((v and 0x7f or 0x80).toInt())
            v = v ushr 7
        }
        out.write(v.toInt())
    }
}

/** Reads the fields of one message in order; [next] is false at its end. */
internal class ProtoReader(private val data: ByteArray, private var pos: Int = 0, private val end: Int = data.size) {
    var field = 0
        private set
    var wireType = 0
        private set
    var varint = 0L
        private set
    var bytes: ByteArray = EMPTY
        private set

    fun next(): Boolean {
        if (pos >= end) return false
        val tag = readVarint()
        field = (tag ushr 3).toInt()
        wireType = (tag and 7).toInt()
        when (wireType) {
            0 -> varint = readVarint()
            1 -> pos += 8
            2 -> {
                val size = readVarint().toInt()
                if (size < 0 || pos + size > end) throw IOException("bad length")
                bytes = data.copyOfRange(pos, pos + size)
                pos += size
            }
            5 -> pos += 4
            else -> throw IOException("bad wire type $wireType")
        }
        if (pos > end) throw IOException("truncated")
        return true
    }

    private fun readVarint(): Long {
        var result = 0L
        var shift = 0
        while (true) {
            if (pos >= end || shift > 63) throw IOException("bad varint")
            val b = data[pos++].toInt()
            result = result or ((b and 0x7f).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
    }

    private companion object {
        val EMPTY = ByteArray(0)
    }
}
