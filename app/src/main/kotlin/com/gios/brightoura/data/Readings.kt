package com.gios.brightoura.data

/**
 * Turning the ring's frames into numbers a person would recognise.
 *
 * Deliberately free of any Android import, for the same reason the pairing-code reader is: this is
 * the part that can be wrong in ways nothing throws, and a decoder that can be run against
 * hand-built bytes on a desk is one that gets fixed in minutes rather than evenings.
 *
 * ## What is known, and what is guessed
 *
 * Three layers of confidence, and this file keeps them apart on purpose:
 *
 *  - **Documented.** The frame envelope, the tag table, and the timestamp's meaning come from the
 *    protocol notes at ringverse/protocol, which were taken off a Ring 4 — the ring in hand.
 *  - **Validated by somebody else's capture.** The bit layouts for inter-beat intervals and HRV
 *    come from open_oura, which checked them against overnight recordings on a Ring 3 and a Ring 5.
 *    Those are the decoders here that produce real units.
 *  - **Inferred.** Temperature and step counts are known to *exist* in their events and known
 *    roughly what they should read — a worn ring is about 33 °C — without a byte layout anybody has
 *    written down. Those are decoded to the most plausible reading and **marked**
 *    [Reading.inferred], so a screen can show them differently and nobody builds a habit on a
 *    number that might be a scaling error.
 *
 * Nothing is thrown away for being undecodable: [EventLog] keeps every frame as bytes, so a better
 * decoder is a re-parse rather than another night of waiting.
 */
object Readings {

    /** One event, as it came off the wire. */
    data class Frame(val tag: Int, val ticks: Long, val payload: ByteArray) {
        override fun equals(other: Any?) = other is Frame &&
            tag == other.tag && ticks == other.ticks && payload.contentEquals(other.payload)

        override fun hashCode() = (tag * 31 + ticks.hashCode()) * 31 + payload.contentHashCode()
    }

    /** Something measured, at a moment, in a unit. */
    sealed interface Reading {
        /** When, in the ring's own clock. Converted by [Clock], not here. */
        val ticks: Long

        /** True when the byte layout behind this is a best guess rather than a documented one. */
        val inferred: Boolean get() = false

        data class Heart(
            override val ticks: Long,
            /** Beats per minute, from the interval between beats. */
            val bpm: Int,
            /** The interval itself, in milliseconds — the honest measurement. */
            val intervalMs: Int,
            /** 0 is best. The ring's own opinion of the signal. */
            val quality: Int,
        ) : Reading

        data class Hrv(
            override val ticks: Long,
            val bpm: Int,
            /** Root mean square of successive differences, in milliseconds. */
            val rmssdMs: Int,
        ) : Reading

        data class Temp(
            override val ticks: Long,
            val celsius: Double,
            override val inferred: Boolean = true,
        ) : Reading

        data class Steps(
            override val ticks: Long,
            val count: Int,
            override val inferred: Boolean = true,
        ) : Reading

        /** Whether the ring believes it is on a finger. Everything else depends on this. */
        data class Wear(override val ticks: Long, val worn: Boolean) : Reading

        /** A frame this app can store and cannot read. Counted, never invented. */
        data class Unread(override val ticks: Long, val tag: Int, val bytes: Int) : Reading
    }

    /**
     * The ring's clock is seconds since *its* boot, so a timestamp means nothing on its own.
     *
     * The anchor is the Sync Time response (`0x13`), which reports the ring's current reading of
     * its own clock; noting the phone's wall clock in the same breath gives one pair of
     * corresponding instants, and every event converts through that pair.
     *
     * ### Why the tick size is a parameter
     *
     * The protocol notes call the event timestamp seconds; this app's cursor has been carrying
     * deciseconds since the sync loop was written, because that is what the batches looked like.
     * One of those is wrong and the ring will say which the first time real events arrive with
     * known spacing — HRV events are five minutes apart, which is a ruler. Until then the wrong
     * choice would silently stretch or squash a whole night, so it is named, defaulted to the
     * documented value, and changeable in one place.
     */
    class Clock(
        private val anchorTicks: Long,
        private val anchorEpochMs: Long,
        private val ticksPerSecond: Int = TICKS_PER_SECOND,
    ) {
        fun epochMs(ticks: Long): Long =
            anchorEpochMs + ((ticks - anchorTicks) * 1_000L) / ticksPerSecond

        /** The reverse, for asking the ring for everything since a given moment. */
        fun ticks(epochMs: Long): Long =
            anchorTicks + ((epochMs - anchorEpochMs) * ticksPerSecond) / 1_000L
    }

    /** Documented: the event timestamp is in seconds. See [Clock] for why this is not assumed. */
    const val TICKS_PER_SECOND = 1

    /**
     * Split one notification into the frames packed inside it.
     *
     * Several events arrive in one notification, each `tag | length | payload`. A length that runs
     * past the end of the buffer stops the walk rather than throwing: a truncated notification is a
     * thing that happens, and the frames before the truncation are still good.
     */
    fun frames(bytes: ByteArray, ticksOf: (ByteArray) -> Long = ::timestampOf): List<Frame> {
        val out = mutableListOf<Frame>()
        var at = 0
        while (at + 2 <= bytes.size) {
            val tag = bytes[at].toInt() and 0xFF
            val length = bytes[at + 1].toInt() and 0xFF
            val from = at + 2
            val to = from + length
            if (to > bytes.size) break
            // Only events carry a timestamp, and only events are worth keeping here. Anything
            // below 0x41 is a reply to something this app asked, handled where it was asked.
            if (tag >= FIRST_EVENT_TAG && length >= 4) {
                val body = bytes.copyOfRange(from, to)
                out += Frame(tag, ticksOf(body), body.copyOfRange(4, body.size))
            }
            at = to
        }
        return out
    }

    /** The first four bytes of an event payload are its timestamp, little-endian. */
    fun timestampOf(payload: ByteArray): Long {
        if (payload.size < 4) return 0
        var value = 0L
        for (i in 3 downTo 0) value = (value shl 8) or (payload[i].toLong() and 0xFF)
        return value
    }

    /** Events start at 0x41; anything lower is a response to a request. */
    const val FIRST_EVENT_TAG = 0x41

    /**
     * What one frame says, if this app can say it.
     *
     * A frame can hold several readings — an inter-beat frame holds six intervals — so this returns
     * a list, and an empty list is never returned: a frame nobody can read comes back as
     * [Reading.Unread], because "we stored 4,000 events and understood 300" is a fact worth having
     * on a screen.
     */
    fun read(frame: Frame): List<Reading> = when (frame.tag) {
        TAG_IBI_AMPLITUDE -> beats(frame)
        TAG_GREEN_IBI -> greenBeats(frame)
        TAG_HRV -> hrv(frame)
        TAG_TEMP, TAG_TEMP_PERIOD, TAG_SLEEP_TEMP -> temp(frame)
        TAG_REAL_STEPS_1, TAG_REAL_STEPS_2 -> steps(frame)
        TAG_WEAR -> wear(frame)
        else -> listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
    }

    /**
     * `0x60` — six inter-beat intervals and a signal amplitude, bit-packed into 14 bytes.
     *
     * The layout is open_oura's, validated against a full night: pairs of bytes, the interval in
     * the top thirteen bits and the ring's quality in the next two. An interval outside the range a
     * heart produces is dropped rather than reported — a bad read is a plausible number in the
     * wrong place, and a screen that says 12 bpm teaches somebody to distrust the ones that are
     * right.
     */
    private fun beats(frame: Frame): List<Reading> {
        val out = mutableListOf<Reading>()
        var i = 0
        while (i + 1 < frame.payload.size) {
            val b0 = frame.payload[i].toInt() and 0xFF
            val b1 = frame.payload[i + 1].toInt() and 0xFF
            val interval = (b0 shl 3) or (b1 and 0x07)
            val quality = (b1 shr 3) and 0x03
            if (interval in PLAUSIBLE_INTERVAL) {
                out += Reading.Heart(frame.ticks, 60_000 / interval, interval, quality)
            }
            i += 2
        }
        return out.ifEmpty { listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size)) }
    }

    /** `0x80` — the daytime green-LED equivalent, same packing. */
    private fun greenBeats(frame: Frame) = beats(frame)

    /**
     * `0x5D` — pairs of (heart rate, RMSSD), one pair per five minutes.
     *
     * The five-minute spacing is what makes this the ruler for [Clock]: if consecutive HRV frames
     * are 300 apart the tick is a second, and if they are 3,000 apart it is a decisecond.
     */
    private fun hrv(frame: Frame): List<Reading> {
        val out = mutableListOf<Reading>()
        var i = 0
        while (i + 1 < frame.payload.size) {
            val bpm = frame.payload[i].toInt() and 0xFF
            val rmssd = frame.payload[i + 1].toInt() and 0xFF
            if (bpm in PLAUSIBLE_BPM) out += Reading.Hrv(frame.ticks, bpm, rmssd)
            i += 2
        }
        return out.ifEmpty { listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size)) }
    }

    /**
     * Temperature, **inferred**.
     *
     * Nobody has written down the scaling, and the value is known only by what it should read: a
     * worn ring sits around 33 °C and an asleep one nearer 35. So the candidates are tried in order
     * of how commonly a firmware would encode a body temperature, and the first that lands in the
     * range a finger can actually be is taken — hundredths of a degree, then sixty-fourths, then
     * whole degrees.
     *
     * A reading nothing plausible comes out of is [Reading.Unread] rather than a number. Guessing
     * is only defensible while it is labelled, and a number outside 15–45 °C is not a scaling
     * question, it is the wrong field.
     */
    private fun temp(frame: Frame): List<Reading> {
        if (frame.payload.size < 2) {
            return listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
        }
        val raw = (frame.payload[0].toInt() and 0xFF) or ((frame.payload[1].toInt() and 0xFF) shl 8)
        val signed = raw.toShort().toInt()
        val candidates = listOf(raw / 100.0, signed / 64.0, raw / 256.0, raw.toDouble())
        val celsius = candidates.firstOrNull { it in PLAUSIBLE_CELSIUS }
            ?: return listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
        return listOf(Reading.Temp(frame.ticks, celsius))
    }

    /**
     * Steps, **inferred**: a little-endian count in the first two bytes.
     *
     * Rejected above [MAX_STEPS_PER_EVENT] rather than clamped. These events arrive through the
     * day, so a value in the tens of thousands is not somebody who walked very far, it is the wrong
     * two bytes — and a step count that silently inflates is worse than no step count.
     */
    private fun steps(frame: Frame): List<Reading> {
        if (frame.payload.size < 2) {
            return listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
        }
        val count = (frame.payload[0].toInt() and 0xFF) or
            ((frame.payload[1].toInt() and 0xFF) shl 8)
        if (count > MAX_STEPS_PER_EVENT) {
            return listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
        }
        return listOf(Reading.Steps(frame.ticks, count))
    }

    /** `0x53` — on a finger or not. Zero is off; anything else is on. */
    private fun wear(frame: Frame): List<Reading> {
        if (frame.payload.isEmpty()) {
            return listOf(Reading.Unread(frame.ticks, frame.tag, frame.payload.size))
        }
        return listOf(Reading.Wear(frame.ticks, frame.payload[0].toInt() != 0))
    }

    private val PLAUSIBLE_INTERVAL = 300..2_000
    private val PLAUSIBLE_BPM = 25..220
    private val PLAUSIBLE_CELSIUS = 15.0..45.0
    private const val MAX_STEPS_PER_EVENT = 5_000

    const val TAG_STATE_CHANGE = 0x45
    const val TAG_TEMP = 0x46
    const val TAG_WEAR = 0x53
    const val TAG_HRV = 0x5D
    const val TAG_IBI_AMPLITUDE = 0x60
    const val TAG_TEMP_PERIOD = 0x69
    const val TAG_SLEEP_TEMP = 0x75
    const val TAG_REAL_STEPS_1 = 0x7E
    const val TAG_REAL_STEPS_2 = 0x7F
    const val TAG_GREEN_IBI = 0x80

    /**
     * The tag table, for the log screen.
     *
     * Named even where undecoded, because "0x4c, 118 of them" tells nobody anything and "Sleep
     * summary (2), 118 of them" says which decoder to write next.
     */
    fun name(tag: Int): String = NAMES[tag] ?: "Unknown 0x%02x".format(tag)

    private val NAMES: Map<Int, String> = mapOf(
        0x41 to "Ring start", 0x42 to "Time sync", 0x43 to "Debug",
        0x44 to "IBI", 0x45 to "State change", 0x46 to "Temperature",
        0x47 to "Motion", 0x48 to "Sleep period", 0x49 to "Sleep summary (1)",
        0x4A to "PPG amplitude", 0x4B to "Sleep phase", 0x4C to "Sleep summary (2)",
        0x4D to "Sleep feature info", 0x4E to "Sleep phase detail", 0x4F to "Sleep summary (3)",
        0x50 to "Activity", 0x51 to "Activity summary (1)", 0x52 to "Activity summary (2)",
        0x53 to "Wear", 0x54 to "Recovery summary", 0x55 to "Sleep heart rate",
        0x56 to "Alert", 0x57 to "Sleep feature info (2)", 0x58 to "Sleep summary (4)",
        0x59 to "EDA", 0x5A to "Sleep phase data", 0x5B to "BLE connection",
        0x5C to "User info", 0x5D to "HRV", 0x5E to "Self test",
        0x5F to "Raw accelerometer", 0x60 to "Beats and amplitude", 0x61 to "Debug data",
        0x62 to "On-demand measurement", 0x63 to "PPG peak", 0x64 to "Raw PPG",
        0x65 to "On-demand session", 0x66 to "On-demand motion", 0x67 to "Raw PPG summary",
        0x68 to "Raw PPG data", 0x69 to "Temperature period", 0x6A to "Sleep period (2)",
        0x6B to "Motion period", 0x6C to "Feature session", 0x6D to "Measurement quality",
        0x6E to "SpO2 beats", 0x6F to "SpO2", 0x70 to "SpO2 smoothed",
        0x71 to "Green beats", 0x72 to "Sleep motion period", 0x73 to "Heart trace",
        0x74 to "Heart motion intensity", 0x75 to "Sleep temperature", 0x76 to "Bedtime",
        0x77 to "SpO2 DC", 0x79 to "Self test data", 0x7A to "Tag",
        0x7E to "Real steps", 0x7F to "Real steps (2)", 0x80 to "Green beats and quality",
        0x81 to "CVA raw PPG", 0x82 to "Scan start", 0x83 to "Scan end",
    )
}
