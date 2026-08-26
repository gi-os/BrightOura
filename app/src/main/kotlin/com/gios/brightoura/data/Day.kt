package com.gios.brightoura.data

import com.gios.brightoura.data.Readings.Reading

/**
 * A day's worth of readings, reduced to the handful of numbers worth putting on a screen.
 *
 * Also free of Android, also for the same reason: this is arithmetic over a list, and arithmetic
 * over a list is the kind of thing that is quietly wrong for a month.
 *
 * ## What is deliberately not here
 *
 * A sleep score. Oura's own numbers are the output of a model this app does not have and cannot
 * honestly reproduce, and an invented score that disagrees with the official app by eleven points
 * is worse than no score: it looks like the same thing and is not. What is here is measurement —
 * beats, degrees, steps, and the hours the ring believed it was asleep — which is the part the
 * ring actually tells us.
 */
object Day {

    data class Summary(
        /** Local day this covers, as an epoch-day number. */
        val day: Long,
        /** Lowest sustained heart rate — the closest honest thing to a resting rate. */
        val restingBpm: Int?,
        val lowestBpm: Int?,
        val highestBpm: Int?,
        /** Average of the HRV frames, which the ring emits every five minutes. */
        val averageRmssdMs: Int?,
        /** Degrees away from this ring's own median, which is the only useful way to read it. */
        val tempDeviation: Double?,
        val steps: Int,
        /** Minutes the ring reported being worn. Everything else is only true while this is. */
        val wornMinutes: Int,
        /** How many frames went in, and how many of them nothing could read. */
        val readings: Int,
        val unread: Int,
    ) {
        val hasAnything: Boolean
            get() = restingBpm != null || tempDeviation != null || steps > 0 || wornMinutes > 0
    }

    /**
     * Fold readings into one day.
     *
     * [baseline] is the median finger temperature over the preceding fortnight, which is what makes
     * a temperature reading mean anything: 33.4 °C is not a fact anybody can use, and "0.4 above
     * your own normal" is the whole signal an illness shows up as. Null baseline gives a null
     * deviation rather than a deviation from nothing.
     */
    fun summarise(
        day: Long,
        readings: List<Reading>,
        clock: Readings.Clock,
        baseline: Double? = null,
    ): Summary {
        val beats = readings.filterIsInstance<Reading.Heart>()
        val hrvs = readings.filterIsInstance<Reading.Hrv>()
        val temps = readings.filterIsInstance<Reading.Temp>()
        val steps = readings.filterIsInstance<Reading.Steps>()
        val worn = readings.filterIsInstance<Reading.Wear>()

        // Every beat the ring reported, HRV frames included: those carry a rate of their own and
        // are the only heart data on a night when the amplitude frames were too noisy to keep.
        val allBpm = beats.map { it.bpm } + hrvs.map { it.bpm }

        return Summary(
            day = day,
            restingBpm = resting(allBpm),
            lowestBpm = allBpm.minOrNull(),
            highestBpm = allBpm.maxOrNull(),
            averageRmssdMs = hrvs.map { it.rmssdMs }.averageOrNull(),
            tempDeviation = baseline?.let { base ->
                median(temps.map { it.celsius })?.let { it - base }
            },
            // Summed, not maxed. These are counts *since the last event*, so a day is their total —
            // reading the largest one would report the busiest ten minutes as the whole day.
            steps = steps.sumOf { it.count },
            wornMinutes = wornMinutes(worn, clock),
            readings = readings.count { it !is Reading.Unread },
            unread = readings.count { it is Reading.Unread },
        )
    }

    /**
     * The lowest rate the ring saw for long enough to mean something.
     *
     * Not the minimum: a single 38 is a dropped beat or a bad contact, and reporting it as a
     * resting rate makes a person believe something about their heart that a sensor artefact told
     * them. The tenth percentile of everything measured is the standard trick and needs no model —
     * on a night's worth of beats it lands where a resting rate lands, and one wild reading cannot
     * move it.
     */
    private fun resting(bpm: List<Int>): Int? {
        if (bpm.size < MIN_BEATS_FOR_RESTING) return null
        val sorted = bpm.sorted()
        return sorted[(sorted.size * 10) / 100]
    }

    /**
     * Minutes worn, from the on/off events.
     *
     * Paired: an "on" opens a stretch and the next "off" closes it. A trailing "on" with no "off"
     * counts up to the last reading of the day rather than to midnight, because a ring being worn
     * now says nothing about the hours after the last thing it told us.
     */
    private fun wornMinutes(events: List<Reading.Wear>, clock: Readings.Clock): Int {
        if (events.isEmpty()) return 0
        val ordered = events.sortedBy { it.ticks }
        var total = 0L
        var since: Long? = null
        for (event in ordered) {
            val at = clock.epochMs(event.ticks)
            if (event.worn && since == null) {
                since = at
            } else if (!event.worn && since != null) {
                total += at - since
                since = null
            }
        }
        since?.let { total += clock.epochMs(ordered.last().ticks) - it }
        return (total / 60_000L).toInt()
    }

    /** A ring's own normal, over however many days there are. The baseline for [Summary]. */
    fun baseline(temperatures: List<Double>): Double? = median(temperatures)

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val sorted = values.sorted()
        val middle = sorted.size / 2
        return if (sorted.size % 2 == 1) {
            sorted[middle]
        } else {
            (sorted[middle - 1] + sorted[middle]) / 2
        }
    }

    private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else average().toInt()

    /**
     * Below this, "resting heart rate" is a number with nothing behind it.
     *
     * A ring worn for ten minutes produces a handful of beats, and a percentile over a handful is
     * just the smallest of them wearing a better name.
     */
    private const val MIN_BEATS_FOR_RESTING = 30
}
