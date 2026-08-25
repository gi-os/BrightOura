package com.gios.brightoura.data

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Where the ring's auth key lives.
 *
 * The key *is* the ring: anything holding it can read every measurement the ring has taken. So it
 * is not kept as plain text in preferences — it is sealed with an AES key generated inside the
 * AndroidKeyStore, which cannot be exported from the device even by this app.
 *
 * **Not user-authentication-bound**, deliberately. A key that needs the lock screen to unwrap is a
 * key a background sync cannot use, and the ring is read while the phone sits in a pocket. The
 * protection this gives is against the sealed value being copied off the phone — a backup, an adb
 * pull of app data — which is the threat that actually exists here.
 *
 * If the keystore entry ever goes (a factory reset of the *phone*, a restored backup, a wiped
 * keystore) the sealed value becomes unreadable and the app says the ring needs pairing again. That
 * is the honest outcome: better than pretending, and recoverable in two minutes.
 */
class Vault(context: Context) {

    private val prefs = context.getSharedPreferences("brightoura", Context.MODE_PRIVATE)

    /** The stored ring key, or null when there is none — or none this phone can still read. */
    fun key(): ByteArray? {
        val sealed = prefs.getString(SEALED, null) ?: return null
        return runCatching {
            val raw = android.util.Base64.decode(sealed, android.util.Base64.NO_WRAP)
            val iv = raw.copyOfRange(0, IV_BYTES)
            val body = raw.copyOfRange(IV_BYTES, raw.size)
            val cipher = Cipher.getInstance(TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, wrapper(), GCMParameterSpec(TAG_BITS, iv))
            cipher.doFinal(body)
        }.getOrNull()
    }

    /** Seal a key. False when the keystore refused, which is worth telling the user about. */
    fun store(key: ByteArray): Boolean = runCatching {
        val cipher = Cipher.getInstance(TRANSFORM)
        cipher.init(Cipher.ENCRYPT_MODE, wrapper())
        val body = cipher.doFinal(key)
        val packed = cipher.iv + body
        prefs.edit()
            .putString(SEALED, android.util.Base64.encodeToString(packed, android.util.Base64.NO_WRAP))
            .apply()
        true
    }.getOrDefault(false)

    fun forget() {
        prefs.edit().remove(SEALED).apply()
    }

    /** The ring's BLE address, so a sync does not have to scan for it every time. */
    var address: String?
        get() = prefs.getString(ADDRESS, null)
        set(v) { prefs.edit().putString(ADDRESS, v).apply() }

    /** What the ring called itself, for the setup screen to show. */
    var name: String?
        get() = prefs.getString(NAME, null)
        set(v) { prefs.edit().putString(NAME, v).apply() }

    /**
     * Where the last sync got to, in the ring's own deciseconds.
     *
     * The ring's clock, not the phone's: the cursor is handed straight back to it, and converting
     * through a phone clock that has drifted or changed zone is how a sync silently re-reads a week
     * or skips one.
     */
    var cursorDeciseconds: Long
        get() = prefs.getLong(CURSOR, 0L)
        set(v) { prefs.edit().putLong(CURSOR, v).apply() }

    var lastSyncMs: Long
        get() = prefs.getLong(LAST_SYNC, 0L)
        set(v) { prefs.edit().putLong(LAST_SYNC, v).apply() }

    private fun wrapper(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getEntry(ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(
                ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                // Explicitly not required: a sync runs with the screen off. See the class note.
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ALIAS = "brightoura.ringkey"
        const val TRANSFORM = "AES/GCM/NoPadding"
        const val IV_BYTES = 12
        const val TAG_BITS = 128

        const val SEALED = "ringKeySealed"
        const val ADDRESS = "ringAddress"
        const val NAME = "ringName"
        const val CURSOR = "cursorDeciseconds"
        const val LAST_SYNC = "lastSyncMs"
    }
}
