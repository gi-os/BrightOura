package com.gios.brightoura.data

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reading the ring's data off the Mac bridge instead of off the ring.
 *
 * The ring will not complete a Bluetooth pairing on LightOS — its SMP handshake stalls the
 * phone's stack — so a Mac on the same network holds the ring (open_oura, bonded) and serves the
 * synced history as JSON. This fetches that, and hands it to the **same** [Readings] and [Day]
 * decoders the BLE path would have used: open_oura stores each event's body as the bytes after the
 * 4-byte timestamp, which is exactly what [Readings.Frame.payload] is, so the mapping is one to
 * one and nothing here re-implements a decoder.
 *
 * Free of Android on purpose, like the decoders it drives: it is `java.net` and arithmetic, and
 * the part that can be quietly wrong — turning ring ticks into days — is the part worth being able
 * to run on a desk.
 */
object MacSource {

    /** What the bridge knows, reduced to what a screen shows. */
    data class Snapshot(
        val serial: String?,
        val firmware: String?,
        val batteryPercent: Int?,
        val model: Tracker.Model,
        val frameCount: Int,
        /** True when the day boundaries were derived from capture time, not a ring time-sync. */
        val approximate: Boolean,
        val error: String?,
    ) {
        companion object {
            fun failed(message: String) =
                Snapshot(null, null, null, Tracker.Model(emptyList(), null), 0, false, message)
        }
    }

    /** How long to wait on the Mac before calling it unreachable. */
    private const val TIMEOUT_MS = 8_000

    /**
     * Fetch `<baseUrl>/data` and fold it into day summaries.
     *
     * Every failure comes back as [Snapshot.error] rather than an exception: this is called from a
     * screen, and "the Mac did not answer" is a sentence to show, not a crash to file.
     */
    fun fetch(baseUrl: String): Snapshot {
        val url = baseUrl.trim().trimEnd('/')
        if (url.isEmpty()) return Snapshot.failed("No Mac address set.")
        val body = runCatching { get("$url/data") }
            .getOrElse { return Snapshot.failed(reason(it)) }
        val json = runCatching { JSONObject(body) }
            .getOrElse { return Snapshot.failed("The Mac sent something that was not JSON.") }
        json.optString("error").takeIf { it.isNotEmpty() }
            ?.let { return Snapshot.failed("The Mac reported: $it") }

        val serial = json.optString("serial").ifEmpty { null }
        val firmware = json.optString("firmware").ifEmpty { null }
        val battery = if (json.isNull("battery")) null else json.optInt("battery")

        val framesJson = json.optJSONArray("frames")
        if (framesJson == null || framesJson.length() == 0) {
            return Snapshot(serial, firmware, battery, Tracker.Model(emptyList(), null), 0, false, null)
        }

        val frames = ArrayList<Readings.Frame>(framesJson.length())
        for (i in 0 until framesJson.length()) {
            val f = framesJson.getJSONObject(i)
            val tag = f.getInt("tag")
            val ticks = f.getLong("ticks")
            val payload = hexToBytes(f.optString("body"))
            frames += Readings.Frame(tag, ticks, payload)
        }

        val clockJson = json.optJSONObject("clock")
        val clock = clockJson?.let {
            Readings.Clock(
                anchorTicks = it.getLong("anchor_ticks"),
                anchorEpochMs = it.getLong("anchor_epoch_ms"),
                ticksPerSecond = it.optInt("ticks_per_second", Readings.TICKS_PER_SECOND),
            )
        } ?: Readings.Clock(0L, 0L)      // no anchor yet: ticks fall on epoch-day 0, one bucket
        val approximate = clockJson?.optBoolean("approximate", false) ?: true

        val readings = frames.flatMap { Readings.read(it) }
        val model = Tracker.build(readings, clock)
        return Snapshot(serial, firmware, battery, model, frames.size, approximate, null)
    }

    private fun get(spec: String): String {
        val conn = (URL(spec).openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            requestMethod = "GET"
        }
        try {
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (code !in 200..299) error("HTTP $code")
            return text
        } finally {
            conn.disconnect()
        }
    }

    private fun reason(t: Throwable): String = when (t) {
        is java.net.SocketTimeoutException -> "The Mac did not answer in time — is it on the network?"
        is java.net.ConnectException -> "Could not reach the Mac. Check the address and that the bridge is running."
        is java.net.UnknownHostException -> "That address does not resolve."
        else -> t.message ?: t.javaClass.simpleName
    }

    private fun hexToBytes(hex: String): ByteArray {
        val clean = hex.trim()
        val out = ByteArray(clean.length / 2)
        var i = 0
        while (i + 1 < clean.length + 1 && i + 2 <= clean.length) {
            out[i / 2] = ((clean[i].digitToInt(16) shl 4) or clean[i + 1].digitToInt(16)).toByte()
            i += 2
        }
        return out
    }
}
