package com.gios.brightoura.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightoura.ble.Ring
import com.gios.brightoura.ble.Session
import com.gios.brightoura.ble.Sync
import com.gios.brightoura.data.EventLog
import com.gios.brightoura.data.Vault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The whole app's state, which at this stage is: is there a ring, do we hold its key, and what has
 * it told us.
 *
 * Every operation is a whole trip to the ring and back — see [Session] — so this holds no
 * connection, only the last thing that happened. A screen that shows a live link is a screen that
 * has to draw a dropped one, and none of what this app does needs the ring to stay connected.
 */
class RingViewModel(app: Application) : AndroidViewModel(app) {

    private val vault = Vault(app)
    private val log = EventLog(app)
    private val session = Session(app, vault)

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _found = MutableStateFlow<List<Ring.Found>>(emptyList())
    val found: StateFlow<List<Ring.Found>> = _found.asStateFlow()

    private val _probe = MutableStateFlow<Session.Probe?>(null)
    val probe: StateFlow<Session.Probe?> = _probe.asStateFlow()

    private val _said = MutableStateFlow<String?>(null)
    val said: StateFlow<String?> = _said.asStateFlow()

    private val _counts = MutableStateFlow(log.counts())
    val counts: StateFlow<EventLog.Counts> = _counts.asStateFlow()

    val paired: Boolean get() = vault.key() != null
    val ringName: String? get() = vault.name
    val ringAddress: String? get() = vault.address
    val lastSyncMs: Long get() = vault.lastSyncMs

    fun say(text: String?) { _said.value = text }

    fun scan() = work {
        if (!Ring.bluetoothOn(getApplication())) {
            say("Bluetooth is off")
            return@work
        }
        _found.value = withContext(Dispatchers.IO) { Ring.scan(getApplication()) }
        say(if (_found.value.isEmpty()) "No rings answered. Wear it, or put it on the charger." else null)
    }

    /**
     * Look at a ring without touching it.
     *
     * The first thing setup does, and the one step that works on a ring still onboarded to Oura's
     * app: firmware, serial and hardware id all answer before authentication. If this shows your
     * serial, everything after it is a question of keys rather than of radios.
     */
    fun probe(found: Ring.Found) = work {
        val result = withContext(Dispatchers.IO) { session.probe(found.address) }
        _probe.value = result
        say(
            when {
                result == null -> "Could not connect. If the ring was just reset, accept the " +
                    "Bluetooth pairing prompt first."
                result.keyed -> "This ring already has a key — Oura's app has it, or a previous " +
                    "pairing here does."
                else -> "Ready to pair."
            },
        )
    }

    /**
     * Adopt the ring.
     *
     * Only a factory-reset ring can be adopted, and the messages here are the whole reason this is
     * not a one-line call: "already keyed" is not a failure, it is the ordinary state of a ring that
     * still belongs to Oura's app, and it needs a different sentence than a radio problem does.
     */
    fun pair(found: Ring.Found) = work {
        val outcome = withContext(Dispatchers.IO) { session.pair(found.address, found.name) }
        say(
            when (outcome) {
                is Session.Pairing.Paired -> if (outcome.featuresEnabled >= 3) {
                    "Paired, and measuring. Give it a few minutes before the first sync."
                } else {
                    "Paired. ${outcome.featuresEnabled} of 3 measurement features answered — the " +
                        "rest can be retried from here."
                }
                Session.Pairing.AlreadyKeyed ->
                    "This ring still holds another key. Factory-reset it and try again."
                Session.Pairing.AuthFailed ->
                    "The key installed and then would not authenticate. Try once more."
                Session.Pairing.CouldNotStore ->
                    "Paired, but this phone would not store the key. Nothing was kept."
                Session.Pairing.NoConnection -> "Lost the ring mid-pairing."
            },
        )
        _probe.value = null
    }

    /** Drain history into the log. The point of the whole app, and the thing to watch first. */
    fun sync() = work {
        val outcome = withContext(Dispatchers.IO) {
            session.authenticated { ring -> Sync(vault, log).run(ring) }
        }
        say(
            when (outcome) {
                is Session.Authenticated.Ok -> {
                    val r = outcome.value
                    _counts.value = log.counts()
                    "${r.events} events in ${r.batches} batches · ${r.stoppedBecause}"
                }
                is Session.Authenticated.NoKey -> "No ring paired yet."
                is Session.Authenticated.NoRing -> "The ring did not answer."
                is Session.Authenticated.Rejected ->
                    "The ring refused our key. It has been re-onboarded somewhere else."
            },
        )
    }

    /** Battery, as a cheap proof that an authenticated session works at all. */
    fun battery() = work {
        val outcome = withContext(Dispatchers.IO) {
            session.authenticated { ring ->
                ring.ask(com.gios.brightoura.ble.Protocol.battery())
                    ?.payload
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xff)
            }
        }
        say(
            when (outcome) {
                is Session.Authenticated.Ok ->
                    outcome.value?.let { "Battery $it%" } ?: "The ring said nothing about battery."
                is Session.Authenticated.NoKey -> "No ring paired yet."
                is Session.Authenticated.NoRing -> "The ring did not answer."
                is Session.Authenticated.Rejected -> "The ring refused our key."
            },
        )
    }

    fun forget() {
        vault.forget()
        vault.address = null
        vault.name = null
        vault.cursorDeciseconds = 0
        _probe.value = null
        say("Key forgotten. The ring keeps it until it is reset again.")
    }

    fun refreshCounts() {
        _counts.value = log.counts()
    }

    fun logTail(): List<String> = log.tail()

    fun clearLog() {
        log.clear()
        refreshCounts()
        say("Log cleared. The ring's own buffer is untouched.")
    }

    /**
     * One thing at a time.
     *
     * Not a nicety: two overlapping GATT conversations with the same ring produce a connection that
     * fails in a way neither caller can explain.
     */
    private fun work(block: suspend () -> Unit) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        runCatching { block() }.onFailure { say(it.message ?: it.javaClass.simpleName) }
        _busy.value = false
    }
}
