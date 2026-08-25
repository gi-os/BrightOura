package com.gios.brightoura.ble

import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The ring's challenge-response, which is smaller than its reputation.
 *
 * The ring holds a 16-byte key. Every connection it hands out a 15-byte nonce; the client encrypts
 * that nonce with the key and hands back the 16 bytes, and the ring compares. There is no key
 * exchange, no certificate, no server: the key is a value both sides already have, and the
 * encryption is `AES/ECB/PKCS5Padding` — which is why a 15-byte input becomes exactly one 16-byte
 * block, PKCS#5 appending the single byte `0x01`.
 *
 * That last detail is worth writing down, because the two published descriptions of this protocol
 * look different and are the same thing: "encrypt the nonce with PKCS5 padding" and
 * "AES-128-ECB(key, nonce ‖ 0x01)" describe one operation, and the second was verified against 484
 * of 484 captured nonce/proof pairs.
 *
 * ECB with a fixed key and a rotating nonce is a weak construction in general — a replayed nonce
 * replays its proof — but it is the ring's construction and interoperating means implementing it as
 * it is. Nothing here is a security boundary of ours: the key gates *reading a ring the user owns*.
 */
object Auth {

    /**
     * A key for a ring we are about to adopt.
     *
     * `SecureRandom`, not the app's own scheme. Oura's app derives its keys from
     * `UUID.randomUUID()`, which is 122 bits of randomness in a 128-bit field; there is no reason
     * to copy that shape when the ring only ever compares the bytes.
     */
    fun newKey(): ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }

    /**
     * The answer to a challenge.
     *
     * Returns null rather than throwing on a nonce the wrong length: what arrives here came off a
     * radio, and a malformed frame is a thing to retry rather than a crash.
     */
    fun proof(key: ByteArray, nonce: ByteArray): ByteArray? {
        if (key.size != 16 || nonce.isEmpty() || nonce.size > 15) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
            cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"))
            // One block out, whatever the padding decided to do inside.
            cipher.doFinal(nonce).copyOf(16)
        }.getOrNull()
    }

    fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun fromHex(text: String): ByteArray? {
        val clean = text.trim().removePrefix("0x").filterNot { it.isWhitespace() }
        if (clean.length != 32 || clean.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(16) { clean.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }
}
