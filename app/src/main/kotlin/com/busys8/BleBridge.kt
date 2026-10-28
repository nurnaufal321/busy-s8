package com.busys8

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import com.busys8.engine.Busybar
import com.busys8.engine.system.BleControl
import com.busys8.engine.system.BleStatus
import com.busys8.engine.system.SettingsChange
import com.busys8.server.StateEncoder
import com.busys8.server.StateKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * The bar's Bluetooth link (services/ble) on the phone's radio, as a GATT peripheral:
 *
 * - **Nordic UART** (6E400001…): a byte pipe to the bar's own HTTP server, as
 *   ble/http/ble_http_repeater.c does it. Bytes written to RX go to one TCP connection to the
 *   local API; whatever the server answers comes back as indications on TX, in chunks of up to
 *   237 bytes. When the server closes the connection the session counter (6E400004) moves on;
 *   writing 0 there resets the session.
 * - **HM10 UART** (FFE0): the status stream, the same protobuf `State` messages as
 *   `/api/status/ws`, split into chunks with a `{num, count, size}` header (ble_streaming.c).
 * - **Device Information** and **Battery**.
 *
 * It advertises like the bar (ble_advertise.c): service UUID 0x308A, manufacturer ID 0x0E29,
 * the device name in the scan response. Pairing is "Just Works" with one bonded peer.
 * Every GATT event is logged under `BleBridge`, to see what a client expects.
 */
@SuppressLint("MissingPermission") // BLUETOOTH and BLUETOOTH_ADMIN are install-time on API 28.
class BleBridge(
    private val context: Context,
    private val bar: Busybar,
    private val port: Int,
    private val scope: CoroutineScope,
) : BleControl {
    private val manager = context.getSystemService(BluetoothManager::class.java)
    private val adapter: BluetoothAdapter? = manager?.adapter
    private val prefs = context.getSharedPreferences("ble", Context.MODE_PRIVATE)

    private var server: BluetoothGattServer? = null
    private var advertising = false
    private var enabled = false
    @Volatile private var peer: BluetoothDevice? = null
    /** ATT MTU per connected device; the client's decides the chunk size. */
    private val mtus = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val mtu get() = peer?.let { mtus[it.address] } ?: DEFAULT_MTU
    private var failed = false

    private val uartTx = characteristic(UART_TX, READ or INDICATE, PERM_R, cccd = true)
    private val uartSession = characteristic(UART_SESSION, READ or WRITE, PERM_R or PERM_W).also { it.value = u32(0) }
    private val hm10Tx = characteristic(HM10_TX, READ or NOTIFY, PERM_R, cccd = true)
    private val battery = characteristic(uuid16(0x2A19), READ or NOTIFY, PERM_R, cccd = true)
    private var bondReceiverRegistered = false
    private val subscribed = HashSet<UUID>()

    /** One notification or indication in flight at a time, as Android requires. */
    private val txDone = Semaphore(0)
    private val txLock = Any()

    private val repeater = Repeater()
    private var streamJob: Job? = null

    private val bondedAddress get() = prefs.getString(KEY_BONDED, null)

    override val status: BleStatus
        get() = when {
            failed -> BleStatus.ERROR
            !enabled -> BleStatus.READY
            peer != null -> BleStatus.CONNECTED
            bondedAddress != null -> BleStatus.CONNECTABLE
            else -> BleStatus.ADVERTISING
        }

    override val peerAddress: String? get() = peer?.address

    // ---- BleControl -------------------------------------------------------------------------

    @Synchronized
    override fun enable(): Boolean {
        val a = adapter ?: return false.also { Log.w(TAG, "no Bluetooth adapter") }
        if (enabled) return true
        if (!a.isEnabled) {
            Log.i(TAG, "turning Bluetooth on")
            a.enable()
            // The GATT server and advertiser need the radio up; retry once it is.
            context.registerReceiver(object : BroadcastReceiver() {
                override fun onReceive(c: Context, i: Intent) {
                    if (i.getIntExtra(BluetoothAdapter.EXTRA_STATE, 0) != BluetoothAdapter.STATE_ON) return
                    context.unregisterReceiver(this)
                    start()
                }
            }, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
            enabled = true
            return true
        }
        enabled = true
        start()
        return true
    }

    @Synchronized
    override fun disable(): Boolean {
        if (!enabled) return true
        enabled = false
        stopAdvertising()
        peer?.let { server?.cancelConnection(it) }
        server?.close()
        server = null
        peer = null
        repeater.close()
        streamJob?.cancel()
        if (bondReceiverRegistered) {
            context.unregisterReceiver(bondReceiver)
            bondReceiverRegistered = false
        }
        changed()
        return true
    }

    override fun forget(): Boolean {
        val address = bondedAddress ?: return false
        val device = adapter?.getRemoteDevice(address)
        // removeBond is hidden API; API 28 still allows it (light greylist).
        val removed = runCatching { device?.javaClass?.getMethod("removeBond")?.invoke(device) as? Boolean }.getOrNull() ?: false
        Log.i(TAG, "forget $address: removeBond=$removed")
        prefs.edit().remove(KEY_BONDED).apply()
        peer?.let { server?.cancelConnection(it) }
        changed()
        return true
    }

    /** The advertised name follows the device name, as ble_worker_set_name. */
    @Synchronized
    fun rename(name: String) {
        if (!enabled || server == null) return
        adapter?.name = name
        stopAdvertising()
        startAdvertising()
    }

    // ---- setup --------------------------------------------------------------------------------

    @Synchronized
    private fun start() {
        if (!enabled || server != null) return
        val a = adapter ?: return
        failed = false
        if (!bondReceiverRegistered) {
            context.registerReceiver(bondReceiver, IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED))
            bondReceiverRegistered = true
        }
        a.name = bar.settings.data.name
        server = manager.openGattServer(context, callback)
        if (server == null) {
            failed = true
            Log.w(TAG, "openGattServer failed")
            changed()
            return
        }
        pendingServices.clear()
        pendingServices += listOf(uartService(), hm10Service(), deviceInfoService(), batteryService())
        addNextService()
    }

    /** Android adds GATT services one at a time: the next one goes in from onServiceAdded. */
    private val pendingServices = ArrayDeque<BluetoothGattService>()

    private fun addNextService() {
        val s = pendingServices.removeFirstOrNull()
        if (s == null) {
            startAdvertising()
            return
        }
        server?.addService(s)
    }

    private fun uartService() = BluetoothGattService(UART_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(UART_RX, WRITE or WRITE_NR, PERM_W))
        addCharacteristic(uartTx)
        addCharacteristic(uartSession)
    }

    private fun hm10Service() = BluetoothGattService(HM10_SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(hm10Tx)
        addCharacteristic(characteristic(HM10_RX, READ or WRITE, PERM_R or PERM_W))
    }

    private fun deviceInfoService() = BluetoothGattService(uuid16(0x180A), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(characteristic(uuid16(0x2A25), READ, PERM_R).also { it.value = bar.platform.ids().serial.toByteArray() })
        addCharacteristic(characteristic(uuid16(0x2A27), READ, PERM_R).also { it.value = Build.MODEL.toByteArray() })
        addCharacteristic(characteristic(uuid16(0x2A26), READ, PERM_R).also { it.value = Busybar.FIRMWARE_VERSION.toByteArray() })
    }

    private fun batteryService() = BluetoothGattService(uuid16(0x180F), BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
        addCharacteristic(battery)
    }

    private fun characteristic(uuid: UUID, props: Int, perms: Int, cccd: Boolean = false) =
        BluetoothGattCharacteristic(uuid, props, perms).apply {
            if (cccd) addDescriptor(BluetoothGattDescriptor(CCCD, BluetoothGattDescriptor.PERMISSION_READ or BluetoothGattDescriptor.PERMISSION_WRITE))
        }

    private val advertiseCallback = object : AdvertiseCallback() {
        override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
            Log.i(TAG, "advertising as \"${adapter?.name}\"")
            advertising = true
            changed()
        }

        override fun onStartFailure(errorCode: Int) {
            Log.w(TAG, "advertising failed: $errorCode")
            failed = true
            changed()
        }
    }

    private fun startAdvertising() {
        if (advertising) return
        val advertiser = adapter?.bluetoothLeAdvertiser
        if (advertiser == null) {
            Log.w(TAG, "no LE advertiser")
            failed = true
            changed()
            return
        }
        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_BALANCED)
            .setConnectable(true)
            .setTimeout(0)
            .build()
        val data = AdvertiseData.Builder()
            .addServiceUuid(ParcelUuid(uuid16(0x308A)))
            .addManufacturerData(MANUFACTURER_ID, ByteArray(0))
            .build()
        val scanResponse = AdvertiseData.Builder().setIncludeDeviceName(true).build()
        advertiser.startAdvertising(settings, data, scanResponse, advertiseCallback)
    }

    private fun stopAdvertising() {
        if (!advertising) return
        adapter?.bluetoothLeAdvertiser?.stopAdvertising(advertiseCallback)
        advertising = false
    }

    private fun changed() = bar.settings.notifyChanged(SettingsChange.BLE)

    // ---- GATT callbacks -----------------------------------------------------------------------

    private val callback = object : BluetoothGattServerCallback() {
        override fun onServiceAdded(status: Int, service: BluetoothGattService) {
            Log.d(TAG, "service ${service.uuid} added: $status")
            addNextService()
        }

        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            Log.i(TAG, "connection ${device.address} state=$newState status=$status bond=${device.bondState}")
            // Any number of links may come and go (phones keep idle LE links open); the client is
            // whichever device starts using the bar's services, see claim().
            if (newState == BluetoothProfile.STATE_DISCONNECTED) mtus.remove(device.address)
            if (newState == BluetoothProfile.STATE_DISCONNECTED && peer?.address == device.address) {
                peer = null
                subscribed.clear()
                streamJob?.cancel()
                repeater.close()
                txDone.release()
                changed()
            }
        }

        override fun onMtuChanged(device: BluetoothDevice, newMtu: Int) {
            Log.i(TAG, "mtu $newMtu from ${device.address}")
            mtus[device.address] = newMtu
        }

        override fun onCharacteristicReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, c: BluetoothGattCharacteristic) {
            if (c.uuid == battery.uuid) c.value = byteArrayOf(bar.platform.power().chargePercent.coerceIn(0, 100).toByte())
            val value = c.value ?: ByteArray(0)
            Log.d(TAG, "read ${name(c.uuid)} by ${device.address} offset=$offset (${value.size} bytes)")
            val slice = if (offset < value.size) value.copyOfRange(offset, value.size) else ByteArray(0)
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, offset, slice)
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice, requestId: Int, c: BluetoothGattCharacteristic,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            Log.d(TAG, "write ${name(c.uuid)} ${value.size} bytes" + if (c.uuid == UART_RX) ": ${preview(value)}" else "")
            if (c.uuid == UART_RX || c.uuid == UART_SESSION) claim(device)
            when (c.uuid) {
                UART_RX -> repeater.send(value)
                UART_SESSION -> if (value.size == 4) {
                    if (u32(value) == 0L) repeater.resetSession() else Log.w(TAG, "session not 0, ignored")
                } else {
                    Log.w(TAG, "wrong session data size")
                }
                HM10_RX -> Unit
            }
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onDescriptorReadRequest(device: BluetoothDevice, requestId: Int, offset: Int, d: BluetoothGattDescriptor) {
            val on = d.characteristic.uuid in subscribed
            val value = if (!on) BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
            else if (d.characteristic.uuid == UART_TX) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
            else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, value)
        }

        override fun onDescriptorWriteRequest(
            device: BluetoothDevice, requestId: Int, d: BluetoothGattDescriptor,
            preparedWrite: Boolean, responseNeeded: Boolean, offset: Int, value: ByteArray,
        ) {
            val on = value.isNotEmpty() && value[0].toInt() != 0
            val uuid = d.characteristic.uuid
            if (on) claim(device)
            if (on) subscribed += uuid else subscribed -= uuid
            Log.i(TAG, "${if (on) "subscribe" else "unsubscribe"} ${name(uuid)}")
            if (responseNeeded) server?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
        }

        override fun onNotificationSent(device: BluetoothDevice, status: Int) {
            txDone.release()
        }
    }

    private val bondReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val device = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val state = i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)
            Log.i(TAG, "bond ${device.address} -> $state")
            if (state == BluetoothDevice.BOND_BONDED) {
                // The bar keeps a single bond: a new one replaces the old.
                prefs.edit().putString(KEY_BONDED, device.address).apply()
                changed()
            }
        }
    }

    /** Makes [device] the client once it subscribes or writes (one at a time, like the bar). */
    private fun claim(device: BluetoothDevice) {
        if (peer?.address == device.address) return
        Log.i(TAG, "client is now ${device.address}")
        peer = device
        subscribed.clear()
        repeater.close()
        startStream()
        changed()
    }

    /** Sends one notification (or indication on Nordic TX) and waits for it to go out. */
    private fun transmit(c: BluetoothGattCharacteristic, data: ByteArray): Boolean = synchronized(txLock) {
        val device = peer ?: return false
        if (c.uuid !in subscribed) return false
        txDone.drainPermits()
        c.value = data
        val indicate = c.uuid == UART_TX
        if (server?.notifyCharacteristicChanged(device, c, indicate) != true) return false
        txDone.tryAcquire(TX_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    // ---- HTTP repeater ------------------------------------------------------------------------

    /**
     * ble_http_repeater.c: one TCP connection to the local API carrying the raw bytes. Opened
     * on the first write; when the server closes it, the session counter is set to the number
     * of that connection, and the next write opens a new one.
     */
    private inner class Repeater {
        private var socket: Socket? = null
        private val outgoing = LinkedBlockingQueue<ByteArray>()
        private var currentRequest = 0L
        private var previousRequest = 0L

        private val writer = Thread({
            while (true) {
                val data = outgoing.take()
                try {
                    val s = socket ?: open()
                    s.getOutputStream().write(data)
                    s.getOutputStream().flush()
                } catch (e: IOException) {
                    Log.w(TAG, "repeater write failed: ${e.message}")
                    resetSession()
                }
            }
        }, "ble-http-writer").apply {
            isDaemon = true
            start()
        }

        fun send(data: ByteArray) {
            outgoing.put(data)
        }

        @Synchronized
        private fun open(): Socket {
            val s = Socket()
            s.connect(InetSocketAddress("127.0.0.1", port), 5000)
            socket = s
            if (previousRequest == currentRequest) currentRequest++
            Log.d(TAG, "session $currentRequest")
            Thread({ pump(s) }, "ble-http-reader").apply {
                isDaemon = true
                start()
            }
            return s
        }

        /** Server → client: chunks of up to 237 bytes (MAX_TX_CHUNK_SIZE), as indications. */
        private fun pump(s: Socket) {
            val buf = ByteArray(4096)
            try {
                val input = s.getInputStream()
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    var i = 0
                    while (i < n) {
                        val size = minOf(MAX_TX_CHUNK, mtu - 3, n - i)
                        if (!transmit(uartTx, buf.copyOfRange(i, i + size))) {
                            Log.w(TAG, "session reset by timeout")
                            resetSession()
                            return
                        }
                        i += size
                    }
                }
            } catch (_: IOException) {
            }
            closed(s)
        }

        @Synchronized
        private fun closed(s: Socket) {
            if (socket !== s) return
            socket = null
            runCatching { s.close() }
            uartSession.value = u32(currentRequest)
            previousRequest = currentRequest
            Log.d(TAG, "connection closed, session counter $currentRequest")
        }

        @Synchronized
        fun resetSession() {
            Log.i(TAG, "session reset")
            currentRequest = 0
            previousRequest = 0
            outgoing.clear()
            socket?.let { runCatching { it.close() } }
            socket = null
            uartSession.value = u32(0)
        }

        fun close() = resetSession()
    }

    // ---- status stream ------------------------------------------------------------------------

    /** ble_streaming.c: the state publisher over HM10 TX, at most one message a second. */
    private fun startStream() {
        streamJob?.cancel()
        val encoder = StateEncoder(bar)
        streamJob = scope.launch {
            var last: ByteArray? = null
            while (isActive && peer != null) {
                delay(STREAM_PERIOD_MS)
                if (HM10_TX !in subscribed) continue
                val state = withContext(bar.device.dispatcher) {
                    encoder.state(StateKind.entries.filter { it != StateKind.FRAME }.mapNotNull(encoder::update))
                }
                // Skip the 9-byte timestamp field when deciding whether anything changed.
                val body = state.copyOfRange(9, state.size)
                if (last != null && body.contentEquals(last)) continue
                last = body
                withContext(kotlinx.coroutines.Dispatchers.IO) { sendStream(state) }
            }
        }
    }

    private fun sendStream(data: ByteArray) {
        val count = (data.size + STREAM_CHUNK_DATA - 1) / STREAM_CHUNK_DATA
        for (num in 0 until count) {
            val start = num * STREAM_CHUNK_DATA
            val size = minOf(STREAM_CHUNK_DATA, data.size - start)
            val chunk = ByteBuffer.allocate(MAX_TX_CHUNK).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(num.toShort()).putShort(count.toShort()).putShort(size.toShort())
                .put(data, start, size)
                .array()
            if (!transmit(hm10Tx, chunk)) return
        }
    }

    private companion object {
        const val TAG = "BleBridge"
        const val KEY_BONDED = "bonded_address"

        val UART_SERVICE: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_RX: UUID = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_TX: UUID = UUID.fromString("6E400003-B5A3-F393-E0A9-E50E24DCCA9E")
        val UART_SESSION: UUID = UUID.fromString("6E400004-B5A3-F393-E0A9-E50E24DCCA9E")
        val HM10_SERVICE: UUID = uuid16(0xFFE0)
        val HM10_TX: UUID = uuid16(0xFFE1)
        val HM10_RX: UUID = uuid16(0xFFE2)
        val CCCD: UUID = uuid16(0x2902)

        /** Manufacturer data in the bar's advertisement: company ID only, no payload. */
        const val MANUFACTURER_ID = 0x0E29

        const val DEFAULT_MTU = 23
        const val MAX_TX_CHUNK = 237
        const val STREAM_CHUNK_DATA = MAX_TX_CHUNK - 6
        const val STREAM_PERIOD_MS = 1000L
        /** BLE_HTTP_SESSION_TIMEOUT_ON_TX_CONFIRM_FAIL. */
        const val TX_TIMEOUT_MS = 4000L

        const val READ = BluetoothGattCharacteristic.PROPERTY_READ
        const val WRITE = BluetoothGattCharacteristic.PROPERTY_WRITE
        const val WRITE_NR = BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
        const val NOTIFY = BluetoothGattCharacteristic.PROPERTY_NOTIFY
        const val INDICATE = BluetoothGattCharacteristic.PROPERTY_INDICATE
        const val PERM_R = BluetoothGattCharacteristic.PERMISSION_READ
        const val PERM_W = BluetoothGattCharacteristic.PERMISSION_WRITE

        fun uuid16(id: Int): UUID = UUID.fromString("%08x-0000-1000-8000-00805f9b34fb".format(id))

        fun u32(v: Long): ByteArray = ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v.toInt()).array()

        fun u32(b: ByteArray): Long = ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

        fun name(uuid: UUID) = when (uuid) {
            UART_RX -> "uart-rx"
            UART_TX -> "uart-tx"
            UART_SESSION -> "uart-session"
            HM10_TX -> "hm10-tx"
            HM10_RX -> "hm10-rx"
            else -> uuid.toString().substring(4, 8)
        }

        /** The first line of an HTTP request, for the log. */
        fun preview(b: ByteArray) = String(b, Charsets.ISO_8859_1).lineSequence().first().take(80)
    }
}
