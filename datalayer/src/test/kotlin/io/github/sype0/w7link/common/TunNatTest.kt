// SPDX-License-Identifier: AGPL-3.0-or-later
// Copyright (C) 2026 Sype0

package io.github.sype0.w7link.common

import io.github.sype0.w7link.common.TunNat.Companion.checksum
import io.github.sype0.w7link.common.TunNat.Companion.int
import io.github.sype0.w7link.common.TunNat.Companion.pseudoHeader
import io.github.sype0.w7link.common.TunNat.Companion.putInt
import io.github.sype0.w7link.common.TunNat.Companion.putShort
import io.github.sype0.w7link.common.TunNat.Companion.short
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TunNatTest {
    private val local = 0x0a6f0002
    private val far = 0x5db8d822
    private val relayPort = 40000
    private val written = ArrayList<ByteArray>()
    private var query: ByteArray? = null
    private var answer: ((ByteArray) -> Unit)? = null
    private val nat = TunNat(local, relayPort, { q, onReply -> query = q; answer = onReply }, { written.add(it) })

    private fun packet(protocol: Int, to: Int, fromPort: Int, toPort: Int, flags: Int = 0, payload: ByteArray = ByteArray(0)): ByteArray {
        val transport = if (protocol == 6) 20 else 8
        val p = ByteArray(20 + transport + payload.size)
        p[0] = 0x45
        putShort(p, 2, p.size)
        p[8] = 64
        p[9] = protocol.toByte()
        putInt(p, 12, local)
        putInt(p, 16, to)
        putShort(p, 20, fromPort)
        putShort(p, 22, toPort)
        if (protocol == 6) {
            p[32] = 0x50
            p[33] = flags.toByte()
        } else {
            putShort(p, 24, transport + payload.size)
        }
        payload.copyInto(p, 20 + transport)
        return p
    }

    private fun send(p: ByteArray) = nat.onPacket(p.copyOf(1500), p.size)

    private fun assertChecksums(p: ByteArray) {
        assertEquals(0, checksum(p, 0, 20, 0))
        assertEquals(0, checksum(p, 20, p.size, pseudoHeader(p, 6, p.size - 20)))
    }

    @Test
    fun aConnectionIsTurnedTowardsTheRelayAndBack() {
        send(packet(6, far, 5555, 443, flags = 0x02, payload = byteArrayOf(1, 2, 3)))
        val inbound = written.single()
        assertEquals(far, int(inbound, 12))
        assertEquals(local, int(inbound, 16))
        assertEquals(5555, short(inbound, 20))
        assertEquals(relayPort, short(inbound, 22))
        assertChecksums(inbound)
        val to = nat.destination(5555)!!
        assertEquals("93.184.216.34", to.host)
        assertEquals(443, to.port)

        // The relay's answer, addressed to what it believes is its client.
        send(packet(6, far, relayPort, 5555, flags = 0x12))
        val back = written[1]
        assertEquals(far, int(back, 12))
        assertEquals(local, int(back, 16))
        assertEquals(443, short(back, 20))
        assertEquals(5555, short(back, 22))
        assertChecksums(back)
    }

    @Test
    fun packetsOfNoKnownConnectionAreDropped() {
        send(packet(6, far, 5555, 443, flags = 0x10))
        send(packet(6, far, relayPort, 5555, flags = 0x10))
        send(packet(17, far, 5555, 443, payload = byteArrayOf(1)))
        assertTrue(written.isEmpty())
        assertNull(nat.destination(5555))
    }

    @Test
    fun aDnsQueryIsAnsweredFromTheServerItWentTo() {
        send(packet(17, far, 6000, 53, payload = byteArrayOf(9, 8, 7)))
        assertArrayEquals(byteArrayOf(9, 8, 7), query)
        answer!!(byteArrayOf(4, 5))
        val reply = written.single()
        assertEquals(30, reply.size)
        assertEquals(30, short(reply, 2))
        assertEquals(17, reply[9].toInt())
        assertEquals(far, int(reply, 12))
        assertEquals(local, int(reply, 16))
        assertEquals(0, checksum(reply, 0, 20, 0))
        assertEquals(53, short(reply, 20))
        assertEquals(6000, short(reply, 22))
        assertEquals(10, short(reply, 24))
        assertArrayEquals(byteArrayOf(4, 5), reply.copyOfRange(28, 30))
    }
}
