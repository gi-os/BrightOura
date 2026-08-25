package com.gios.brightoura

import com.gios.brightoura.ble.Auth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The challenge-response, and the one property that matters about it.
 *
 * The ring's proof is `AES/ECB/PKCS5Padding` over a 15-byte nonce, which the community's other
 * description states as `AES-128-ECB(key, nonce ‖ 0x01)` — verified against 484 of 484 captured
 * pairs. Those are the same operation, because PKCS#5 pads a 15-byte input with exactly one `0x01`
 * byte. This test says so in code, so a future refactor to "one AES block, padding done by hand"
 * cannot quietly change the answer.
 */
class AuthTest {

    private val key = ByteArray(16) { (it * 7 + 3).toByte() }

    @Test
    fun `padding a nonce by hand gives the same proof as PKCS5 does`() {
        val nonce = ByteArray(15) { (it * 11 + 5).toByte() }
        val viaPadding = Auth.proof(key, nonce)
        // The same operation stated the other way: one AES block over the nonce with a single
        // 0x01 appended. PKCS#5 on a 15-byte input appends exactly that, which is why the two
        // published descriptions of this protocol are one description.
        val cipher = Cipher.getInstance("AES/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
        val byHand = cipher.doFinal(nonce + byteArrayOf(0x01))
        assertNotNull(viaPadding)
        assertEquals(Auth.hex(byHand), Auth.hex(viaPadding!!))
    }

    @Test
    fun `a proof is one block`() {
        assertEquals(16, Auth.proof(key, ByteArray(15))!!.size)
    }

    @Test
    fun `a key of the wrong length has no proof to give`() {
        assertNull(Auth.proof(ByteArray(8), ByteArray(15)))
    }

    @Test
    fun `an empty or oversized nonce is refused`() {
        assertNull(Auth.proof(key, ByteArray(0)))
        assertNull(Auth.proof(key, ByteArray(16)))
    }

    @Test
    fun `a generated key is sixteen bytes and not the same twice`() {
        val a = Auth.newKey()
        val b = Auth.newKey()
        assertEquals(16, a.size)
        assertEquals(16, b.size)
        assert(!a.contentEquals(b))
    }

    @Test
    fun `hex round trips`() {
        val key = Auth.newKey()
        assertEquals(Auth.hex(key), Auth.hex(Auth.fromHex(Auth.hex(key))!!))
        assertNull(Auth.fromHex("not a key"))
        assertNull(Auth.fromHex("abcd"))
    }
}
