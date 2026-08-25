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
         * Filtered on the service UUID rather than on the name, because the name changes: a
         * factory-reset ring advertises `Oura XXXXXXXX` and the same ring calls itself
         * `Oura Ring Gen3` once an app has keyed it. The service is the constant.
         */
        @SuppressLint("MissingPermission")
        suspend fun scan(context: Context, timeoutMs: Long = 8_000L): List<Found> {
            val adapter = adapter(context) ?: return emptyList()
            val scanner = adapter.bluetoothLeScanner ?: return emptyList()
            val found = LinkedHashMap<String, Found>()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device ?: return
                    val name = result.scanRecord?.deviceName ?: device.name
                    found[device.address] = Found(
                        address = device.address,
                        name = name ?: "Oura ring",
                        rssi = result.rssi,
                    )
                }
            }
            val filter = ScanFilter.Builder()
                .setServiceUuid(android.os.ParcelUuid(Protocol.SERVICE))
                .build()
            val settings = ScanSettings.Builder()
                .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
                .build()
            runCatching { scanner.startScan(listOf(filter), settings, callback) }
                .onFailure { return emptyList() }
            withTimeoutOrNull(timeoutMs) { kotlinx.coroutines.delay(timeoutMs) }
            runCatching { scanner.stopScan(callback) }
            return found.values.sortedByDescending { it.rssi }
        }

        /**
         * Connect, discover, subscribe, and raise the MTU. Null when any of that failed.
         *
         * Deliberately does *not* authenticate: the caller decides whether this is a probe of an
         * unknown ring — where firmware and serial answer cold and are exactly what you want — or a
         * session that needs the key.
         */
        @SuppressLint("MissingPermission")
        suspend fun connect(context: Context, address: String): Ring? {
            val adapter = adapter(context) ?: return null
            val device: BluetoothDevice = runCatching { adapter.getRemoteDevice(address) }
                .getOrNull() ?: return null
            val frames = Channel<ByteArray>(Channel.BUFFERED)
            val ready = Channel<Boolean>(Channel.CONFLATED)
            val opened = AtomicBoolean(false)
            var connection: BluetoothGatt? = null

            val callback = object : BluetoothGattCallback() {
                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    if (newState == BluetoothProfile.STATE_CONNECTED) {
                        // The MTU comes first: history frames are up to 203 bytes and this code
                        // does not reassemble fragments.
                        g.requestMtu(MTU)
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        ready.trySend(false)
                        frames.close()
                    }
                }

                override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
                    g.discoverServices()
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    val service = g.getService(Protocol.SERVICE)
                    val notify = service?.getCharacteristic(Protocol.NOTIFY)
                    if (notify == null) {
                        ready.trySend(false)
                        return
                    }
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

            connection = runCatching { device.connectGatt(context, false, callback) }.getOrNull()
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

        /** Whether Bluetooth is even on. Asked before a scan, so the screen can say so. */
        fun bluetoothOn(context: Context): Boolean = adapter(context)?.isEnabled == true

        private fun adapter(context: Context): BluetoothAdapter? =
            context.getSystemService(BluetoothManager::class.java)?.adapter

        private val CCCD = java.util.UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        /** The ring's own MTU, from the captures. */
        private const val MTU = 203
    }

    /** A ring the scan saw. */
    data class Found(val address: String, val name: String, val rssi: Int)
}
