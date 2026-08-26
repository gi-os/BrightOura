package com.gios.brightoura.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.provider.Settings
import com.gios.brightoura.data.Trace

/**
 * Accepting a pairing request on a phone that will not show you one.
 *
 * ### The problem, exactly
 *
 * Android's pairing request is a **notification with a full-screen intent**: the system posts it and
 * expects the launcher to raise the dialog behind it. LightOS does not. So the phone chimes, the
 * request sits in a shade nobody can open, the sixty-second window expires, and the bond fails with
 * every layer working as designed and nothing on screen to accept.
 *
 * That is not something this app can fix from the inside — the dialog belongs to Settings and the
 * consent belongs to the platform. What it can do is three things, in order of how well they work.
 *
 * ### 1. Answer it ourselves
 *
 * A pairing request is broadcast as [BluetoothDevice.ACTION_PAIRING_REQUEST], and for the "Just
 * Works" variants a listener can call `setPairingConfirmation(true)` and be done. On modern Android
 * that call wants `BLUETOOTH_PRIVILEGED`, which is signature-level and cannot be granted by `pm
 * grant` — so it usually throws, and the throw is reported rather than swallowed. Sometimes it
 * works. Trying costs nothing and it is the only path that needs no screen at all.
 *
 * ### 2. Raise the dialog ourselves
 *
 * The dialog is an ordinary activity in the Settings package. Started explicitly with the device and
 * the variant, it appears — which is the whole of what the notification was supposed to do. It is a
 * private component of another app and may refuse; that is why there is a third option.
 *
 * ### 3. Send them to Bluetooth settings
 *
 * Pairing the ring once from the system's own screen leaves a bond that lasts, and every connection
 * afterwards needs none of this. On a phone with no Settings app this fails too, and then the honest
 * answer is the one on screen: this ring needs a bond and this phone cannot make one.
 */
object Pairing {

    /**
     * Watch for a pairing request and try to accept it.
     *
     * Returned as a closeable handle rather than a fire-and-forget: the receiver must not outlive
     * the attempt, and a receiver left registered is how an app ends up confirming a pairing
     * somebody started somewhere else entirely.
     */
    @SuppressLint("MissingPermission")
    fun watch(context: Context, onProgress: (String) -> Unit): AutoCloseable {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action != BluetoothDevice.ACTION_PAIRING_REQUEST) return
                val device = @Suppress("DEPRECATION")
                intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                val variant = intent.getIntExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, -1)
                Trace.add("pairing request, variant $variant (${variantName(variant)})")
                onProgress("The phone is asking to pair — ${variantName(variant)}")
                if (device == null) return
                // Just Works and consent-style variants are the ones a listener can answer.
                val answered = runCatching { device.setPairingConfirmation(true) }
                    .onFailure {
                        Trace.add("setPairingConfirmation: ${it.javaClass.simpleName}")
                        onProgress(
                            "This phone will not let an app accept a pairing request — open the " +
                                "request or use Bluetooth settings",
                        )
                    }
                    .getOrDefault(false)
                if (answered) {
                    Trace.add("pairing confirmed by the app")
                    onProgress("Accepted it for you")
                    return
                }
                // Could not answer it. Everything left is an attempt to put *something* on screen
                // that can, in falling order of likelihood on this phone.
                if (show(context, device, variant, onProgress)) return
                // **And that is where this stops.**
                //
                // v0.8 opened a Bluetooth screen from here, on the reasoning that the request
                // expires in under a minute and there is no time to explain. What that actually did
                // was switch away from this app *in the middle of its own connection* — which reads
                // as the app closing itself, kills the GATT conversation it was in, and leaves the
                // ring mid-pair. A fallback that destroys the thing it was helping is not a
                // fallback.
                //
                // The Bluetooth screen is a button on the setup screen. Pressed deliberately,
                // between attempts, it is useful; fired automatically, during one, it is sabotage.
                Trace.add("no screen will show the request; leaving the connection alone")
                onProgress("Nothing here will show the request — pair from the Bluetooth screen")
            }
        }
        val filter = IntentFilter(BluetoothDevice.ACTION_PAIRING_REQUEST)
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
        }
        return AutoCloseable { runCatching { context.unregisterReceiver(receiver) } }
    }

    /**
     * Raise the system pairing dialog for a device.
     *
     * The same activity the notification's full-screen intent would have opened, started directly.
     * Two attempts: the action with the Settings package named, then the component by name, because
     * which of those a build accepts is not something to guess at from out here.
     */
    fun show(
        context: Context,
        device: BluetoothDevice,
        variant: Int,
        onProgress: (String) -> Unit = {},
    ): Boolean {
        val extras = Intent(BluetoothDevice.ACTION_PAIRING_REQUEST).apply {
            putExtra(BluetoothDevice.EXTRA_DEVICE, device)
            if (variant >= 0) putExtra(BluetoothDevice.EXTRA_PAIRING_VARIANT, variant)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val attempts = listOf(
            // Unpackaged first: whatever *this* build registers as the pairing handler gets it,
            // which on a phone with its own launcher-side Bluetooth UI may not be Settings at all.
            Intent(extras),
            Intent(extras).setPackage(SETTINGS),
            Intent(extras).setClassName(SETTINGS, DIALOG),
        )
        for (intent in attempts) {
            val ok = runCatching { context.startActivity(intent); true }.getOrDefault(false)
            if (ok) {
                Trace.add("raised the pairing dialog")
                onProgress("Pairing request on screen — accept it")
                return true
            }
        }
        Trace.add("could not raise the pairing dialog")
        onProgress("Could not put the request on screen. Try Bluetooth settings.")
        return false
    }

    /**
     * Open a Bluetooth screen where a bond can be made by hand — **LightOS's own, first**.
     *
     * `ACTION_BLUETOOTH_SETTINGS` resolves to AOSP's Settings app, and on this phone that app
     * crashes on the pairing screen. LightOS has a Bluetooth screen of its own — it is how the
     * phone pairs earbuds — and it draws its own prompt rather than relying on the system
     * notification nothing renders. So this looks for that first, by asking the package manager
     * which of LightOS's own activities sound like Bluetooth.
     *
     * Named by search rather than hardcoded: the component is not documented anywhere and an
     * activity name in somebody else's launcher is exactly the sort of constant that changes in an
     * update. If nothing is found, the answer is LightOS's home screen — from which its own
     * settings are two taps away and definitely not crashing.
     */
    fun openSettings(context: Context, onProgress: (String) -> Unit = {}): Boolean {
        lightOsBluetooth(context)?.let { component ->
            val ok = runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_MAIN)
                        .setComponent(component)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
                true
            }.getOrDefault(false)
            if (ok) {
                Trace.add("opened ${component.className}")
                onProgress("Opened Light's own Bluetooth screen")
                return true
            }
        }
        val attempts = listOf(
            // LightOS's dashboard: its Bluetooth screen is inside this, and it is the one UI on
            // the phone that is definitely not the crashing Settings app.
            Intent(Intent.ACTION_MAIN).setPackage(LIGHTOS),
            Intent(Settings.ACTION_BLUETOOTH_SETTINGS),
            Intent(Settings.ACTION_SETTINGS),
        )
        for (intent in attempts) {
            val ok = runCatching {
                context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                true
            }.getOrDefault(false)
            if (ok) {
                onProgress(
                    if (intent.`package` == LIGHTOS) {
                        "Opened Light's own settings — Bluetooth is in there"
                    } else {
                        "Opened the system settings app"
                    },
                )
                return true
            }
        }
        return false
    }

    /**
     * Whichever of LightOS's activities looks like a Bluetooth screen.
     *
     * Asked of the installed package rather than assumed. `QUERY_ALL_PACKAGES` is already held for
     * the scan, and reading an activity list costs one call.
     */
    private fun lightOsBluetooth(context: Context): android.content.ComponentName? = runCatching {
        val flags = android.content.pm.PackageManager.GET_ACTIVITIES
        val info = context.packageManager.getPackageInfo(LIGHTOS, flags)
        info.activities
            ?.map { it.name }
            ?.firstOrNull { it.contains("bluetooth", ignoreCase = true) }
            ?.let { android.content.ComponentName(LIGHTOS, it) }
    }.getOrNull()

    /**
     * What a pairing variant means, because the number decides whether this is solvable.
     *
     * `3` is consent — a plain yes/no with nothing to type, which is the "Just Works" case. It is
     * the *easiest* pairing there is and the one this phone cannot complete, because a yes needs a
     * dialog and confirming it in-process needs `BLUETOOTH_PRIVILEGED`. A PIN variant would at
     * least have somewhere to type.
     */
    private fun variantName(variant: Int): String = when (variant) {
        0 -> "a PIN to type"
        1 -> "a passkey to type"
        2 -> "a passkey to confirm"
        3 -> "just a yes or no"
        4 -> "a yes or no"
        5 -> "a display passkey"
        6 -> "a PIN with 16 digits"
        else -> "type $variant"
    }

    /**
     * Bond over LE explicitly, by reflection, because the public call will not let you say.
     *
     * `createBond()` uses `TRANSPORT_AUTO`. On a stack that also speaks classic Bluetooth that can
     * mean the phone attempts **classic pairing against a device that only speaks LE** — which
     * produces exactly the symptom this phone shows from its own Bluetooth screen: "pairing…
     * pairing…" and then nothing, because the classic attempt has nobody to talk to and times out
     * without a word.
     *
     * `createBond(int transport)` has existed since Android 6 and has never been public. It is the
     * standard workaround for this symptom. Being reflection it is written to fail quietly: if the
     * method is gone or blocked by the hidden-API policy, that is reported and the caller falls
     * back to the ordinary call.
     */
    @SuppressLint("MissingPermission")
    fun bondOverLe(device: BluetoothDevice, onProgress: (String) -> Unit): Boolean {
        val method = runCatching {
            BluetoothDevice::class.java.getMethod("createBond", Int::class.javaPrimitiveType)
        }.getOrNull()
        if (method == null) {
            Trace.add("createBond(transport) is not reachable on this build")
            onProgress("This phone will not let an app choose the pairing transport")
            return false
        }
        val started = runCatching { method.invoke(device, TRANSPORT_LE) as? Boolean }
            .onFailure {
                Trace.add("createBond(LE) threw ${it.javaClass.simpleName}")
                onProgress("The phone refused an LE-only pairing request")
            }
            .getOrNull() ?: false
        Trace.add("createBond(LE) started=$started")
        if (started) onProgress("Pairing over LE only — no classic attempt to time out")
        return started
    }

    /** `BluetoothDevice.TRANSPORT_LE`. Public as a constant; only the overload taking it is not. */
    private const val TRANSPORT_LE = 2

    private const val SETTINGS = "com.android.settings"
    private const val LIGHTOS = "com.lightos"
    private const val DIALOG = "com.android.settings.bluetooth.BluetoothPairingDialog"
}
