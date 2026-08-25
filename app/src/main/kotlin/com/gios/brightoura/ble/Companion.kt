package com.gios.brightoura.ble

import android.annotation.SuppressLint
import android.bluetooth.le.ScanFilter
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.IntentSender
import android.os.Build
import com.gios.brightoura.data.Trace
import java.util.regex.Pattern

/**
 * Pairing through the system's companion-device flow, which is the one dialog this phone will draw.
 *
 * ### Why this exists
 *
 * The ordinary pairing request is a *notification* with a full-screen intent, and LightOS never
 * raises it — the phone chimes and nothing appears, so the request expires unanswered. That is not
 * a bug this app can fix from the inside.
 *
 * `CompanionDeviceManager` is a different shape entirely: the app asks, the *system* runs a device
 * picker, and the picker is handed back as an `IntentSender` **the app launches itself**. It is an
 * activity, not a notification — so it appears on a phone that draws no notifications at all. And
 * for the watch profile the system takes on the pairing as part of associating, which is exactly
 * the step that has been failing.
 *
 * ### What it costs
 *
 * The watch profile asks for more than a plain association does — the dialog says so plainly, in the
 * system's own words rather than ours. If the profile is refused this falls back to an ordinary
 * association, which still gets the ring on screen in a picker and still lets the user confirm it;
 * whether the platform then bonds is up to the platform.
 *
 * Association is remembered by the system, so this is a one-time step rather than a login.
 */
object Companions {

    /**
     * Ask the system to find the ring and offer it.
     *
     * The filter is on the name rather than the service, for the same reason the scan is: a ring
     * that keeps its service in the GATT table and not in the advertisement would never appear in
     * a picker filtered on the service, and the picker is the whole point here.
     */
    @SuppressLint("MissingPermission")
    fun request(
        context: Context,
        onProgress: (String) -> Unit,
        onPicker: (IntentSender) -> Unit,
        onFailure: (String) -> Unit,
    ) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            onFailure("This phone is too old for the companion flow")
            return
        }
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
        if (manager == null) {
            onFailure("This phone has no companion-device service")
            return
        }
        val filter = BluetoothLeDeviceFilter.Builder()
            .setNamePattern(Pattern.compile("(?i)^$NAME.*"))
            .setScanFilter(ScanFilter.Builder().build())
            .build()
        val builder = AssociationRequest.Builder()
            .addDeviceFilter(filter)
            .setSingleDevice(false)
        // The watch profile is what makes the system pair the device for us. It is only available
        // from Android 12, and asking for it on an older build throws rather than degrading.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching { builder.setDeviceProfile(AssociationRequest.DEVICE_PROFILE_WATCH) }
                .onFailure { Trace.add("watch profile refused: ${it.javaClass.simpleName}") }
        }
        onProgress("Asking the phone to find the ring")
        Trace.add("companion association requested")
        runCatching {
            manager.associate(
                builder.build(),
                object : CompanionDeviceManager.Callback() {
                    @Deprecated("Replaced on API 33, and still the one older builds call.")
                    override fun onDeviceFound(intentSender: IntentSender) {
                        Trace.add("companion picker ready")
                        onPicker(intentSender)
                    }

                    override fun onAssociationPending(intentSender: IntentSender) {
                        Trace.add("companion picker ready (pending)")
                        onPicker(intentSender)
                    }

                    override fun onFailure(error: CharSequence?) {
                        val text = error?.toString().orEmpty().ifBlank { "no reason given" }
                        Trace.add("companion association failed: $text")
                        onFailure(text)
                    }
                },
                null,
            )
        }.onFailure {
            Trace.add("associate() threw: ${it.javaClass.simpleName}")
            onFailure(it.message ?: it.javaClass.simpleName)
        }
    }

    /** Whether the system already holds an association for us, so the step can be skipped. */
    fun associated(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        manager.associations.isNotEmpty()
    }.getOrDefault(false)

    private const val NAME = "Oura"
}
