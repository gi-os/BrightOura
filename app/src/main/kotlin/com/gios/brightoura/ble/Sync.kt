package com.gios.brightoura.ble

import com.gios.brightoura.data.EventLog
import com.gios.brightoura.data.Vault

/**
 * Draining the ring's history into a log on the phone.
 *
 * The ring keeps events in a ring buffer with a clock of its own, in deciseconds. `GetEvent` asks
 * for up to a few dozen from a cursor; the ring answers with event frames and then a summary frame
 * saying how many it sent and how much is left. So a sync is a loop, and the loop's exit condition
 * is the ring saying "nothing more" rather than a count we guessed.
 *
 * ### Everything is kept, decoded or not
 *
 * Frames go into [EventLog] as bytes with their tag and their timestamp, whether or not this app
 * understands them yet. That is the difference between a decoder we can improve and a sync we have
 * to run again: the ring's buffer is finite and yesterday's events are gone from it, so anything
 * dropped here is dropped for good. Decoding is a pass over the log afterwards, and can be
 * rewritten as often as we like.
 *
 * ### The cursor is the ring's, not ours
 *
 * Stored in the ring's deciseconds exactly as the ring reported them. Converting through the
 * phone's clock — which drifts, changes zone, and is wrong for a minute after every boot — is how a
 * sync silently re-reads a week or skips one.
 */
class Sync(private val vault: Vault, private val log: EventLog) {

    data class Result(
        val events: Int,
        val batches: Int,
        val bytes: Int,
        /** Tag → how many, for the screen that says what the ring is actually producing. */
        val byTag: Map<Int, Int>,
        val stoppedBecause: String,
    )

    /**
     * Drain from the stored cursor until the ring runs out or [maxBatches] is reached.
     *
     * The cap is not tidiness: a first sync on a ring nobody has read in a month is thousands of
     * frames, and a loop with no ceiling on a screen somebody is watching is a screen that looks
     * hung. What it does not do is lose anything — the cursor moves as it goes, so the next run
     * carries on from where this one stopped.
     */
    suspend fun run(ring: Ring, maxBatches: Int = 40): Result {
        var events = 0
        var batches = 0
        var bytes = 0
        val byTag = HashMap<Int, Int>()
        var cursor = vault.cursorDeciseconds
        var reason = "nothing left"

        while (batches < maxBatches) {
            ring.send(Protocol.getEvents(cursor, BATCH))
            var got = 0
            var done = false
            while (true) {
                val packet = ring.next() ?: run {
                    reason = "the ring stopped answering"
                    done = true
                    null
                } ?: break
                if (packet.isEvent) {
                    val stamp = timestamp(packet.payload)
                    log.append(packet.tag, stamp, packet.payload)
                    byTag[packet.tag] = (byTag[packet.tag] ?: 0) + 1
                    events++
                    got++
                    bytes += packet.payload.size
                    // The cursor follows the newest event seen, plus one tick so the next request
                    // does not fetch the same frame again forever.
                    if (stamp > 0) cursor = stamp + 1
                    continue
                }
                // The batch summary: `11 08 <count> <bytes left>`. Its shape is documented; what
                // this code needs from it is only whether anything is left.
                if (packet.tag == SUMMARY) {
                    val remaining = leftover(packet.payload)
                    if (remaining <= 0) {
                        reason = "nothing left"
                        done = true
                    }
                    break
                }
                if (Protocol.needsAuth(packet)) {
                    reason = "the session was not authenticated"
                    done = true
                    break
                }
            }
            batches++
            if (done || got == 0) {
                if (got == 0 && reason == "nothing left") reason = "no events in that window"
                break
            }
        }

        if (batches >= maxBatches) reason = "stopped at $maxBatches batches, more to come"
        vault.cursorDeciseconds = cursor
        vault.lastSyncMs = System.currentTimeMillis()
        return Result(events, batches, bytes, byTag, reason)
    }

    /**
     * The event's own timestamp: the first four payload bytes, little-endian.
     *
     * Every history event begins with one, which is what makes a log of undecoded frames useful —
     * the *when* is readable without understanding the *what*.
     */
    private fun timestamp(payload: ByteArray): Long {
        if (payload.size < 4) return 0
        var value = 0L
        for (i in 3 downTo 0) {
            value = (value shl 8) or (payload[i].toLong() and 0xff)
        }
        return value
    }

    /** How many bytes the ring says are still waiting. Zero means the drain is finished. */
    private fun leftover(payload: ByteArray): Int {
        if (payload.size < 6) return 0
        var value = 0
        for (i in 5 downTo 2) {
            value = (value shl 8) or (payload[i].toInt() and 0xff)
        }
        return value
    }

    private companion object {
        /** How many events to ask for at a time. The app's own request uses a batch like this. */
        const val BATCH = 8

        /** The frame that closes a batch. */
        const val SUMMARY = 0x11
    }
}
