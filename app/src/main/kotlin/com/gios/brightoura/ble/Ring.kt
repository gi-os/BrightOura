package com.gios.brightoura.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.gios.brightoura.data.Trace
import android.os.Build
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One connection to one ring, as a suspending conversation.
 *
 * ### Why this shape
 *
 * The ring's protocol is strictly request/response over two characteristics: write a frame, wait
 * for the frame that answers it. Android's BLE API is callbacks all the way down, and code that
 * mixes the two ends up with a state machine per command. So the callbacks feed exactly one
 * [Channel] of frames, and every command is "write, then take from the channel with a timeout".
 *
 * That works because the ring answers in order and only speaks unprompted for history events and
 * live streams — both of which arrive on the same channel and are read by the caller that asked for
 * them.
 *
 * ### The two things that bite
 *
 * **Encryption comes first.** After a factory reset the ring refuses notify subscription and writes
 * until the link is bonded — Android reports that as a GATT failure rather than as "pair first", so
 * a connect that fails immediately on a freshly reset ring usually means the system pairing prompt
 * is waiting behind the app.
 *
 * **The MTU matters.** The ring's own MTU is 203 and history frames use it; the default 23 would
 * chop every event into fragments this code does not reassemble. So the MTU is requested before
 * anything is asked for, and a failed request is a reason to stop rather than to carry on quietly.
 */
class Ring private constructor(
    private val gatt: BluetoothGatt,
    private val write: BluetoothGattCharacteristic,
    private val notify: BluetoothGattCharacteristic,
    private val frames: Channel<ByteArray>,
    /**
     * Whether the notify subscription actually took.
     *
     * False is not fatal any more. Subscribing is the first thing on this link that needs
     * encryption, so on a phone that cannot complete a BLE bond it is the first thing to fail —
     * and a ring that will not push frames may still hand them over when *asked*. See [ask].
     */
    private val subscribed: Boolean,
) {

    /**
     * Send a request and wait for the next frame. Null on silence.
     *
     * Two ways of hearing the answer, and the second one exists because of this phone. Normally the
     * ring pushes it: subscribe once, and replies arrive as notifications. If the subscription was
     * refused — which is what an unbondable link looks like — the same characteristic is *read*
     * instead, a few times, a beat apart. Polling is worse in every way except the one that
     * matters: it needs no encrypted link.
     */
    suspend fun ask(request: ByteArray, timeoutMs: Long = REPLY_MS): Protocol.Packet? {
        send(request)
        if (subscribed) return next(timeoutMs)
        return poll(timeoutMs)
    }

    /**
     * Ask the notify characteristic for its current value, repeatedly, until something arrives.
     *
     * A reply that has already been delivered as a notification cannot be read back, so this is
     * only ever the fallback path — but a ring that answers a request by *setting* the value rather
     * than only pushing it will answer here. Whether this ring does is exactly what the probe is
     * for, and it is not knowable from the outside.
     */
    @SuppressLint("MissingPermission")
    private suspend fun poll(timeoutMs: Long): Protocol.Packet? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (!gatt.readCharacteristic(notify)) return null
            next(POLL_STEP_MS)?.let { return it }
        }
        return null
    }

    /** What the two characteristics say they can do, for the screen that has to explain a failure. */
    fun capabilitiesLine(): String = buildString {
        append("write ")
        append(properties(write))
        append(" · notify ")
        append(properties(notify))
        append(if (subscribed) " · subscribed" else " · NOT subscribed")
    }

    private fun properties(characteristic: BluetoothGattCharacteristic): String {
        val flags = characteristic.properties
        val names = buildList {
            if (flags and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("read")
            if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("write")
            if (flags and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("write-nr")
            if (flags and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("notify")
            if (flags and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("indicate")
        }
        return if (names.isEmpty()) "none" else names.joinToString("/")
    }

    /** Send without waiting — for the second half of a stream that answers many frames. */
    @SuppressLint("MissingPermission")
    fun send(request: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                write,
                request,
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
            )
        } else {
            @Suppress("DEPRECATION")
            write.value = request
            @Suppress("DEPRECATION")
            gatt.writeCharacteristic(write)
        }
    }

    /** The next frame, or null if the ring said nothing in time. */
    suspend fun next(timeoutMs: Long = REPLY_MS): Protocol.Packet? =
        withTimeoutOrNull(timeoutMs) { Protocol.parse(frames.receive()) }

    @SuppressLint("MissingPermission")
    fun close() {
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        frames.close()
    }

    companion object {

        /** How long a single reply is waited for. Generous: the ring is a ring. */
        const val REPLY_MS = 6_000L

        /** How long a connect and service discovery is given before giving up. */
        const val CONNECT_MS = 20_000L

        /** What a ring calls itself before it has been named by an app. */
        const val NAME_PREFIX = "Oura"

        /**
         * Look for rings.
         *
         * **Unfiltered, and that is the fix rather than the shortcut.** The first version filtered
         * on the ring's service UUID, which is correct for a ring that advertises it — and a ring
         * that does not advertise it is then invisible. An Oura ring's advertisement is small: it
         * carries a name and sometimes nothing else, and which generation puts the service in the
         * advertisement versus only in the GATT table is not something to bet a scan on.
         *
         * So everything is scanned and matched afterwards, three ways: the service if it is
         * advertised, the name if the ring gave one, or a bond that already exists with something
         * called Oura. Whatever else is nearby is counted and reported — a scan that finds eleven
         * devices and no ring is a different problem from a scan that finds nothing at all, and the
         * screen should be able to say which.
         */
        @SuppressLint("MissingPermission")
        suspend fun scan(
            context: Context,
            timeoutMs: Long = 10_000L,
            onProgress: (String) -> Unit = {},
        ): Scan {
            val adapter = adapter(context) ?: return Scan(emptyList(), 0, "Bluetooth is unavailable")
            val scanner = adapter.bluetoothLeScanner
                ?: return Scan(emptyList(), 0, "No BLE scanner — is Bluetooth on?")
            val rings = LinkedHashMap<String, Found>()
            val others = HashSet<String>()

            // A ring already bonded to this phone may not be advertising at all — it does not need
            // to. Bonded devices are checked first so a paired ring is offered instantly.
            runCatching {
                adapter.bondedDevices?.forEach { device ->
                    val name = device.name ?: return@forEach
                    if (name.startsWith(NAME_PREFIX, ignoreCase = true)) {
                        rings[device.address] = Found(device.address, name, rssi = 0, bonded = true)
                    }
                }
            }

            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val name = result.scanRecord?.deviceName ?: device.name
                    val advertises = result.scanRecord?.serviceUuids
                        ?.any { it.uuid == Protocol.SERVICE } == true
                    val looksLikeOura = name?.startsWith(NAME_PREFIX, ignoreCase = true) == true
                    if (advertises || looksLikeOura) {
                        rings[device.address] = Found(
                            address = device.address,
                            name = name ?: "Oura ring",
                            rssi = result.rssi,
                            bonded = device.bondState == BluetoothDevice.BOND_BONDED,
                        )
                        onProgress("Found ${name ?: device.address}")
                    } else {
                        others.add(device.address)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    onProgress("The scan was refused (code $errorCode)")
                }
            }
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching { scanner.startScan(null, settings, callback) }
                .onFailure { return Scan(emptyList(), 0, it.message ?: "The scan could not start") }
            // Reported as it goes, so a scan that finds nothing still looks like something
            // happening rather than a frozen screen.
            val step = 1_000L
            var waited = 0L
            while (waited < timeoutMs) {
                kotlinx.coroutines.delay(step)
                waited += step
                onProgress(
                    "Looking… ${waited / 1000}s · ${rings.size} ring${if (rings.size == 1) "" else "s"}" +
                        if (others.isEmpty()) "" else ", ${others.size} other device(s)",
                )
            }
            runCatching { scanner.stopScan(callback) }
            // **One row per ring.** A ring advertises with a rotating private address, so the same
            // ring appears under two of them within a minute — and a bonded ring adds a third entry
            // from the bond list with no address in common with either. Keyed on the name, keeping
            // the bonded entry if there is one and otherwise the strongest signal, because that is
            // the one a connection has the best chance with.
            val unique = rings.values
                .groupBy { it.name }
                .map { (_, seen) ->
                    seen.firstOrNull { it.bonded } ?: seen.maxByOrNull { it.rssi } ?: seen.first()
                }
            return Scan(
                rings = unique.sortedByDescending { it.rssi },
                otherDevices = others.size,
                note = when {
                    rings.isNotEmpty() -> null
                    others.isEmpty() ->
                        "Nothing at all answered. Bluetooth may be off, or the scan permission " +
                            "was refused."
                    else ->
                        "Saw ${others.size} other device(s) but no ring. Wear it or put it on the " +
                            "charger to wake its radio, then look again."
                },
            )
        }

        /**
         * Connect, discover, subscribe, and raise the MTU. Null when any of that failed.
         *
         * Deliberately does *not* authenticate: the caller decides whether this is a probe of an
         * unknown ring — where firmware and serial answer cold and are exactly what you want — or a
         * session that needs the key.
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(
            context: Context,
            address: String,
            onProgress: (String) -> Unit = {},
            /**
             * Whether to subscribe for notifications.
             *
             * **Off by default, which is the opposite of how BLE is normally written**, and the
             * reason is in the trail from this phone: the descriptor write that subscribes is what
             * triggers the bond, the bond never completes here, and the callback for that write
             * therefore never arrives — so the whole connection stalls until it times out, twenty
             * seconds later, having asked the ring nothing at all.
             *
             * Without it the link is plain and unencrypted, requests still go out, and replies are
             * read rather than pushed. Slower, and it works on a phone that cannot bond.
             */
            subscribe: Boolean = false,
        ): Ring? {
            val adapter = adapter(context) ?: return null
            val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
                .getOrNull() ?: return null

            // **Connect first, bond only if the link asks for it.** v0.2 did the opposite —
            // `createBond()` before connecting — on the reading that the ring refuses an
            // unencrypted link. That is true after a factory reset and *not* true of a ring that
            // is still onboarded, which will happily answer firmware and serial with no bond at
            // all. Worse, a great many BLE bonds are "Just Works": they complete with **no prompt
            // of any kind**, so an app that waits for the user to accept something waits for an
            // event that is never coming, and then reports a pairing failure on a ring that was
            // ready to talk.
            //
            // So: try. If a GATT operation comes back with an insufficient-encryption status, the
            // link genuinely needs a bond, and only then is one asked for — see [needsBond].
            onProgress(
                if (device.bondState == BluetoothDevice.BOND_BONDED) {
                    "Connecting (already paired)"
                } else {
                    "Connecting"
                },
            )

            // **Watch for the pairing request for the whole connection.** v0.4 only watched while
            // an explicit bond was in flight, and the request does not arrive then — it arrives the
            // moment a connection touches something that needs an encrypted link, which is during
            // *this*. So the chime happened with nothing listening, and the one chance to put the
            // dialog on screen went past. See [Pairing].
            val watcher = Pairing.watch(context, onProgress)
            val frames = Channel<ByteArray>(Channel.BUFFERED)
            val ready = Channel<Boolean>(Channel.CONFLATED)
            val opened = AtomicBoolean(false)
            /** Set when a GATT status says the link is not encrypted enough. See below. */
            val needsBond = AtomicBoolean(false)
            val subscribed = AtomicBoolean(false)
            var connection: BluetoothGatt? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        onProgress("Connected · raising the MTU")
                        // The MTU comes first: history frames are up to 203 bytes and this code
                        // does not reassemble fragments. A *refused* request is not a reason to
                        // stop — it is a reason to carry on at the default, which is why this
                        // falls through to discovery rather than waiting for a callback that will
                        // never come.
                        if (!g.requestMtu(MTU)) g.discoverServices()
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        if (isEncryptionStatus(status)) needsBond.set(true)
                        onProgress(
                            when {
                                status == BluetoothGatt.GATT_SUCCESS -> "Disconnected"
                                isEncryptionStatus(status) ->
                                    "The ring dropped the link asking for encryption (status $status)"
                                else -> describe(status)
                            },
                        )
                        ready.trySend(false)
                        frames.close()
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    onProgress("Discovering services")
                    g.discoverServices()
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val service = g.getService(Protocol.SERVICE)
                    val notify = service?.getCharacteristic(Protocol.NOTIFY)
                    if (!subscribe && service != null && notify != null) {
                        // Straight through. Nothing here needs an encrypted link, so nothing here
                        // can be blocked by a bond that will not finish.
                        onProgress("Ready (reading replies rather than subscribing)")
                        if (opened.compareAndSet(false, true)) ready.trySend(true)
                        return
                    }
                    if (notify == null) {
                        onProgress(
                            if (service == null) {
                                "Connected, but this device has no Oura service"
                            } else {
                                "The Oura service is there but its notify channel is not"
                            },
                        )
                        ready.trySend(false)
                        return
                    }
                    onProgress("Subscribing")
                    g.setCharacteristicNotification(notify, true)
                    val cccd = notify.getDescriptor(CCCD)
                    if (cccd == null) {
                        ready.trySend(false)
                        return
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        g.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
                    } else {
                        @Suppress("DEPRECATION")
                        cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                        @Suppress("DEPRECATION")
                        g.writeDescriptor(cccd)
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    // Subscribing is the first thing that needs an encrypted link, so this is
                    // where a missing bond announces itself — as a status code, not a prompt.
                    if (isEncryptionStatus(status)) needsBond.set(true)
                    if (status != BluetoothGatt.GATT_SUCCESS) onProgress(describe(status))
                    subscribed.set(status == BluetoothGatt.GATT_SUCCESS)
                    if (opened.compareAndSet(false, true)) {
                        // Open either way. A refused subscription means the push channel is
                        // unavailable, not that the ring is unreachable — [ask] falls back to
                        // reading, and a probe that gets one frame out of a phone that cannot bond
                        // is worth more than a clean failure.
                        ready.trySend(true)
                    }
                }

                @Deprecated("Kept for API 32 and below, which has no value-carrying overload.")
                @Suppress("DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                        characteristic.value?.let { frames.trySend(it.copyOf()) }
                    }
                }

                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    frames.trySend(value.copyOf())
                }

                @Deprecated("Kept for API 32 and below.")
                @Suppress("DEPRECATION")
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    status: Int,
                ) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return
                    if (isEncryptionStatus(status)) needsBond.set(true)
                    characteristic.value?.takeIf { it.isNotEmpty() }?.let { frames.trySend(it.copyOf()) }
                }

                // A read answers here, and it is fed into the same channel a notification would
                // have used — so everything above [ask] is indifferent to which way the frame came.
                override fun onCharacteristicRead(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                    status: Int,
                ) {
                    if (isEncryptionStatus(status)) needsBond.set(true)
                    if (value.isNotEmpty()) frames.trySend(value.copyOf())
                }
            }

            // `TRANSPORT_LE` explicitly. The default is AUTO, which on some phones tries
            // BR/EDR first against a device that only speaks BLE — and fails in a way that looks
            // like the ring is not there.
            connection = runCatching {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            val g = connection ?: run {
                onProgress("The phone refused to start a connection — is the scan permission on?")
                return null
            }
            val up = withTimeoutOrNull(CONNECT_MS) { ready.receive() } ?: false
            if (!up) {
                runCatching { watcher.close() }
                runCatching { g.disconnect() }
                runCatching { g.close() }
                // The one case where a bond is the answer: the link said it needed encryption.
                // Asked for here rather than pre-emptively, and reported either way — a bond that
                // completes silently is normal, and a bond that never completes is worth naming.
                if (needsBond.get() && device.bondState != BluetoothDevice.BOND_BONDED) {
                    onProgress("The ring wants a paired link. Asking to pair…")
                    val bonded = bond(context, device, onProgress)
                    onProgress(
                        if (bonded) "Paired. Try again." else "The pairing did not complete.",
                    )
                }
                return null
            }
            runCatching { watcher.close() }
            val writeChar = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.WRITE)
            if (writeChar == null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
                return null
            }
            val notifyChar = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.NOTIFY)
            if (notifyChar == null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
                return null
            }
            val ring = Ring(g, writeChar, notifyChar, frames, subscribed.get())
            onProgress(ring.capabilitiesLine())
            Trace.add(ring.capabilitiesLine())
            return ring
        }

        /**
         * Ask Android to bond, and wait for the answer.
         *
         * `createBond` is what raises the system pairing prompt. Without this the app relies on a
         * GATT operation *triggering* the bond, which the platform does inconsistently — and on a
         * phone where nothing appears, there is nothing for the user to accept and nothing on
         * screen to explain why.
         *
         * The wait is long on purpose. The prompt is a notification the user has to find, and the
         * ring has to be awake to answer: a minute is not generous, it is realistic.
         */
        @SuppressLint("MissingPermission")
        suspend fun bond(
            context: Context,
            device: BluetoothDevice,
            onProgress: (String) -> Unit = {},
        ): Boolean {
            val done = Channel<Boolean>(Channel.CONFLATED)
            val receiver = object : android.content.BroadcastReceiver() {
                override fun onReceive(c: Context?, intent: android.content.Intent?) {
                    if (intent?.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val which = @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (which?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, -1)) {
                        BluetoothDevice.BOND_BONDED -> done.trySend(true)
                        BluetoothDevice.BOND_NONE -> done.trySend(false)
                    }
                }
            }
            val filter = android.content.IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.registerReceiver(
                        receiver,
                        filter,
                        Context.RECEIVER_EXPORTED,
                    )
                } else {
                    context.registerReceiver(receiver, filter)
                }
            }
            // Watch for the request while this bond is in flight. On this phone the system's own
            // notification is never rendered — see [Pairing] — so something has to either answer it
            // or put it on screen, and both of those live there.
            val watcher = Pairing.watch(context, onProgress)
            return try {
                val asked = runCatching { device.createBond() }.getOrDefault(false)
                if (!asked) {
                    // False means the request itself was refused — a missing permission, or a
                    // device the adapter will not bond with. Not a timeout, and worth saying so
                    // rather than waiting a minute to say nothing.
                    onProgress("The phone would not start pairing (permission, or the ring refused)")
                    return false
                }
                onProgress(
                    "Pairing… most rings pair with no prompt at all, so this may just finish",
                )
                val bonded = withTimeoutOrNull(BOND_MS) { done.receive() } ?: false
                if (!bonded) onProgress("Pairing did not finish in a minute")
                bonded
            } finally {
                runCatching { watcher.close() }
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        /**
         * The statuses that mean "this link is not encrypted enough", which is the only honest
         * reason to go asking for a bond.
         *
         * `5` is insufficient authentication, `15` insufficient encryption, and `137` is Android's
         * own `GATT_AUTH_FAIL`. Nothing else in the GATT status space means "pair with me", and
         * treating other failures as a pairing problem is how an app asks somebody to accept a
         * prompt that was never going to appear.
         */
        private fun isEncryptionStatus(status: Int): Boolean = status == 5 || status == 15 || status == 137

        /**
         * A GATT status in words.
         *
         * Not decoration: `133` is the status every Android BLE developer knows and no user could,
         * and it almost always means the device is out of range or asleep rather than broken. A
         * screen that says so saves an evening.
         */
        private fun describe(status: Int): String = when (status) {
            0 -> "Fine"
            8 -> "The ring dropped the link (timeout, status 8)"
            19 -> "The ring closed the link (status 19)"
            22 -> "The phone closed the link (status 22)"
            133 -> "No answer from the ring (status 133) — asleep, out of range, or busy with " +
                "another phone"
            147 -> "The connection could not be set up (status 147)"
            else -> "The link failed (status $status)"
        }

        /** Pair with a ring by address, for the button that asks for it deliberately. */
        @SuppressLint("MissingPermission")
        suspend fun bondWith(
            context: Context,
            address: String,
            onProgress: (String) -> Unit = {},
        ): Boolean {
            val adapter = adapter(context) ?: return false
            val device = runCatching { adapter.getRemoteDevice(address) }.getOrNull() ?: return false
            if (device.bondState == BluetoothDevice.BOND_BONDED) {
                onProgress("Already paired")
                return true
            }
            return bond(context, device, onProgress)
        }

        /**
         * What this phone is bonded to, with its bond state, for the diagnosis.
         *
         * The one question that decides everything else: a ring the phone already trusts needs
         * none of the pairing machinery, and a ring bonded to *another* phone will refuse a second
         * link whatever this app does.
         */
        @SuppressLint("MissingPermission")
        fun bondedNames(context: Context): List<String> = runCatching {
            adapter(context)?.bondedDevices?.map { device ->
                "${device.name ?: "unnamed"} ${device.address} state=${device.bondState}"
            }.orEmpty()
        }.getOrDefault(emptyList())

        /** Whether Bluetooth is even on. Asked before a scan, so the screen can say so. */
        fun bluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true

        private fun adapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        private val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The ring's own MTU, from the captures. */
        private const val MTU = 203

        /** How long the system pairing prompt is waited on. A person has to find it first. */
        private const val BOND_MS = 60_000L

        /** How long one poll of the notify characteristic waits before asking again. */
        private const val POLL_STEP_MS = 400L
    }

    /** A ring the scan saw. */
    data class Found(
        val address: String,
        val name: String,
        val rssi: Int,
        /** Already bonded to this phone, so no pairing prompt is coming. */
        val bonded: Boolean = false,
    )

    /**
     * What a scan found, including what it found that was not a ring.
     *
     * The count of other devices is the difference between two very different failures: a radio
     * that is not working, and a ring that is not awake. Both look like an empty list.
     */
    data class Scan(
        val rings: List<Found>,
        val otherDevices: Int,
        val note: String?,
    )
}
