package com.gios.brightoura.ble

import java.util.UUID

/**
 * The Oura ring's BLE protocol: the GATT endpoints, the frame format, and the requests.
 *
 * ### Where this comes from
 *
 * Reverse-engineered by the `open_oura` project (MIT) from the official Android app's native
 * libraries, verified live against a Ring 3 Horizon and a Ring 5, and cross-checked against the
 * ringverse Ring 4 notes. Ring 3, 4 and 5 share the same service, the same framing and the same
 * authentication flow — the generation differences are in which event tags a ring emits, not in how
 * you talk to it. This file is a straight port of the parts a diary needs; nothing here was guessed.
 *
 * ### The frame
 *
 * `tag | length | payload`, every multi-byte integer little-endian. Extended operations ride under
 * outer tag [EXT] with the first payload byte as the real operation. Anything with a tag at or above
 * [HISTORY_EVENT_PREFIX] is a history event rather than a reply to something we asked.
 *
 * ### What needs authentication
 *
 * Firmware, serial and hardware id answer cold. Battery, history events, features and the realtime
 * streams all answer `2f 02 2f 01` until the session is authenticated — that exact frame is the
 * ring's way of saying "authenticate first", and it is the signal [Ring] watches for.
 */
object Protocol {

    /** The ring's GATT service. The same on Ring 3, 4 and 5. */
    val SERVICE: UUID = UUID.fromString("98ed0001-a541-11e4-b6a0-0002a5d5c51b")

    /** Requests are written here. */
    val WRITE: UUID = UUID.fromString("98ed0002-a541-11e4-b6a0-0002a5d5c51b")

    /** Replies and asynchronous notifications arrive here. */
    val NOTIFY: UUID = UUID.fromString("98ed0003-a541-11e4-b6a0-0002a5d5c51b")

    /** Outer tag for extended operations. */
    const val EXT = 0x2f

    /** At or above this, a frame is a history event and not a reply. */
    const val HISTORY_EVENT_PREFIX = 0x41

    /** One frame. */
    data class Packet(val tag: Int, val payload: ByteArray) {

        /** The extended operation, for [EXT] frames. */
        val ext: Int? get() = if (tag == EXT) payload.firstOrNull()?.toInt()?.and(0xff) else null

        val isEvent: Boolean get() = tag >= HISTORY_EVENT_PREFIX

        fun hex(): String = payload.joinToString("") { "%02x".format(it) }

        override fun equals(other: Any?): Boolean =
            other is Packet && other.tag == tag && other.payload.contentEquals(payload)

        override fun hashCode(): Int = 31 * tag + payload.contentHashCode()
    }

    /**
     * Read a frame, leniently.
     *
     * The declared length is believed only as far as the buffer allows: rings pad frames
     * occasionally, and a parser that returns nothing for a frame one byte short of its own header
     * is a parser that drops real data. Everything after the header is used when the two disagree.
     */
    fun parse(frame: ByteArray): Packet? {
        if (frame.size < 2) return null
        val tag = frame[0].toInt() and 0xff
        val len = frame[1].toInt() and 0xff
        val end = (2 + len).coerceAtMost(frame.size)
        return Packet(tag, frame.copyOfRange(2, end))
    }

    private fun frame(tag: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        val out = ByteArray(payload.size + 2)
        out[0] = tag.toByte()
        out[1] = payload.size.toByte()
        payload.copyInto(out, 2)
        return out
    }

    // ------------------------------------------------------------------ what we ask for

    /** Firmware, API, bootloader, BT stack, MAC. Answers without authentication. */
    fun firmware(): ByteArray = byteArrayOf(0x08, 0x03, 0x00, 0x00, 0x00)

    /** The serial number slot. Also answers cold — useful for "is this your ring?". */
    fun serial(): ByteArray = byteArrayOf(0x18, 0x03, 0x08, 0x00, 0x10)

    /** The hardware id slot, e.g. `BLB_03`, which is how a generation identifies itself. */
    fun hardware(): ByteArray = byteArrayOf(0x18, 0x03, 0x18, 0x00, 0x10)

    /** Battery percent. Authenticated. */
    fun battery(): ByteArray = frame(0x0c)

    /** A capabilities page. Authenticated. */
    fun capabilities(page: Int): ByteArray = byteArrayOf(0x2f, 0x02, 0x01, page.toByte())

    /**
     * Install a 16-byte key. **Only a factory-reset ring accepts this** — an onboarded ring
     * already has one and answers with a failure rather than replacing it, which is the whole
     * reason the setup flow has to ask for a reset.
     */
    fun setAuthKey(key: ByteArray): ByteArray {
        require(key.size == 16) { "an auth key is 16 bytes" }
        return frame(0x24, key)
    }

    /** Ask for the 15-byte challenge. */
    fun authNonce(): ByteArray = byteArrayOf(0x2f, 0x01, 0x2b)

    /** Answer it with the encrypted nonce. See [Auth]. */
    fun authenticate(proof: ByteArray): ByteArray {
        require(proof.size == 16) { "the proof is 16 bytes" }
        return frame(EXT, byteArrayOf(0x2d) + proof)
    }

    /** Read a feature's runtime mode. */
    fun featureStatus(feature: Int): ByteArray =
        byteArrayOf(0x2f, 0x02, 0x20, feature.toByte())

    /** Turn a feature on (or off). See [Feature] and [Mode]. */
    fun setFeatureMode(feature: Int, mode: Int): ByteArray =
        byteArrayOf(0x2f, 0x03, 0x22, feature.toByte(), mode.toByte())

    /** A feature's last cached values — HR and SpO2 without waiting for a measurement. */
    fun featureLatest(feature: Int): ByteArray =
        byteArrayOf(0x2f, 0x02, 0x24, feature.toByte())

    /**
     * History events from [startDeciseconds], at most [max] of them.
     *
     * `flags` is passed through as the app passes it: `-1` means every type. Deciseconds, not
     * milliseconds — the ring's own clock unit, and the cursor a sync resumes from.
     */
    fun getEvents(startDeciseconds: Long, max: Int, flags: Int = -1): ByteArray {
        val payload = ByteArray(9)
        var v = startDeciseconds
        for (i in 0 until 4) {
            payload[i] = (v and 0xff).toByte()
            v = v shr 8
        }
        payload[4] = max.toByte()
        var f = flags
        for (i in 5 until 9) {
            payload[i] = (f and 0xff).toByte()
            f = f shr 8
        }
        return frame(0x10, payload)
    }

    /** Ask the ring to run its sleep analysis, after which sleep events appear in history. */
    fun checkSleepAnalysis(force: Boolean): ByteArray =
        frame(0x28, byteArrayOf(if (force) 1 else 0))

    /** Feature ids for [setFeatureMode]. */
    object Feature {
        const val DAYTIME_HR = 0x02
        const val EXERCISE_HR = 0x03
        const val SPO2 = 0x04
        const val RESTING_HR = 0x08
        const val CHARGING_CONTROL = 0x0e
    }

    /** Feature modes. [CONNECTED_LIVE] is what streams a live heart rate. */
    object Mode {
        const val OFF = 0x00
        const val AUTOMATIC = 0x01
        const val REQUESTED = 0x02
        const val CONNECTED_LIVE = 0x03
    }

    /**
     * Replies that mean something on their own.
     *
     * `2f 02 2f 01` is the one that matters: not an error, but the ring saying this session has not
     * authenticated. Treating it as a failure is how a client ends up looking broken on a ring that
     * is working perfectly.
     */
    fun needsAuth(packet: Packet): Boolean =
        packet.tag == EXT && packet.ext == 0x2f && packet.payload.getOrNull(1)?.toInt() == 0x01
}
