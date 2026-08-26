package com.gios.brightoura.notify

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.gios.brightoura.data.Trace

/**
 * Pressing the "Pair" button on a notification this phone never draws.
 *
 * ### The idea
 *
 * Android's pairing request is a notification, and that notification carries **actions** — Pair and
 * Cancel — each holding a `PendingIntent`. A `PendingIntent` runs as *whoever created it*, and the
 * creator here is the system. So firing the Pair action does not need `BLUETOOTH_PRIVILEGED`, or a
 * dialog, or a shade: it is the same thing that would happen if somebody tapped the button that
 * cannot be seen.
 *
 * This is the last idea in the box, and it is the one that fits the phone: LightOS posts
 * notifications perfectly well — BrightControl's lock face lists them — it simply never *draws* the
 * pairing one, because that arrives with a full-screen intent nothing here honours. The
 * notification exists. Only the pixels are missing.
 *
 * ### What it will and will not press
 *
 * Only notifications from the Bluetooth stack, Settings, or the system, and only actions whose
 * words mean yes. Anything else is left alone and logged. A service that reads every notification
 * on the phone and presses buttons in them is not a thing to write casually, and this one is
 * deliberately narrow enough to read in one sitting.
 *
 * If no action matches, the notification's full-screen intent is sent instead — which is exactly
 * what the launcher was supposed to do and does not, and at worst puts the real dialog on screen.
 *
 * ### The grant
 *
 * A notification listener needs to be switched on, and on this phone there is no Settings screen for
 * it, so it is one adb line — the same shape BrightControl already grants for its own lock face:
 *
 *     adb shell cmd notification allow_listener com.gios.brightoura/.notify.PairingListener
 */
class PairingListener : NotificationListenerService() {

    override fun onNotificationPosted(notification: StatusBarNotification?) {
        val posted = notification ?: return
        if (posted.packageName !in WATCHED) return
        val body = posted.notification ?: return
        val title = text(body, Notification.EXTRA_TITLE)
        val message = text(body, Notification.EXTRA_TEXT)

        // **Every Bluetooth notification is traced, not only the pairing one.** The open question
        // is no longer "can this be pressed" but "does the request exist at all" — if the app that
        // posts it crashes on the way there is nothing to press and nothing to draw, and from
        // outside those two failures look identical. One line each settles it.
        Trace.add("notification from ${posted.packageName}: $title | $message")

        if (!looksLikePairing(title, message)) return

        Trace.add("pairing notification from ${posted.packageName}: $title")
        val actions = body.actions.orEmpty()
        Trace.add("actions: " + actions.joinToString(", ") { it.title?.toString().orEmpty() })

        val yes = actions.firstOrNull { action ->
            val label = action.title?.toString()?.lowercase().orEmpty()
            YES.any { label.contains(it) }
        }
        if (yes != null) {
            val sent = runCatching { yes.actionIntent?.send(); true }.getOrDefault(false)
            Trace.add(if (sent) "pressed '${yes.title}'" else "could not press '${yes.title}'")
            return
        }

        // No action to press. The full-screen intent is the dialog the launcher never raised, so
        // sending it by hand is the next best thing — and it is the system's own intent, aimed
        // where the system meant it to go.
        val full = body.fullScreenIntent
        if (full != null) {
            val sent = runCatching { full.send(); true }.getOrDefault(false)
            Trace.add(if (sent) "sent the full-screen intent" else "the full-screen intent refused")
            return
        }
        Trace.add("the pairing notification had nothing to press")
    }

    private fun text(notification: Notification, key: String): String =
        notification.extras?.getCharSequence(key)?.toString().orEmpty()

    /**
     * Whether this is the pairing request rather than some other Bluetooth notification.
     *
     * Matched on words rather than on a channel id, because the channel is not part of any contract
     * and the words are what the user would have read. Deliberately narrow: "pair" and a device
     * name, not "bluetooth" and hope.
     */
    private fun looksLikePairing(title: String, message: String): Boolean {
        val both = (title + " " + message).lowercase()
        return both.contains("pair") || both.contains("bluetooth request")
    }

    private companion object {
        /** Only these post a pairing request. */
        val WATCHED = setOf(
            "com.android.bluetooth",
            "com.android.settings",
            "android",
            "com.google.android.bluetooth",
        )

        /** Words that mean yes, in the buttons a pairing request carries. */
        val YES = listOf("pair", "accept", "allow", "ok", "yes", "connect")
    }
}
