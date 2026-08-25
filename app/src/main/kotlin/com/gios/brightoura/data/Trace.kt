package com.gios.brightoura.data

import android.os.SystemClock

/**
 * What the app just tried, in order.
 *
 * A BLE conversation fails in the middle and leaves nothing behind: the screen says "could not
 * connect" and the interesting part — how far it got, whether a bond was asked for, whether the
 * service was even there — happened three callbacks ago on a thread nobody was watching. On a phone
 * with no adb attached that is the whole diagnosis, gone.
 *
 * So every step writes a line here, and a failure report carries the lot. It is in memory only and
 * capped: this is a breadcrumb trail for the last minute or two, not a log file.
 */
object Trace {

    private const val MAX = 80

    private val lines = ArrayDeque<String>()
    private var startedAt = SystemClock.elapsedRealtime()

    /** Begin a new attempt. The clock restarts so the timings read as "since I pressed it". */
    @Synchronized
    fun begin(what: String) {
        startedAt = SystemClock.elapsedRealtime()
        add("── $what")
    }

    @Synchronized
    fun add(line: String) {
        val at = SystemClock.elapsedRealtime() - startedAt
        lines.addLast("%5dms  %s".format(at, line))
        while (lines.size > MAX) lines.removeFirst()
    }

    /** The trail, oldest first, as one block — which is what a report wants. */
    @Synchronized
    fun text(): String = lines.joinToString("\n")

    @Synchronized
    fun latest(limit: Int = 12): List<String> = lines.toList().takeLast(limit).reversed()

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
