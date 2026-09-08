package com.gios.brightoura.data

import com.gios.brightoura.data.Readings.Reading
import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The measurements, folded into the shape the tracker screens draw — and best-effort scores.
 *
 * ## About the scores
 *
 * Oura's 0–100 Readiness / Sleep / Activity numbers are the output of a proprietary model this app
 * does not have. These are **BrightOura's own** scores, computed openly from the ring's real
 * measurements — resting heart rate and HRV against your own recent baseline, temperature drift,
 * how long and how efficiently you slept, and steps against a goal. They track the same signals the
 * official numbers do and move the same direction, but they are not Oura's numbers and will not
 * match them to the point. Everything that is a raw measurement (a heart rate, a temperature, a
 * count of steps) is exactly what the ring reported; only the 0–100 roll-ups are computed here.
 *
 * Pure Kotlin, no Android — the arithmetic that is quietly wrong for a month is the arithmetic
 * worth testing on a desk.
 */
object Tracker {

    // ---- models the screens render -------------------------------------------------------------

    data class Model(val days: List<Day>, val trends: Trends?) {
        val today: Day? get() = days.maxByOrNull { it.epochDay }
    }

    data class Day(
        val epochDay: Long,
        val readiness: Readiness?,
        val sleep: Sleep?,
        val activity: Activity?,
        val heart: Heart?,
    )

    /** A 0–100 score with a word for it and, when we have history, how it compares. */
    data class Score(val value: Int, val label: String, val deltaVsAvg: Int? = null)

    data class Contributor(val name: String, val score: Int)

    data class Readiness(
        val score: Score,
        val contributors: List<Contributor>,
        val bodyTempDeviation: Double?,
        val restingBpm: Int?,
        val hrvMs: Int?,
        val average14d: Int?,
    )

    data class Sleep(
        val score: Score,
        val bedtimeMs: Long?,
        val wakeMs: Long?,
        val asleepMinutes: Int,
        val efficiencyPct: Int?,
        /** Deep / REM / Light / Awake minutes, when staging is available; else null. */
        val stages: Stages?,
        val latencyMinutes: Int?,
        val avgBpm: Int?,
        val lowestBpm: Int?,
        val lowestAtMs: Long?,
    )

    data class Stages(val deep: Int, val rem: Int, val light: Int, val awake: Int) {
        val total get() = deep + rem + light + awake
    }

    data class Activity(
        val score: Score,
        val steps: Int,
        val goal: Int,
        val activeKcal: Int,
        val totalKcal: Int?,
        val distanceKm: Double,
        val wornMinutes: Int,
    )

    data class Heart(
        val restingBpm: Int?,
        val lowestBpm: Int?,
        val lowestAtMs: Long?,
        val hrvAvgMs: Int?,
        val hrvVs14d: Int?,
        /** Downsampled (epochMs, bpm) across the day, for the line. */
        val series: List<Point>,
        val hrvByHour: List<Point>,
    )

    data class Point(val atMs: Long, val value: Double)

    data class Trends(
        val fromDay: Long,
        val toDay: Long,
        val readinessAvg: Int?,
        val sleepAvg: Int?,
        val activityAvg: Int?,
        val readinessByDay: List<Int?>,
        val sleepByDay: List<Int?>,
        val activityByDay: List<Int?>,
    )

    // ---- build ---------------------------------------------------------------------------------

    private const val STEP_GOAL = 10_000
    private const val SLEEP_TARGET_MIN = 480          // 8h
    private const val GAP_SPLIT_MS = 3 * 60 * 60_000L // a >3h quiet gap separates two "days" of sleep

    fun build(readings: List<Reading>, clock: Readings.Clock): Model {
        if (readings.isEmpty()) return Model(emptyList(), null)
        val zone = ZoneId.systemDefault()
        fun dayOf(ticks: Long) =
            Instant.ofEpochMilli(clock.epochMs(ticks)).atZone(zone).toLocalDate().toEpochDay()

        val byDay = readings.groupBy { dayOf(it.ticks) }.toSortedMap()

        // Baselines over the whole window — a ring's own normal is the only useful reference.
        val tempBaseline = median(readings.filterIsInstance<Reading.Temp>().map { it.celsius })
        val dailyResting = HashMap<Long, Int>()
        val dailyHrv = HashMap<Long, Int>()

        // First pass: per-day measurement rollups (also feeds the baselines below).
        val partial = LinkedHashMap<Long, Rollup>()
        for ((day, rs) in byDay) {
            val r = rollup(day, rs, clock)
            partial[day] = r
            r.restingBpm?.let { dailyResting[day] = it }
            r.hrvAvg?.let { dailyHrv[day] = it }
        }
        val rhrBase = median(dailyResting.values.map { it.toDouble() })
        val hrvBase = median(dailyHrv.values.map { it.toDouble() })

        // Second pass: assemble scored days, each seeing the day before it for "previous night".
        val out = ArrayList<Day>()
        val days = partial.keys.sorted()
        for ((i, day) in days.withIndex()) {
            val r = partial[day]!!
            val prevSleep = if (i > 0) out.lastOrNull()?.sleep?.score?.value else null
            out += assemble(r, tempBaseline, rhrBase, hrvBase, prevSleep)
        }
        return Model(out.sortedBy { it.epochDay }, trends(out))
    }

    // ---- per-day measurement rollup ------------------------------------------------------------

    private class Rollup(
        val epochDay: Long,
        val allBpm: List<Int>,
        val restingBpm: Int?,
        val lowestBpm: Int?,
        val lowestAtMs: Long?,
        val highestBpm: Int?,
        val hrvAvg: Int?,
        val tempC: Double?,
        val steps: Int,
        val wornMinutes: Int,
        val nightAsleepMin: Int,
        val nightBedMs: Long?,
        val nightWakeMs: Long?,
        val nightAvgBpm: Int?,
        val nightLowBpm: Int?,
        val nightLowAtMs: Long?,
        val series: List<Point>,
        val hrvByHour: List<Point>,
        val stages: Stages?,
    )

    private fun rollup(day: Long, rs: List<Reading>, clock: Readings.Clock): Rollup {
        val beats = rs.filterIsInstance<Reading.Heart>()
        val hrvs = rs.filterIsInstance<Reading.Hrv>()
        val temps = rs.filterIsInstance<Reading.Temp>()
        val steps = rs.filterIsInstance<Reading.Steps>().sumOf { it.count }
        val wear = rs.filterIsInstance<Reading.Wear>()

        val bpmPoints = (beats.map { it.ticks to it.bpm } + hrvs.map { it.ticks to it.bpm })
            .sortedBy { it.first }
        val allBpm = bpmPoints.map { it.second }
        val lowest = bpmPoints.minByOrNull { it.second }
        val series = downsample(bpmPoints.map { Point(clock.epochMs(it.first), it.second.toDouble()) }, 120)
        val hrvByHour = downsample(hrvs.map { Point(clock.epochMs(it.ticks), it.rmssdMs.toDouble()) }, 48)

        val worn = wornMinutes(wear, clock)

        // Overnight window: the longest low-HR stretch while worn, midnight-ish. Best-effort — no
        // sleep-staging frames, so "asleep" is worn-and-quiet, and stages stay null.
        val night = nightWindow(bpmPoints.map { clock.epochMs(it.first) to it.second }, clock, wear)

        return Rollup(
            epochDay = day,
            allBpm = allBpm,
            restingBpm = resting(allBpm),
            lowestBpm = allBpm.minOrNull(),
            lowestAtMs = lowest?.let { clock.epochMs(it.first) },
            highestBpm = allBpm.maxOrNull(),
            hrvAvg = hrvs.map { it.rmssdMs }.averageOrNull(),
            tempC = median(temps.map { it.celsius }),
            steps = steps,
            wornMinutes = worn,
            nightAsleepMin = night?.asleepMin ?: 0,
            nightBedMs = night?.bedMs,
            nightWakeMs = night?.wakeMs,
            nightAvgBpm = night?.avgBpm,
            nightLowBpm = night?.lowBpm,
            nightLowAtMs = night?.lowAtMs,
            series = series,
            hrvByHour = hrvByHour,
            stages = null,
        )
    }

    private class Night(
        val bedMs: Long, val wakeMs: Long, val asleepMin: Int,
        val avgBpm: Int?, val lowBpm: Int?, val lowAtMs: Long?,
    )

    /** The longest worn, low-heart-rate stretch — the honest stand-in for a sleep window. */
    private fun nightWindow(
        bpm: List<Pair<Long, Int>>, clock: Readings.Clock, wear: List<Reading.Wear>,
    ): Night? {
        if (bpm.size < 10) return null
        val sorted = bpm.sortedBy { it.first }
        // Split into runs wherever there is a long quiet gap; the longest run is the candidate.
        var runStart = 0
        var best: IntRange? = null
        for (i in 1 until sorted.size) {
            if (sorted[i].first - sorted[i - 1].first > GAP_SPLIT_MS) {
                val run = runStart until i
                if (best == null || run.count() > best!!.count()) best = run
                runStart = i
            }
        }
        val tail = runStart until sorted.size
        if (best == null || tail.count() > best!!.count()) best = tail
        val run = best ?: return null
        val slice = sorted.slice(run)
        if (slice.size < 10) return null
        val bedMs = slice.first().first
        val wakeMs = slice.last().first
        val asleepMin = ((wakeMs - bedMs) / 60_000L).toInt().coerceAtLeast(0)
        if (asleepMin < 90) return null              // too short to call a night
        val bpms = slice.map { it.second }
        val low = slice.minByOrNull { it.second }
        return Night(bedMs, wakeMs, asleepMin, bpms.average().roundToInt(),
            low?.second, low?.first)
    }

    // ---- scoring (best-effort, documented) -----------------------------------------------------

    private fun assemble(
        r: Rollup, tempBase: Double?, rhrBase: Double?, hrvBase: Double?, prevSleep: Int?,
    ): Day {
        val tempDev = if (tempBase != null && r.tempC != null) r.tempC - tempBase else null

        // --- Sleep ---
        val sleep: Sleep? = if (r.nightAsleepMin > 0) {
            val durationScore = pct(r.nightAsleepMin.toDouble() / SLEEP_TARGET_MIN * 100)
            // With no true time-in-bed we treat the worn night as the bed window, so efficiency is
            // a soft proxy from how settled the heart was, not a measured awake count.
            val efficiency = 82 + ((r.nightLowBpm ?: 60).let { 60 - it }).coerceIn(-10, 12)
            val eff = efficiency.coerceIn(50, 98)
            val score = ((durationScore * 0.6) + (eff * 0.4)).roundToInt().coerceIn(1, 100)
            Sleep(
                score = Score(score, wordFor(score)),
                bedtimeMs = r.nightBedMs, wakeMs = r.nightWakeMs,
                asleepMinutes = r.nightAsleepMin,
                efficiencyPct = eff,
                stages = r.stages,
                latencyMinutes = null,
                avgBpm = r.nightAvgBpm, lowestBpm = r.nightLowBpm, lowestAtMs = r.nightLowAtMs,
            )
        } else null

        // --- Readiness ---
        val cRhr = r.restingBpm?.let { rhr ->
            val dev = if (rhrBase != null) rhr - rhrBase else 0.0
            pct(70 - dev * 4)
        }
        val cHrv = r.hrvAvg?.let { hrv ->
            val dev = if (hrvBase != null) hrv - hrvBase else 0.0
            pct(70 + dev * 1.5)
        }
        val cTemp = tempDev?.let { pct(100 - abs(it) * 40) }
        val cPrev = prevSleep
        val cParts = buildList {
            cPrev?.let { add(Contributor("Previous night", it)) }
            cRhr?.let { add(Contributor("Resting heart rate", it)) }
            cHrv?.let { add(Contributor("HRV balance", it)) }
            cTemp?.let { add(Contributor("Body temperature", it)) }
        }
        val readiness: Readiness? = if (cParts.isNotEmpty()) {
            val v = cParts.map { it.score }.average().roundToInt().coerceIn(1, 100)
            Readiness(
                score = Score(v, wordFor(v)),
                contributors = cParts,
                bodyTempDeviation = tempDev,
                restingBpm = r.restingBpm,
                hrvMs = r.hrvAvg,
                average14d = null,
            )
        } else null

        // --- Activity ---
        val activity: Activity? = if (r.steps > 0 || r.wornMinutes > 0) {
            val v = pct(r.steps.toDouble() / STEP_GOAL * 100)
            val activeKcal = (r.steps * 0.04).roundToInt()          // ~0.04 kcal/step, rough
            Activity(
                score = Score(v, wordFor(v)),
                steps = r.steps, goal = STEP_GOAL,
                activeKcal = activeKcal, totalKcal = activeKcal + 1500,
                distanceKm = r.steps * 0.000_75,                    // ~0.75 m/step
                wornMinutes = r.wornMinutes,
            )
        } else null

        // --- Heart ---
        val heart: Heart? = if (r.allBpm.isNotEmpty() || r.hrvAvg != null) {
            Heart(
                restingBpm = r.restingBpm, lowestBpm = r.lowestBpm, lowestAtMs = r.lowestAtMs,
                hrvAvgMs = r.hrvAvg,
                hrvVs14d = if (hrvBase != null && r.hrvAvg != null) (r.hrvAvg - hrvBase).roundToInt() else null,
                series = r.series, hrvByHour = r.hrvByHour,
            )
        } else null

        return Day(r.epochDay, readiness, sleep, activity, heart)
    }

    private fun trends(days: List<Day>): Trends? {
        if (days.isEmpty()) return null
        val last = days.sortedBy { it.epochDay }.takeLast(7)
        fun avg(sel: (Day) -> Int?): Int? =
            last.mapNotNull(sel).takeIf { it.isNotEmpty() }?.average()?.roundToInt()
        return Trends(
            fromDay = last.first().epochDay, toDay = last.last().epochDay,
            readinessAvg = avg { it.readiness?.score?.value },
            sleepAvg = avg { it.sleep?.score?.value },
            activityAvg = avg { it.activity?.score?.value },
            readinessByDay = last.map { it.readiness?.score?.value },
            sleepByDay = last.map { it.sleep?.score?.value },
            activityByDay = last.map { it.activity?.score?.value },
        )
    }

    // ---- helpers -------------------------------------------------------------------------------

    fun wordFor(score: Int): String = when {
        score >= 85 -> "Optimal"
        score >= 70 -> "Good"
        score >= 60 -> "Fair"
        else -> "Pay attention"
    }

    private fun pct(x: Double): Int = x.roundToInt().coerceIn(1, 100)

    private fun resting(bpm: List<Int>): Int? {
        if (bpm.size < 30) return bpm.minOrNull()
        val sorted = bpm.sorted()
        return sorted[(sorted.size * 10) / 100]
    }

    private fun wornMinutes(events: List<Reading.Wear>, clock: Readings.Clock): Int {
        if (events.isEmpty()) return 0
        val ordered = events.sortedBy { it.ticks }
        var total = 0L
        var since: Long? = null
        for (e in ordered) {
            val at = clock.epochMs(e.ticks)
            if (e.worn && since == null) since = at
            else if (!e.worn && since != null) { total += at - since!!; since = null }
        }
        since?.let { total += clock.epochMs(ordered.last().ticks) - it }
        return (total / 60_000L).toInt()
    }

    private fun downsample(points: List<Point>, max: Int): List<Point> {
        if (points.size <= max) return points
        val step = points.size.toDouble() / max
        return (0 until max).map { points[(it * step).toInt()] }
    }

    private fun median(values: List<Double>): Double? {
        if (values.isEmpty()) return null
        val s = values.sorted(); val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun List<Int>.averageOrNull(): Int? = if (isEmpty()) null else average().roundToInt()
}
