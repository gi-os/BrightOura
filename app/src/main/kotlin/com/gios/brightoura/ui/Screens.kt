package com.gios.brightoura.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.gios.light.common.hw.WheelScroll
import com.gios.light.common.theme.Dim
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What the ring has said, and what to do next.
 *
 * The whole of this release is a proof: that the protocol works on your ring, that the key is held,
 * and that history events arrive. So the screen shows counts rather than health — a sleep score
 * drawn from a decoder nobody has verified would be a made-up number, and this app is being built
 * in the other order on purpose.
 */
@Composable
fun TodayScreen(vm: RingViewModel, onSetup: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val said by vm.said.collectAsStateWithLifecycle()
    val counts by vm.counts.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    WheelScroll(listState)
    LaunchedEffect(Unit) { vm.refreshCounts() }

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            SectionLabel("RING")
            MenuRow(
                label = vm.ringName ?: "No ring paired",
                sub = vm.ringAddress ?: "Pair one to begin",
                detail = if (vm.paired) "PAIRED" else "SET UP",
                onClick = onSetup,
            )
            Rule()
        }

        item {
            SectionLabel("HISTORY")
            MenuRow(
                label = "Events kept",
                detail = "${counts.frames}",
                sub = if (counts.frames == 0) {
                    "Nothing yet. Sync after the ring has been worn a while."
                } else {
                    "%,d bytes across %d kinds".format(counts.bytes, counts.byTag.size)
                },
            )
            MenuRow(
                label = "Last sync",
                detail = when (val at = vm.lastSyncMs) {
                    0L -> "NEVER"
                    else -> CLOCK.format(Date(at))
                },
                sub = "Every frame is kept as bytes, so a better decoder can be run over the " +
                    "same history later.",
            )
            Rule()
        }

        item {
            SectionLabel("ASK THE RING")
            MenuRow(
                label = "Sync history",
                detail = if (busy) "…" else "RUN",
                sub = "Drains what the ring has recorded since the last run",
                onClick = { vm.sync() },
            )
            MenuRow(
                label = "Battery",
                detail = if (busy) "…" else "READ",
                sub = "A one-command check that an authenticated session works",
                onClick = { vm.battery() },
            )
            Rule()
        }

        if (counts.byTag.isNotEmpty()) {
            item {
                SectionLabel("WHAT IT IS PRODUCING")
                Text(
                    text = "Tag, then how many. The names come from the protocol notes; anything " +
                        "unnamed is kept anyway.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            items(counts.byTag.entries.sortedByDescending { it.value }) { (tag, count) ->
                MenuRow(
                    label = EventNames.of(tag),
                    detail = "$count",
                    sub = "0x%02x".format(tag),
                )
            }
        }

        item {
            said?.let { line ->
                Rule()
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/**
 * Finding a ring, looking at it, and adopting it.
 *
 * The order is deliberate and the middle step is the point: **probe before pairing**. A probe reads
 * firmware and serial without authenticating and without changing anything, on a ring that still
 * belongs to Oura's app — so it answers "can this app see my ring" before anybody is asked to
 * factory-reset anything.
 */
@Composable
fun SetupScreen(vm: RingViewModel, onDone: () -> Unit) {
    val busy by vm.busy.collectAsStateWithLifecycle()
    val said by vm.said.collectAsStateWithLifecycle()
    val found by vm.found.collectAsStateWithLifecycle()
    val probe by vm.probe.collectAsStateWithLifecycle()

    val listState = rememberLazyListState()
    WheelScroll(listState)

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            SectionLabel("STEP ONE · FIND IT")
            MenuRow(
                label = "Scan",
                detail = if (busy) "…" else "LOOK",
                sub = "Filtered on the ring's service, so the name it advertises does not matter",
                onClick = { vm.scan() },
            )
            Rule()
        }

        if (found.isNotEmpty()) {
            item { SectionLabel("STEP TWO · LOOK AT IT") }
            items(found) { ring ->
                MenuRow(
                    label = ring.name,
                    sub = "${ring.address} · ${ring.rssi} dBm",
                    detail = "PROBE",
                    onClick = { vm.probe(ring) },
                )
            }
            item { Rule() }
        }

        probe?.let { p ->
            item {
                SectionLabel("WHAT IT SAID")
                MenuRow(label = "Serial", detail = p.serial ?: "—")
                MenuRow(label = "Hardware", detail = p.hardware ?: "—")
                MenuRow(label = "Firmware", detail = p.firmware ?: "—")
                MenuRow(
                    label = "Already has a key",
                    detail = if (p.keyed) "YES" else "NO",
                    sub = if (p.keyed) {
                        "Reset the ring to adopt it here. That is what takes it off Oura's app."
                    } else {
                        "A factory-reset ring. It will take a key from this app."
                    },
                )
                p.batteryPercent?.let { MenuRow(label = "Battery", detail = "$it%") }
                Rule()

                SectionLabel("STEP THREE · ADOPT IT")
                Text(
                    text = "Pairing installs a key of ours and switches the ring's measuring on — " +
                        "a ring keyed outside Oura's app has heart rate and SpO2 off until " +
                        "something asks for them. Oura's app loses this ring until you re-onboard " +
                        "it there, which would take it back from us.",
                    style = MaterialTheme.typography.bodySmall,
                    color = Dim,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                )
                found.firstOrNull()?.let { ring ->
                    MenuRow(
                        label = "Pair this ring",
                        detail = if (busy) "…" else "PAIR",
                        sub = ring.name,
                        onClick = { vm.pair(ring) },
                    )
                }
                Rule()
            }
        }

        if (vm.paired) {
            item {
                SectionLabel("PAIRED")
                MenuRow(
                    label = "Forget the key",
                    detail = "CLEAR",
                    sub = "The ring keeps it until it is reset, so this is a one-way door on " +
                        "this phone only",
                    onClick = { vm.forget() },
                )
                MenuRow(label = "Done", detail = "BACK", onClick = onDone)
                Rule()
            }
        }

        item {
            said?.let { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Dim,
                    modifier = Modifier.fillMaxWidth().padding(16.dp),
                )
            }
        }
    }
}

/** The frames the log holds, newest first, for when something needs looking at by hand. */
@Composable
fun FramesScreen(vm: RingViewModel) {
    val listState = rememberLazyListState()
    WheelScroll(listState)
    val lines = vm.logTail()

    LazyColumn(Modifier.fillMaxSize(), state = listState) {
        item {
            SectionLabel("LAST FRAMES")
            Text(
                text = "Tag, the ring's own timestamp, then the payload. This is what a decoder " +
                    "will be written against.",
                style = MaterialTheme.typography.bodySmall,
                color = Dim,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        if (lines.isEmpty()) {
            item { EmptyState("Nothing synced yet.") }
        }
        items(lines) { line ->
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                color = Dim,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 3.dp),
            )
        }
        item {
            Rule()
            MenuRow(
                label = "Clear the log",
                detail = "WIPE",
                sub = "Only this phone's copy. The ring's own buffer is untouched.",
                onClick = { vm.clearLog() },
            )
        }
    }
}

private val CLOCK = SimpleDateFormat("d MMM HH:mm", Locale.US)
