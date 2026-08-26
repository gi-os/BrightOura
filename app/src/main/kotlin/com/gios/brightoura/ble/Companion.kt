package com.gios.brightoura.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.companion.AssociationInfo
import android.companion.AssociationRequest
import android.companion.BluetoothLeDeviceFilter
import android.companion.CompanionDeviceManager
import android.content.Context
import android.content.Intent
import android.content.IntentSender
import android.os.Parcelable
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
        // **No device profile, deliberately.**
        //
        // Up to v0.16 this asked for DEVICE_PROFILE_WATCH, on the reasoning that the watch profile
        // makes the *system* pair the device for us — which sounded like exactly what a phone with
        // a broken pairing dialog needs. It is the opposite. The platform decides whether to skip
        // its consent dialog by looking up who called `createBond`:
        //
        // ```java
        // boolean createBond(device, transport, ..., callingPackage) {
        //     if (deviceProp != null && deviceProp.getBondState() != BOND_NONE) {
        //         return deviceProp.getBondState() == BOND_BONDING;   // early return
        //     }                                                      // caller never recorded
        //     mBondAttemptCallerInfo.put(device.getAddress(), new CallerInfo(callingPackage, user));
        // }
        // ```
        //
        // Whoever bonds **first** owns that slot, and `canBondWithoutDialog` reads only that slot.
        // With the watch profile the association itself starts the bond, from the system — so our
        // own `createBond`, arriving milliseconds later, hit the early return and changed nothing.
        // The recorded caller was a package holding no association, the check failed, and the
        // consent dialog was raised over a phone that cannot draw one. Right address, inside the
        // window, wrong caller.
        //
        // A plain association bonds nothing. It records the approval and gets out of the way, which
        // leaves the first `createBond` to us.
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

    /**
     * The address the system just associated, read off the picker's own result.
     *
     * ### Why the address matters more than the association
     *
     * Associating is not what skips the pairing dialog. This is the rule, out of the platform:
     *
     * ```java
     * // CompanionDeviceManagerService
     * canPairWithoutPrompt(pkg, mac, user) {
     *     association = getFirstAssociationByAddress(user, pkg, mac);   // exact address
     *     return now - association.getTimeApprovedMs() < 10 * 60 * 1000;
     * }
     * ```
     *
     * and the Bluetooth stack asks it by the address `createBond` was called on. So an association
     * only helps when **the bond lands on the same address, within ten minutes of approval**. An
     * Oura ring advertises with a rotating private address, so an association made an hour ago
     * names an address the ring has already stopped using: the check fails, the system falls back
     * to the consent dialog, and on this phone that dialog is the one that crashes Settings.
     *
     * Hence: take the address the picker hands back, and bond that one, now. Not a fresh scan
     * result, not the last one we saw.
     */
    fun addressFrom(data: Intent?): String? {
        if (data == null) return null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            runCatching {
                data.getParcelableExtra(
                    CompanionDeviceManager.EXTRA_ASSOCIATION,
                    AssociationInfo::class.java,
                )
            }.getOrNull()?.deviceMacAddress?.toString()?.let { return it.uppercase() }
        }
        @Suppress("DEPRECATION")
        val found = runCatching {
            data.getParcelableExtra<Parcelable>(CompanionDeviceManager.EXTRA_DEVICE)
        }.getOrNull()
        val address = when (found) {
            is BluetoothDevice -> found.address
            is ScanResult -> found.device?.address
            else -> null
        }
        return address?.uppercase()
    }

    /**
     * Drop every association except the one just made.
     *
     * A rotating address means a new association every time this is run, and they pile up — two
     * associations for one ring, both naming addresses it no longer answers to, was the state this
     * phone was found in. They are not harmless: they make the association list say the ring is set
     * up while the only check that matters keeps failing on the address.
     */
    @SuppressLint("MissingPermission")
    fun prune(context: Context, keep: String?) {
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                manager.myAssociations
                    .filterNot { it.deviceMacAddress?.toString().equals(keep, ignoreCase = true) }
                    .forEach { old ->
                        Trace.add("dropping stale association ${old.deviceMacAddress}")
                        runCatching { manager.disassociate(old.id) }
                    }
            } else {
                @Suppress("DEPRECATION")
                manager.associations
                    .filterNot { it.equals(keep, ignoreCase = true) }
                    .forEach { old ->
                        Trace.add("dropping stale association $old")
                        @Suppress("DEPRECATION")
                        runCatching { manager.disassociate(old) }
                    }
            }
        }
    }

    /** Every association this app holds, as addresses, for the diagnosis screen. */
    fun addresses(context: Context): List<String> = runCatching {
        val manager = context.getSystemService(CompanionDeviceManager::class.java)
            ?: return emptyList()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            manager.myAssociations.mapNotNull { it.deviceMacAddress?.toString() }
        } else {
            @Suppress("DEPRECATION")
            manager.associations.toList()
        }
    }.getOrDefault(emptyList())

    /** Whether the system already holds an association for us, so the step can be skipped. */
    fun associated(context: Context): Boolean = runCatching {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        val manager = context.getSystemService(CompanionDeviceManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        manager.associations.isNotEmpty()
    }.getOrDefault(false)

    private const val NAME = "Oura"
}
