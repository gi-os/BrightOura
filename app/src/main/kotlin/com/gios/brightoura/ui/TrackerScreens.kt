package com.gios.brightoura.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gios.brightoura.data.Tracker
import com.gios.brightoura.ui.theme.Ink
import com.gios.light.common.hw.WheelTurns
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.max

private val DOW = DateTimeFormatter.ofPattern("EEE d MMM").withZone(ZoneId.systemDefault())
private val HM = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneId.systemDefault())

/**
 * The tracker — six screens the wheel or a swipe moves between, in the Oura idiom rendered for a
 * matte monochrome panel. Today, then a screen each for Sleep, Readiness, Activity, Heart, and a
 * week of Trends.
 */
@Composable
fun TrackerPager(vm: RingViewModel, snap: com.gios.brightoura.data.MacSource.Snapshot) {
    val model = snap.model
    val pages = 6
    val state = rememberPagerState(pageCount = { pages })
    val scope = rememberCoroutineScope()

    // The wheel turns pages, the way it would scroll a list — one notch, one screen.
    WheelTurns(armed = true) { n ->
        val next = (state.currentPage + if (n > 0) 1 else -1).coerceIn(0, pages - 1)
        if (next != state.currentPage) scope.launch { state.animateScrollToPage(next) }
    }

    Column(Modifier.fillMaxSize().background(Ink.Bg)) {
        HorizontalPager(state = state, modifier = Modifier.weight(1f)) { page ->
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 18.dp),
            ) {
                when (page) {
                    0 -> TodayPage(model, snap)
                    1 -> SleepPage(model.today?.sleep)
                    2 -> ReadinessPage(model.today?.readiness)
                    3 -> ActivityPage(model.today?.activity)
                    4 -> HeartPage(model.today?.heart)
                    else -> TrendsPage(model.trends)
                }
            }
        }
        Dots(pages, state.currentPage)
    }
}

// ---- pages -------------------------------------------------------------------------------------

@Composable
private fun TodayPage(model: Tracker.Model, snap: com.gios.brightoura.data.MacSource.Snapshot) {
    val day = model.today
    HeaderRow(
        left = day?.let { DOW.format(Instant.ofEpochMilli(dayMs(it.epochDay))) } ?: "Today",
        right = snap.serial?.let { "OURA" } ?: "",
    )
    Spacer(Modifier.height(18.dp))
    if (day == null) {
        Empty(
            if (snap.frameCount == 0)
                "No ring history yet. Wear the ring near the Mac and let it sync."
            else "Reading the ring…",
        )
        return
    }
    ScoreBlock("READINESS", day.readiness?.score, day.readiness?.let { wordSub(it.score) })
    Divider()
    ScoreBlock("SLEEP", day.sleep?.score, day.sleep?.let { hm(it.asleepMinutes) + " asleep" })
    Divider()
    ScoreBlock("ACTIVITY", day.activity?.score, day.activity?.let { "%,d steps".format(it.steps) })
    Spacer(Modifier.height(20.dp))
    Text(
        "Ring ${snap.batteryPercent?.let { "$it%" } ?: "—"} · synced just now",
        style = MaterialTheme.typography.bodySmall, color = Ink.Faint,
    )
}

@Composable
private fun SleepPage(s: Tracker.Sleep?) {
    Cap("SLEEP")
    if (s == null) { Spacer(Modifier.height(10.dp)); Empty("No sleep recorded yet."); return }
    Spacer(Modifier.height(6.dp))
    Text(
        (s.bedtimeMs?.let { HM.format(Instant.ofEpochMilli(it)) } ?: "—") + "  →  " +
            (s.wakeMs?.let { HM.format(Instant.ofEpochMilli(it)) } ?: "—"),
        style = MaterialTheme.typography.titleLarge, color = Ink.Soft,
    )
    Spacer(Modifier.height(10.dp))
    Hero(s.score.value, s.score.label, hm(s.asleepMinutes) + " asleep")
    Spacer(Modifier.height(18.dp))
    if (s.stages != null) {
        StageBar(s.stages)
        Spacer(Modifier.height(16.dp))
    }
    Metric("Total sleep", hm(s.asleepMinutes))
    Metric("Efficiency", s.efficiencyPct?.let { "$it%" }, s.efficiencyPct?.div(100f))
    Metric("Average HR", s.avgBpm?.let { "$it bpm" })
    Metric("Lowest HR", s.lowestBpm?.let { "$it bpm" +
        (s.lowestAtMs?.let { t -> " · " + HM.format(Instant.ofEpochMilli(t)) } ?: "") })
    if (s.stages == null) Note("Sleep staging needs frames this ring hasn't sent yet; the window and heart rate above are measured.")
}

@Composable
private fun ReadinessPage(r: Tracker.Readiness?) {
    Cap("READINESS")
    if (r == null) { Spacer(Modifier.height(10.dp)); Empty("Not enough measured yet for readiness."); return }
    Spacer(Modifier.height(6.dp))
    Hero(r.score.value, r.score.label, null)
    Spacer(Modifier.height(18.dp))
    r.contributors.forEach { ContributorRow(it.name, it.score) }
    Spacer(Modifier.height(8.dp))
    Metric("Body temperature", r.bodyTempDeviation?.let { signed2(it) + " °C" })
    Metric("Resting heart rate", r.restingBpm?.let { "$it bpm" })
    Metric("HRV", r.hrvMs?.let { "$it ms" })
    Note("BrightOura's own score, computed openly from your measurements — not Oura's number.")
}

@Composable
private fun ActivityPage(a: Tracker.Activity?) {
    Cap("ACTIVITY")
    if (a == null) { Spacer(Modifier.height(10.dp)); Empty("No movement recorded yet today."); return }
    Spacer(Modifier.height(6.dp))
    Hero(a.score.value, a.score.label, "%,d of %,d steps".format(a.steps, a.goal))
    Spacer(Modifier.height(10.dp))
    Bar((a.steps.toFloat() / a.goal).coerceIn(0f, 1f), Modifier.fillMaxWidth())
    Spacer(Modifier.height(18.dp))
    Metric("Active calories", "%,d kcal".format(a.activeKcal))
    Metric("Total burn", a.totalKcal?.let { "%,d kcal".format(it) })
    Metric("Distance", "%.1f km".format(a.distanceKm))
    Metric("Worn", hm(a.wornMinutes))
    Note("Calories and distance are estimated from step count.")
}

@Composable
private fun HeartPage(h: Tracker.Heart?) {
    Cap("HEART RATE + HRV")
    if (h == null) { Spacer(Modifier.height(10.dp)); Empty("No heart data yet."); return }
    Spacer(Modifier.height(10.dp))
    Row(Modifier.fillMaxWidth()) {
        StatBig("RESTING", h.restingBpm?.let { "$it" } ?: "—", "bpm", Modifier.weight(1f))
        StatBig("HRV AVG", h.hrvAvgMs?.let { "$it" } ?: "—", "ms", Modifier.weight(1f))
    }
    Spacer(Modifier.height(16.dp))
    if (h.series.size >= 2) {
        Cap("HEART RATE")
        Spacer(Modifier.height(6.dp))
        Sparkline(h.series.map { it.value }, Modifier.fillMaxWidth().height(72.dp))
        Spacer(Modifier.height(14.dp))
    }
    Metric("Lowest heart rate", h.lowestBpm?.let { "$it bpm" +
        (h.lowestAtMs?.let { t -> " · " + HM.format(Instant.ofEpochMilli(t)) } ?: "") })
    Metric("HRV vs 14-day", h.hrvVs14d?.let { (if (it >= 0) "+" else "") + "$it ms" })
}

@Composable
private fun TrendsPage(t: Tracker.Trends?) {
    Cap("TRENDS")
    if (t == null) { Spacer(Modifier.height(10.dp)); Empty("A few days of wear and the week fills in."); return }
    Spacer(Modifier.height(6.dp))
    Text(
        DOW.format(Instant.ofEpochMilli(dayMs(t.fromDay))) + "  →  " +
            DOW.format(Instant.ofEpochMilli(dayMs(t.toDay))),
        style = MaterialTheme.typography.titleLarge, color = Ink.Soft,
    )
    Spacer(Modifier.height(16.dp))
    TrendRow("READINESS", t.readinessAvg, t.readinessByDay)
    TrendRow("SLEEP", t.sleepAvg, t.sleepByDay)
    TrendRow("ACTIVITY", t.activityAvg, t.activityByDay)
}

// ---- components --------------------------------------------------------------------------------

@Composable private fun Cap(text: String) =
    Text(text, style = MaterialTheme.typography.labelLarge, color = Ink.Faint)

@Composable
private fun HeaderRow(left: String, right: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(left, style = MaterialTheme.typography.titleLarge, color = Ink.Soft)
        Text(right, style = MaterialTheme.typography.labelLarge, color = Ink.Faint)
    }
}

@Composable
private fun Hero(value: Int?, word: String?, sub: String?) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(
            value?.toString() ?: "—",
            style = MaterialTheme.typography.displayLarge, color = Ink.Near,
        )
        if (word != null) {
            Spacer(Modifier.width(12.dp))
            Text(
                word, style = MaterialTheme.typography.headlineMedium, color = Ink.Soft,
                modifier = Modifier.padding(bottom = 14.dp),
            )
        }
    }
    if (sub != null) Text(sub, style = MaterialTheme.typography.bodyLarge, color = Ink.Dim)
}

/** A labelled score block for the Today screen: cap, big number, sub, thin bar. */
@Composable
private fun ScoreBlock(label: String, score: Tracker.Score?, sub: String?) {
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Cap(label)
            if (sub != null) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ink.Dim)
        }
        Spacer(Modifier.height(2.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(score?.value?.toString() ?: "—",
                style = MaterialTheme.typography.displayMedium, color = Ink.Near)
            score?.label?.let {
                Spacer(Modifier.width(10.dp))
                Text(it, style = MaterialTheme.typography.titleLarge, color = Ink.Soft,
                    modifier = Modifier.padding(bottom = 10.dp))
            }
        }
        Spacer(Modifier.height(8.dp))
        Bar(((score?.value ?: 0) / 100f), Modifier.fillMaxWidth())
    }
}

@Composable
private fun StatBig(label: String, value: String, unit: String, modifier: Modifier = Modifier) {
    Column(modifier) {
        Cap(label)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, style = MaterialTheme.typography.displaySmall, color = Ink.Near)
            Spacer(Modifier.width(4.dp))
            Text(unit, style = MaterialTheme.typography.bodyMedium, color = Ink.Dim,
                modifier = Modifier.padding(bottom = 6.dp))
        }
    }
}

@Composable
private fun Metric(label: String, value: String?, fraction: Float? = null) {
    Column(Modifier.fillMaxWidth().padding(vertical = 7.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = Ink.Dim)
            Text(value ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink.Near)
        }
        if (fraction != null) { Spacer(Modifier.height(6.dp)); Bar(fraction, Modifier.fillMaxWidth()) }
    }
}

@Composable
private fun ContributorRow(name: String, score: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = MaterialTheme.typography.bodyMedium, color = Ink.Dim,
            modifier = Modifier.weight(1f))
        Box(Modifier.weight(1f)) { Bar(score / 100f, Modifier.fillMaxWidth()) }
        Spacer(Modifier.width(12.dp))
        Text("$score", style = MaterialTheme.typography.bodyMedium, color = Ink.Near)
    }
}

@Composable
private fun TrendRow(label: String, avg: Int?, byDay: List<Int?>) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Cap(label)
            Text(avg?.let { "avg $it" } ?: "—", style = MaterialTheme.typography.bodyMedium, color = Ink.Dim)
        }
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth().height(48.dp), horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.Bottom) {
            byDay.forEach { v ->
                val f = ((v ?: 0) / 100f).coerceIn(0.02f, 1f)
                Box(Modifier.weight(1f).fillMaxHeight(f).clip(CircleShape).background(if (v == null) Ink.Rule else Ink.Soft))
            }
        }
    }
}

/** A thin progress bar: dim track, bright fill. */
@Composable
private fun Bar(fraction: Float, modifier: Modifier = Modifier) {
    Box(modifier.height(3.dp).clip(CircleShape).background(Ink.Rule)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(3.dp)
            .clip(CircleShape).background(Ink.Soft))
    }
}

/** The sleep stage bar: deep / rem / light / awake, widths by minutes. */
@Composable
private fun StageBar(s: Tracker.Stages) {
    val total = max(1, s.total)
    Column {
        Row(Modifier.fillMaxWidth().height(16.dp).clip(CircleShape)) {
            Seg(s.deep, total, Ink.Near); Seg(s.rem, total, Ink.Soft)
            Seg(s.light, total, Ink.Mid); Seg(s.awake, total, Ink.Rule)
        }
        Spacer(Modifier.height(6.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("Deep" to s.deep, "REM" to s.rem, "Light" to s.light, "Awake" to s.awake).forEach { (n, m) ->
                Text("$n ${hm(m)}", style = MaterialTheme.typography.bodySmall, color = Ink.Faint)
            }
        }
    }
}

@Composable private fun androidx.compose.foundation.layout.RowScope.Seg(minutes: Int, total: Int, color: androidx.compose.ui.graphics.Color) {
    if (minutes <= 0) return
    Box(Modifier.weight(minutes.toFloat() / total).fillMaxSize().background(color))
}

/** A bare line chart of a value series. */
@Composable
private fun Sparkline(values: List<Double>, modifier: Modifier) {
    if (values.size < 2) return
    val lo = values.min(); val hi = values.max(); val span = (hi - lo).takeIf { it > 0 } ?: 1.0
    Canvas(modifier) {
        val w = size.width; val h = size.height
        val dx = w / (values.size - 1)
        var prev = Offset(0f, (h - ((values[0] - lo) / span * h)).toFloat())
        for (i in 1 until values.size) {
            val p = Offset(i * dx, (h - ((values[i] - lo) / span * h)).toFloat())
            drawLine(Ink.Soft, prev, p, strokeWidth = 2f, cap = StrokeCap.Round)
            prev = p
        }
    }
}

@Composable
private fun Dots(count: Int, current: Int) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp),
        horizontalArrangement = Arrangement.Center) {
        repeat(count) { i ->
            Box(Modifier.padding(horizontal = 4.dp).size(if (i == current) 7.dp else 5.dp)
                .clip(CircleShape).background(if (i == current) Ink.Near else Ink.Rule))
        }
    }
}

@Composable private fun Divider() {
    Spacer(Modifier.height(6.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(Ink.Rule))
    Spacer(Modifier.height(6.dp))
}

@Composable private fun Empty(msg: String) {
    Box(Modifier.fillMaxWidth().padding(vertical = 40.dp), Alignment.Center) {
        Text(msg, style = MaterialTheme.typography.bodyLarge, color = Ink.Dim, textAlign = TextAlign.Center)
    }
}

@Composable private fun Note(msg: String) {
    Spacer(Modifier.height(14.dp))
    Text(msg, style = MaterialTheme.typography.bodySmall, color = Ink.Faint)
}

// ---- helpers -----------------------------------------------------------------------------------

private fun dayMs(epochDay: Long): Long =
    LocalDate.ofEpochDay(epochDay).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()

private fun hm(minutes: Int): String = "${minutes / 60}h ${minutes % 60}m"
private fun wordSub(score: Tracker.Score): String = score.label
private fun signed2(v: Double): String = (if (v >= 0) "+" else "") + "%.2f".format(v)
