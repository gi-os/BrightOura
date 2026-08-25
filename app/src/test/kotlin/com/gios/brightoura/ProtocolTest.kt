package com.gios.brightoura

import com.gios.brightoura.ble.Protocol
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The framing and the requests, checked against the captured bytes in the protocol notes.
 *
 * These are the frames a real ring answered, written down: a test that agrees with them is a test
 * that the port did not drift. Everything here is pure Kotlin, so it runs on the JVM without a
 * device — which matters, because the device end of this app cannot be tested without a ring.
 */
class ProtocolTest {

    private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }

    @Test
    fun `auth nonce request matches the capture`() {
        assertEquals("2f012b", hex(Protocol.authNonce()))
    }

    @Test
    fun `authenticate carries the sixteen byte proof under the extended tag`() {
        val proof = ByteArray(16) { 0x11 }
        // `2f 11 2d <16 bytes>`: outer tag, length 17, ext op 0x2d, then the proof.
        assertEquals("2f112d" + "11".repeat(16), hex(Protocol.authenticate(proof)))
    }

    @Test
    fun `installing a key is tag 24 with a length of sixteen`() {
        val key = ByteArray(16) { (it + 1).toByte() }
        assertEquals("2410" + (1..16).joinToString("") { "%02x".format(it) }, hex(Protocol.setAuthKey(key)))
    }

    @Test
    fun `a key that is not sixteen bytes is refused before it reaches the ring`() {
        val thrown = runCatching { Protocol.setAuthKey(ByteArray(8)) }.exceptionOrNull()
        assertTrue(thrown is IllegalArgumentException)
    }

    @Test
    fun `feature mode is the extended set with feature and mode`() {
        // `2f 03 22 <feature> <mode>`: daytime HR (0x02) to AUTOMATIC (0x01).
        assertEquals(
            "2f03220201",
            hex(Protocol.setFeatureMode(Protocol.Feature.DAYTIME_HR, Protocol.Mode.AUTOMATIC)),
        )
    }

    @Test
    fun `events are asked for from a cursor in little endian`() {
        // 0x0102 deciseconds, eight events, every type.
        // `10 09 <4-byte cursor LE> <max> <4-byte flags LE>`.
        assertEquals("100902010000" + "08" + "ffffffff", hex(Protocol.getEvents(0x0102, 8)))
    }

    @Test
    fun `a frame is parsed leniently when the ring pads it`() {
        // Declared length 2, three bytes present: the extra is kept rather than the frame dropped.
        val packet = Protocol.parse(byteArrayOf(0x0c, 0x02, 0x5a, 0x01))
        assertEquals(0x0c, packet?.tag)
        assertEquals(2, packet?.payload?.size)
    }

    @Test
    fun `a frame too short to hold a header is not a frame`() {
        assertNull(Protocol.parse(byteArrayOf(0x0c)))
    }

    @Test
    fun `the needs-auth reply is recognised rather than read as a failure`() {
        // `2f 02 2f 01` — the ring saying this session has not authenticated.
        val packet = Protocol.parse(byteArrayOf(0x2f, 0x02, 0x2f, 0x01))!!
        assertTrue(Protocol.needsAuth(packet))
    }

    @Test
    fun `an ordinary extended reply is not a needs-auth`() {
        // `2f 02 2e 00` — authentication accepted.
        val packet = Protocol.parse(byteArrayOf(0x2f, 0x02, 0x2e, 0x00))!!
        assertFalse(Protocol.needsAuth(packet))
    }

    @Test
    fun `history events are told apart from replies by their tag`() {
        assertTrue(Protocol.parse(byteArrayOf(0x60, 0x01, 0x00))!!.isEvent)
        assertFalse(Protocol.parse(byteArrayOf(0x25, 0x01, 0x00))!!.isEvent)
    }
}
