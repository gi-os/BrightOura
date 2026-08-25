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
    private val frames: Channel<ByteArray>,
) {

    /** Send a request and wait for the next frame. Null on silence. */
    suspend fun ask(request: ByteArray, timeoutMs: Long = REPLY_MS): Protocol.Packet? {
        send(request)
        return next(timeoutMs)
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
            return Scan(
                rings = rings.values.sortedByDescending { it.rssi },
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
        ): Ring? {
            val adapter = adapter(context) ?: return null
            val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
                .getOrNull() ?: return null

            // **Bond before connecting.** The ring refuses notify subscription and writes on an
            // unencrypted link, and Android's own answer to that is a GATT failure rather than a
            // pairing prompt — so a connect on an unbonded ring fails with nothing on screen and no
            // prompt to accept. Asking for the bond explicitly is what raises the system dialog.
            if (device.bondState == BluetoothDevice.BOND_NONE) {
                onProgress("Asking to pair — accept the prompt on the phone")
                if (!bond(context, device)) {
                    onProgress("The pairing was refused or timed out")
                    return null
                }
            }
            onProgress("Connecting")
            val frames = Channel<ByteArray>(Channel.BUFFERED)
            val ready = Channel<Boolean>(Channel.CONFLATED)
            val opened = AtomicBoolean(false)
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
                        onProgress(
                            if (status == BluetoothGatt.GATT_SUCCESS) {
                                "Disconnected"
                            } else {
                                "The link dropped (status $status)"
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
                    // Subscribed. Only now is the link able to carry a conversation.
                    if (opened.compareAndSet(false, true)) {
                        ready.trySend(status == BluetoothGatt.GATT_SUCCESS)
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
            }

            // `TRANSPORT_LE` explicitly. The default is AUTO, which on some phones tries
            // BR/EDR first against a device that only speaks BLE — and fails in a way that looks
            // like the ring is not there.
            connection = runCatching {
                device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
            }.getOrNull()
            val g = connection ?: return null
            val up = withTimeoutOrNull(CONNECT_MS) { ready.receive() } ?: false
            if (!up) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
                return null
            }
            val writeChar = g.getService(Protocol.SERVICE)?.getCharacteristic(Protocol.WRITE)
            if (writeChar == null) {
                runCatching { g.disconnect() }
                runCatching { g.close() }
                return null
            }
            return Ring(g, writeChar, frames)
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
        private suspend fun bond(context: Context, device: BluetoothDevice): Boolean {
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
            return try {
                if (!device.createBond()) return false
                withTimeoutOrNull(BOND_MS) { done.receive() } ?: false
            } finally {
                runCatching { context.unregisterReceiver(receiver) }
            }
        }

        /** Whether Bluetooth is even on. Asked before a scan, so the screen can say so. */
        fun bluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true

        private fun adapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        private val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The ring's own MTU, from the captures. */
        private const val MTU = 203

        /** How long the system pairing prompt is waited on. A person has to find it first. */
        private const val BOND_MS = 60_000L
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
