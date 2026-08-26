package com.gios.brightoura.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gios.brightoura.ble.Companions
import com.gios.brightoura.ble.Ring
import com.gios.brightoura.ble.Session
import com.gios.brightoura.ble.Sync
import com.gios.brightoura.data.EventLog
import com.gios.brightoura.data.Failures
import com.gios.brightoura.data.Trace
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

    /**
     * What the app is doing right now, in its own words.
     *
     * The whole reason this exists: a BLE conversation takes ten to sixty seconds, most of it
     * waiting, and a screen that shows nothing during it is a screen somebody presses again. Every
     * step of every attempt lands here as it happens.
     */
    private val _stage = MutableStateFlow<String?>(null)
    val stage: StateFlow<String?> = _stage.asStateFlow()

    /** The breadcrumb trail, for the screen that shows what just happened. */
    private val _trail = MutableStateFlow<List<String>>(emptyList())
    val trail: StateFlow<List<String>> = _trail.asStateFlow()

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

    fun scan() = work("scan for the ring") {
        if (!Ring.bluetoothOn(getApplication())) {
            say("Bluetooth is off — turn it on and look again")
            return@work
        }
        Trace.begin("scan")
        val result = withContext(Dispatchers.IO) {
            Ring.scan(getApplication()) { line -> Trace.add(line); step(line) }
        }
        _found.value = result.rings
        say(
            result.note ?: "Found ${result.rings.size} ring${if (result.rings.size == 1) "" else "s"}",
        )
        if (result.rings.isEmpty()) {
            fail("find the ring", "saw ${result.otherDevices} other device(s); ${result.note}")
        }
    }

    /**
     * Look at a ring without touching it.
     *
     * The first thing setup does, and the one step that works on a ring still onboarded to Oura's
     * app: firmware, serial and hardware id all answer before authentication. If this shows your
     * serial, everything after it is a question of keys rather than of radios.
     */
    fun probe(found: Ring.Found) = work("probe the ring") {
        val result = withContext(Dispatchers.IO) { session.probe(found.address) { step(it) } }
        _probe.value = result
        say(
            when {
                result == null -> "Could not connect. This ring wants a paired link, and the " +
                    "phone's pairing request never draws on this screen — pair it once in the " +
                    "phone's own Bluetooth settings, then probe again."
                result.keyed -> "This ring already has a key — Oura's app has it, or a previous " +
                    "pairing here does."
                else -> "Ready to pair."
            },
        )
        if (result == null) fail("connect to the ring", "probe returned nothing")
    }

    /**
     * Adopt the ring.
     *
     * Only a factory-reset ring can be adopted, and the messages here are the whole reason this is
     * not a one-line call: "already keyed" is not a failure, it is the ordinary state of a ring that
     * still belongs to Oura's app, and it needs a different sentence than a radio problem does.
     */
    fun pair(found: Ring.Found) = work("pair with the ring") {
        val outcome = withContext(Dispatchers.IO) {
            session.pair(found.address, found.name) { step(it) }
        }
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
        if (outcome !is Session.Pairing.Paired) {
            fail("pair with the ring", outcome.javaClass.simpleName)
        }
        _probe.value = null
    }

    /** Drain history into the log. The point of the whole app, and the thing to watch first. */
    fun sync() = work("sync the ring's history") {
        val outcome = withContext(Dispatchers.IO) {
            session.authenticated(onProgress = { step(it) }) { ring ->
                Sync(vault, log).run(ring)
            }
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
        if (outcome !is Session.Authenticated.Ok) {
            fail("sync the ring's history", outcome.javaClass.simpleName)
        }
    }

    /** Battery, as a cheap proof that an authenticated session works at all. */
    fun battery() = work("read the battery") {
        val outcome = withContext(Dispatchers.IO) {
            session.authenticated(onProgress = { step(it) }) { ring ->
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

    /**
     * Whether a scan can even happen: Bluetooth on, and the permissions actually granted.
     *
     * Asked rather than assumed, because the two failures are indistinguishable from inside a scan
     * — a refused permission returns an empty result exactly like an empty room does.
     */
    fun bluetoothReady(): Boolean {
        val app = getApplication<Application>()
        if (!Ring.bluetoothOn(app)) return false
        val needed = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            listOf(
                android.Manifest.permission.BLUETOOTH_SCAN,
                android.Manifest.permission.BLUETOOTH_CONNECT,
            )
        } else {
            listOf(android.Manifest.permission.ACCESS_FINE_LOCATION)
        }
        return needed.all {
            app.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * Ask Android to pair with the ring, deliberately.
     *
     * Here because a bond is *usually* unnecessary and occasionally the whole problem, and because
     * "most rings pair with no prompt" is easier to believe when there is a button that says what
     * happened. The connect path asks for one by itself when the link says it needs encryption;
     * this is the manual version for a ring that will not say so.
     */
    fun bondWith(found: Ring.Found) = work("pair the Bluetooth link") {
        Trace.begin("bond ${found.address}")
        val ok = withContext(Dispatchers.IO) {
            Ring.bondWith(getApplication(), found.address) { line -> Trace.add(line); step(line) }
        }
        say(
            if (ok) {
                "The link is paired. Probe it now."
            } else {
                "Pairing did not complete. Many rings need no pairing at all — probe it anyway."
            },
        )
        if (!ok) fail("pair the Bluetooth link", "createBond did not reach BONDED")
    }

    /**
     * The system's own device picker, when it is ready to be shown.
     *
     * Held here rather than launched here: an `IntentSender` has to be started from an activity,
     * and the activity is watching this.
     */
    private val _picker = MutableStateFlow<android.content.IntentSender?>(null)
    val picker: StateFlow<android.content.IntentSender?> = _picker.asStateFlow()

    fun pickerShown() { _picker.value = null }

    /**
     * Ask the system to pair the ring for us.
     *
     * The path that works on a phone which never draws the pairing notification: the companion
     * flow's picker is an activity this app launches, and the watch profile hands the pairing to
     * the platform. See [com.gios.brightoura.ble.Companions].
     */
    fun pairViaSystem() {
        Trace.begin("companion pairing")
        _stage.value = "Asking the phone to find the ring"
        Companions.request(
            context = getApplication(),
            onProgress = { step(it) },
            onPicker = { sender ->
                _picker.value = sender
                say("Pick the ring in the phone's own dialog.")
            },
            onFailure = { reason ->
                say("The phone would not run its own picker: $reason")
                _stage.value = null
                viewModelScope.launch { fail("pair through the companion flow", reason) }
            },
        )
    }

    /**
     * Open the phone's Bluetooth settings.
     *
     * The reliable way to make a bond on a phone whose pairing notification is never drawn — and it
     * only has to be done once, because the bond outlives it.
     */
    fun openBluetoothSettings() {
        val ok = com.gios.brightoura.ble.Pairing.openSettings(getApplication()) { step(it) }
        say(
            if (ok) {
                "Pair the ring there, then come back and probe it."
            } else {
                "Nothing on this phone would open a Bluetooth screen."
            },
        )
    }

    /**
     * Everything the phone will say about the state of this, as one block of text.
     *
     * On the clipboard rather than in a report, because this app has no reporting key yet and the
     * fastest path from a stuck phone to a fix is a paste into a chat window. Adapter, permissions,
     * bond states, whether the system holds a companion association, and the last attempt's trail.
     */
    fun copyDiagnosis() {
        val app = getApplication<Application>()
        val text = buildString {
            appendLine("BrightOura diagnosis")
            appendLine("bluetooth on: ${Ring.bluetoothOn(app)}")
            appendLine("permissions ok: ${bluetoothReady()}")
            appendLine("companion association: ${Companions.associated(app)}")
            appendLine("ring stored: ${vault.name ?: "none"} ${vault.address ?: ""}")
            appendLine("key held: ${vault.key() != null}")
            appendLine("bonded devices:")
            Ring.bondedNames(app).forEach { appendLine("  $it") }
            appendLine()
            _probe.value?.others?.takeIf { it.isNotEmpty() }?.let { lines ->
                appendLine("channels that answered a read:")
                lines.forEach { appendLine("  $it") }
                appendLine()
            }
            _probe.value?.gatt?.takeIf { it.isNotBlank() }?.let {
                appendLine("gatt table:")
                appendLine(it.trimEnd())
                appendLine()
            }
            appendLine("last attempt:")
            appendLine(Trace.text())
        }
        runCatching {
            val clipboard = app.getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(android.content.ClipData.newPlainText("BrightOura", text))
        }
        say("Copied. Paste it anywhere — it is the whole state of this, in words.")
    }

    fun forget() {
        vault.forget()
        vault.address = null
        vault.name = null
        vault.cursorDeciseconds = 0
        _probe.value = null
        say("Key forgotten. The ring keeps it until it is reset again.")
    }

    /**
     * The shell commands worth running when the phone itself is the problem.
     *
     * There is **no supported adb command that pairs a device** — the platform exposes enabling,
     * disabling and making itself discoverable, and nothing that starts or confirms a bond. What
     * shell *can* do is say why one failed, which is the thing nobody has yet: whether the
     * pairing even reaches the security manager, and what the stack says when it gives up.
     *
     * On the clipboard rather than run from here: this app has no adb of its own, BrightControl
     * does, and a command somebody pastes deliberately is a command somebody can read first.
     */
    fun copyShellCommands() {
        val text = """
            # 1. What the Bluetooth stack thinks is happening. Run it, then try to pair, then run
            #    it again — the bond state of the ring is the line that matters.
            dumpsys bluetooth_manager | grep -A5 -i "bond\|20160C"

            # 2. The stack's own account of the failure. Start this, try to pair, stop it.
            logcat -b all -v time | grep -i "bluetooth\|smp\|bond\|pair"

            # 3. Which companion associations exist, since one was made for the ring.
            cmd companiondevice list 0

            # 4. Whether the phone will even talk about LE pairing.
            dumpsys bluetooth_manager | grep -i "le\|transport" | head -40
        """.trimIndent()
        runCatching {
            val clipboard = getApplication<Application>()
                .getSystemService(android.content.ClipboardManager::class.java)
            clipboard?.setPrimaryClip(
                android.content.ClipData.newPlainText("BrightOura shell", text),
            )
        }
        say("Copied. Run them from BrightControl's ADB screen — none of them change anything.")
    }

    /** Whether a ring's advertised name says it has never been keyed. See [Session.looksUnkeyed]. */
    fun looksUnkeyed(name: String?): Boolean = session.looksUnkeyed(name)

    /** How many failure reports are waiting, and whether this build can send them at all. */
    fun queuedReports(): Int = Failures.queued(getApplication())

    fun canSendReports(): Boolean = Failures.canSend()

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
    private fun work(what: String, block: suspend () -> Unit) = viewModelScope.launch {
        if (_busy.value) return@launch
        _busy.value = true
        _stage.value = "Starting"
        runCatching { block() }.onFailure { thrown ->
            say(thrown.message ?: thrown.javaClass.simpleName)
            fail(what, thrown.stackTraceToString().take(1200))
        }
        _busy.value = false
        _stage.value = null
        _trail.value = Trace.latest()
    }

    /** Say what is happening, now, on the screen. Called from the BLE layer as it goes. */
    private fun step(line: String) {
        _stage.value = line
        _trail.value = Trace.latest()
    }

    /**
     * File a failure, with the trail attached.
     *
     * Automatic rather than offered, and this app is the one place in the collection where that is
     * right: it talks to hardware whose protocol came from somebody else's reverse engineering,
     * against a ring generation nobody has tested it on. A connection that fails *is* the work,
     * and the trail explaining it is gone the moment the screen changes. See
     * [com.gios.brightoura.data.Failures] for what does and does not go in one.
     */
    private suspend fun fail(what: String, detail: String?) {
        runCatching { Failures.file(getApplication(), what, detail) }
        _trail.value = Trace.latest()
    }
}
