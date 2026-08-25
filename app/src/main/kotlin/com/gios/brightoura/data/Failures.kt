package com.gios.brightoura.data

import android.content.Context
import com.gios.light.common.report.Failure
import com.gios.light.common.report.Reports
import com.gios.light.common.report.Symptom
import com.gios.light.common.report.Trouble

/**
 * Filing a failure without asking, because this one is worth having.
 *
 * Everywhere else in the collection a report is offered and the user sends it. Here the app is
 * talking to a piece of hardware whose protocol was reverse-engineered from somebody else's notes,
 * against a ring generation nobody has tested it on — so a connection that fails is not a nuisance,
 * it is *the* thing being developed, and the trail that explains it is gone the moment the screen
 * changes.
 *
 * So a failure files itself: symptom, the step it failed at, and the breadcrumb trail from [Trace].
 * Nothing personal is in it — no serial, no key, no measurements. Steps, timings and status codes.
 *
 * **It queues rather than sends.** That is `Reports`' own design and it is right here too: the
 * report survives the app being killed, and goes out whenever a build with a reporting key runs.
 * A build without the key queues them forever, which is worth knowing rather than discovering.
 */
object Failures {

    /**
     * Note a failure, offer it in the app's own chip, and queue the report.
     *
     * The same failure is only queued once every [QUIET_MS], so a ring that is simply out of range
     * cannot fill the queue while somebody presses Sync six times.
     */
    suspend fun file(context: Context, step: String, detail: String?) {
        val now = System.currentTimeMillis()
        val previous = lastFiled[step]
        // The chip is offered every time — the user pressing something and seeing nothing is the
        // failure this is about — but only the first in the window is queued.
        Trouble.record(step, detail)
        if (previous != null && now - previous < QUIET_MS) return
        lastFiled[step] = now
        val report = Reports.compose(
            context = context,
            symptom = Symptom.Other,
            note = "ring: could not $step",
            screen = "ring",
            crash = null,
            failure = Failure(what = step, detail = buildString {
                if (!detail.isNullOrBlank()) {
                    appendLine(detail)
                    appendLine()
                }
                append(Trace.text())
            }),
        )
        Reports.submit(context, report)
    }

    /** Whether a queued report can actually leave the phone on this build. */
    fun canSend(): Boolean = Reports.canSend()

    fun queued(context: Context): Int = Reports.pendingCount(context)

    private val lastFiled = mutableMapOf<String, Long>()

    /** One report per kind of failure per ten minutes. */
    private const val QUIET_MS = 10 * 60 * 1000L
}
