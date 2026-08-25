package com.gios.brightoura.data

import android.content.Context
import java.io.File

/**
 * Every frame the ring has handed over, kept as bytes.
 *
 * ### Why raw, and why a file
 *
 * The ring's history buffer is finite: what it has already given up, it has given up for good. A
 * decoder that only stores what it currently understands throws away the events it will understand
 * next month, and there is no way to ask again. So the log is append-only hex, one frame per line,
 * with its tag and its own timestamp in front — and decoding is a pass over this file rather than a
 * step in the sync.
 *
 * A file rather than a database because the shape fits: appended in order, read in order, never
 * updated, and rotated by size. That is what a log is, and Room would be a schema over the top of
 * a decision this app has not had to make yet.
 *
 * ### Line format
 *
 * `<tag hex> <ring timestamp> <payload hex>`
 *
 * Space-separated so it is greppable by hand over adb, which is the only debugging channel this
 * phone has.
 */
class EventLog(context: Context) {

    private val file = File(context.filesDir, NAME)

    /** Append one frame. Silent on failure: a sync that stops for a full disk is worse. */
    fun append(tag: Int, timestamp: Long, payload: ByteArray) {
        runCatching {
            rotateIfHuge()
            file.appendText(
                "%02x %d %s\n".format(tag, timestamp, payload.joinToString("") { "%02x".format(it) }),
            )
        }
    }

    /** How many frames, and how many of each tag. Read cheaply enough to show on a screen. */
    fun counts(): Counts = runCatching {
        if (!file.exists()) return Counts(0, emptyMap(), 0L)
        var lines = 0
        val byTag = HashMap<Int, Int>()
        file.forEachLine { line ->
            val tag = line.substringBefore(' ').toIntOrNull(16) ?: return@forEachLine
            byTag[tag] = (byTag[tag] ?: 0) + 1
            lines++
        }
        Counts(lines, byTag, file.length())
    }.getOrDefault(Counts(0, emptyMap(), 0L))

    /** The last [limit] lines, newest first — for the screen that shows what just arrived. */
    fun tail(limit: Int = 40): List<String> = runCatching {
        if (!file.exists()) return emptyList()
        file.readLines().takeLast(limit).reversed()
    }.getOrDefault(emptyList())

    /** Everything, for a decoding pass. */
    fun lines(): Sequence<String> =
        if (file.exists()) file.readLines().asSequence() else emptySequence()

    fun clear() {
        runCatching { file.delete() }
    }

    /**
     * Rotate at [MAX_BYTES] by keeping the newer half.
     *
     * Dropping the oldest rather than the newest, because a decoder is written against what the
     * ring is producing now — and because the alternative, a log that stops accepting frames, would
     * turn a full disk into a silent sync.
     */
    private fun rotateIfHuge() {
        if (!file.exists() || file.length() < MAX_BYTES) return
        runCatching {
            val kept = file.readLines().let { it.subList(it.size / 2, it.size) }
            file.writeText(kept.joinToString("\n", postfix = "\n"))
        }
    }

    data class Counts(val frames: Int, val byTag: Map<Int, Int>, val bytes: Long)

    private companion object {
        const val NAME = "ring-events.log"

        /** A few weeks of a ring's output. Rotated by halves, so the recent past always survives. */
        const val MAX_BYTES = 8L * 1024 * 1024
    }
}
