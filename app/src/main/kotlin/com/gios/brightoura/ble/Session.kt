package com.gios.brightoura.ble

import android.content.Context
import com.gios.brightoura.data.Trace
import com.gios.brightoura.data.Vault

/**
 * A whole conversation with the ring: connect, authenticate, ask, hang up.
 *
 * Everything the app does with a ring is one of these, because the ring's authentication is
 * *session-scoped* — it is re-done on every connection, and forgetting that is how a client works
 * once and then answers `2f 02 2f 01` forever after.
 *
 * Each function here is deliberately a whole trip rather than a step: nothing holds a live
 * connection open across screens. A ring that is not being read should not be connected, both for
 * its battery and because a dropped link mid-screen is a state nobody wants to draw.
 */
class Session(private val context: Context, private val vault: Vault) {

    /**
     * What a ring will tell us before it trusts us.
     *
     * Firmware, serial and hardware id answer without authentication, which makes this the honest
     * first screen of setup: it proves the app can see *your* ring, and it works on a ring that is
     * still paired to Oura's app. Nothing here changes anything on the ring.
     */
    suspend fun probe(address: String, onProgress: (String) -> Unit = {}): Probe? {
        Trace.begin("probe $address")
        val step: (String) -> Unit = { line -> Trace.add(line); onProgress(line) }
        // Two attempts, and the second one re-scans first.
        //
        // An Oura ring advertises with a rotating private address, so the address a scan handed
        // over a minute ago can already be stale — and a connect to a stale address fails with
        // status 133, which reads as "the ring is not there". Looking again costs ten seconds and
        // turns the commonest failure on this phone into a retry nobody has to understand.
        // Plain link first — see [Ring.connect]. Subscribing is what asks for a bond, and on a
        // phone that cannot finish one it stalls the whole connection without asking the ring a
        // single question.
        val ring = Ring.connect(context, address, step, subscribe = false) ?: run {
            step("Looking for it again — the ring's address rotates")
            val again = Ring.scan(context, timeoutMs = 6_000L, onProgress = step)
                .rings
                .firstOrNull()
                ?: return null
            Ring.connect(context, again.address, step, subscribe = false)
        } ?: return null
        step("Reading what it will say")
        return try {
            val firmware = ring.ask(Protocol.firmware())
            val serial = ring.ask(Protocol.serial())
            val hardware = ring.ask(Protocol.hardware())
            // Battery is the tell for whether a key is already installed: it is auth-gated, so a
            // ring that answers `needs auth` has been keyed by something — Oura's app, or us.
            val battery = ring.ask(Protocol.battery())
            Probe(
                link = ring.capabilitiesLine(),
                gatt = ring.gattDump(),
                firmware = firmware?.let { text(it.payload) },
                serial = serial?.let { text(it.payload) },
                hardware = hardware?.let { text(it.payload) },
                keyed = battery != null && Protocol.needsAuth(battery),
                batteryPercent = battery
                    ?.takeIf { !Protocol.needsAuth(it) }
                    ?.payload
                    ?.firstOrNull()
                    ?.toInt()
                    ?.and(0xff),
                frames = listOfNotNull(firmware, serial, hardware, battery).map { it.hex() },
            )
        } finally {
            ring.close()
        }
    }

    /**
     * Adopt a factory-reset ring: install a key of our own and keep it.
     *
     * Only a reset ring accepts a key. On a ring that already has one this returns
     * [Pairing.AlreadyKeyed] rather than an error, because that is not a fault — it is the ordinary
     * state of a ring still onboarded to Oura's app, and the setup screen has something specific to
     * say about it.
     *
     * The measurement features are switched on straight afterwards, and that is not optional
     * politeness: a ring keyed by us has daytime HR and SpO2 **off**, because Oura's app is what
     * turns them on at onboarding. Skip this and the ring authenticates, syncs, and produces no
     * heart rate at all — the app would look broken while working perfectly.
     */
    suspend fun pair(address: String, name: String?, onProgress: (String) -> Unit = {}): Pairing {
        Trace.begin("pair $address")
        val step: (String) -> Unit = { line -> Trace.add(line); onProgress(line) }
        val ring = Ring.connect(context, address, step) ?: return Pairing.NoConnection
        return try {
            step("Installing a key")
            val key = Auth.newKey()
            val installed = ring.ask(Protocol.setAuthKey(key))
                ?: return Pairing.NoConnection
            // `25 01 00` is success. Anything else on this tag means the ring declined, and the
            // overwhelmingly likely reason is that it already holds a key.
            val ok = installed.tag == 0x25 && installed.payload.firstOrNull()?.toInt() == 0x00
            if (!ok) return Pairing.AlreadyKeyed
            step("Authenticating")
            if (!authenticate(ring, key)) return Pairing.AuthFailed
            if (!vault.store(key)) return Pairing.CouldNotStore
            vault.address = address
            vault.name = name
            step("Switching the measuring on")
            val enabled = enableMeasurement(ring)
            Pairing.Paired(featuresEnabled = enabled)
        } finally {
            ring.close()
        }
    }

    /**
     * Turn the measuring on. See [pair] for why this is load-bearing.
     *
     * Each feature is set independently and the result is a count rather than a boolean: a ring that
     * takes heart rate and refuses SpO2 is a working ring, and refusing to report that would be
     * hiding the only information available about why a value is missing later.
     */
    private suspend fun enableMeasurement(ring: Ring): Int {
        var on = 0
        for (feature in MEASUREMENT) {
            val reply = ring.ask(Protocol.setFeatureMode(feature, Protocol.Mode.AUTOMATIC))
            if (reply != null && !Protocol.needsAuth(reply)) on++
        }
        return on
    }

    /** The nonce dance. False on a wrong key, a silent ring, or a malformed challenge. */
    private suspend fun authenticate(ring: Ring, key: ByteArray): Boolean {
        val nonce = ring.ask(Protocol.authNonce())
        if (nonce == null) {
            Trace.add("no answer to the nonce request")
            return false
        }
        // `2f 10 2c <15 bytes>` — the challenge sits after the extended tag.
        if (nonce.tag != Protocol.EXT || nonce.ext != 0x2c) {
            Trace.add("unexpected answer to the nonce request: ${nonce.tag} / ${nonce.ext}")
            return false
        }
        val challenge = nonce.payload.drop(1).toByteArray()
        val proof = Auth.proof(key, challenge) ?: return false
        val answer = ring.ask(Protocol.authenticate(proof))
        if (answer == null) {
            Trace.add("no answer to the proof")
            return false
        }
        // `2f 02 2e 00` accepted, `...01` rejected.
        val ok = answer.ext == 0x2e && answer.payload.getOrNull(1)?.toInt() == 0x00
        Trace.add(if (ok) "authenticated" else "the ring rejected the key")
        return ok
    }

    /**
     * Open an authenticated session and hand it to [block].
     *
     * The one entry point for everything that needs the key, so there is a single place where "no
     * key yet", "key the ring no longer accepts" and "ring not here" are told apart — those are
     * three different sentences on a screen and one shrug in a log.
     */
    suspend fun <T> authenticated(
        onProgress: (String) -> Unit = {},
        block: suspend (Ring) -> T,
    ): Authenticated<T> {
        Trace.begin("authenticated session")
        val step: (String) -> Unit = { line -> Trace.add(line); onProgress(line) }
        val key = vault.key() ?: return Authenticated.NoKey()
        val address = vault.address ?: return Authenticated.NoRing()
        val ring = Ring.connect(context, address, step) ?: return Authenticated.NoRing()
        return try {
            step("Authenticating")
            if (!authenticate(ring, key)) {
                Authenticated.Rejected()
            } else {
                Authenticated.Ok(block(ring))
            }
        } finally {
            ring.close()
        }
    }

    /** ASCII out of a reply payload, for the fields that are text. */
    private fun text(payload: ByteArray): String =
        payload.filter { it >= 0x20 && it < 0x7f }.toByteArray().decodeToString().trim()

    /**
     * Whether the ring's advertised name says it has never been keyed.
     *
     * A ring straight out of a reset advertises `Oura <serial>`; once an app has installed a key it
     * renames itself to `Oura Ring Gen3` or similar. So the name is a free read of the one fact
     * that decides the whole setup — and it explains the encryption: a factory-reset ring insists
     * on a bonded link before it will carry a conversation.
     */
    fun looksUnkeyed(name: String?): Boolean {
        val text = name?.trim().orEmpty()
        if (!text.startsWith("Oura", ignoreCase = true)) return false
        val tail = text.removePrefix("Oura").trim()
        // A serial is a long run of digits and capitals with no spaces. "Ring Gen3" is not.
        return tail.length >= 8 && tail.none { it == ' ' }
    }

    /** What an unauthenticated look at a ring can tell. */
    data class Probe(
        /**
         * What the link itself managed: the characteristics' properties, and whether the push
         * channel was available.
         *
         * First field because it is the first question now. On a phone that cannot complete a BLE
         * bond, "not subscribed" is the whole story and every empty field below follows from it.
         */
        val link: String,
        /**
         * The whole GATT table.
         *
         * Kept because every idea left depends on what is actually in this ring, and nobody has
         * dumped a Ring 4. It goes into the diagnosis rather than onto the screen — it is thirty
         * lines of UUIDs, which is data for whoever is solving this and noise for anybody else.
         */
        val gatt: String,
        val firmware: String?,
        val serial: String?,
        val hardware: String?,
        /** True when the ring has a key already — Oura's app, or a previous pairing of ours. */
        val keyed: Boolean,
        val batteryPercent: Int?,
        /** The raw replies, for a report when something does not look right. */
        val frames: List<String>,
    )

    sealed interface Pairing {
        data class Paired(val featuresEnabled: Int) : Pairing
        data object AlreadyKeyed : Pairing
        data object AuthFailed : Pairing
        data object CouldNotStore : Pairing
        data object NoConnection : Pairing
    }

    sealed interface Authenticated<T> {
        data class Ok<T>(val value: T) : Authenticated<T>
        class NoKey<T> : Authenticated<T>
        class NoRing<T> : Authenticated<T>
        class Rejected<T> : Authenticated<T>
    }

    private companion object {
        /**
         * The features a diary needs, in the order they matter.
         *
         * Resting HR is on by default on a stock ring and off on one we keyed, which is exactly the
         * trap this list exists for.
         */
        val MEASUREMENT = listOf(
            Protocol.Feature.DAYTIME_HR,
            Protocol.Feature.RESTING_HR,
            Protocol.Feature.SPO2,
        )
    }
}
