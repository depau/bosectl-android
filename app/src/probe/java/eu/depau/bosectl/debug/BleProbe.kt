package eu.depau.bosectl.debug

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.content.Context
import android.os.Build
import android.util.Log
import eu.depau.bosectl.bmap.BmapPacket
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.bmap.parseAllPackets
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

/**
 * BMAP over BLE/GATT from Android, which — unlike BlueZ — can pin the transport
 * with [BluetoothDevice.TRANSPORT_LE] even for a device that is also bonded over
 * classic BT. See `docs/PROTOCOL.md` §15.
 *
 * `mode ble` is read-only. `mode bleoffline` additionally disconnects this phone's
 * own classic link ([4.2]) to test control without audio, then reconnects it
 * ([4.1]) — the same two commands the app's multipoint UI already sends.
 */
@SuppressLint("MissingPermission")  // app holds BLUETOOTH_CONNECT
class BleProbe(private val context: Context, private val label: String) {

    private var connected = CompletableDeferred<Int>()
    private var services = CompletableDeferred<Boolean>()
    private var mtu = CompletableDeferred<Int>()
    private val writeAcks = Channel<Int>(Channel.UNLIMITED)
    private val descriptorAcks = Channel<Int>(Channel.UNLIMITED)
    private val inbox = Channel<BmapPacket>(Channel.UNLIMITED)

    private var gatt: BluetoothGatt? = null
    private var char: BluetoothGattCharacteristic? = null
    private val rxSegments = mutableListOf<ByteArray>()
    private var maxNotification = 0

    @Volatile
    private var linkUp = false

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "[$label] connectionState status=$status newState=$newState")
            linkUp = newState == BluetoothGatt.STATE_CONNECTED
            if (linkUp) {
                if (!connected.isCompleted) connected.complete(status)
            } else if (!connected.isCompleted) {
                connected.complete(-1)
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            Log.i(TAG, "[$label] MTU granted: $newMtu (status=$status)")
            if (!mtu.isCompleted) mtu.complete(newMtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            g.services.forEach { svc ->
                Log.i(TAG, "[$label]   service ${svc.uuid}")
                svc.characteristics.forEach { c ->
                    Log.i(TAG, "[$label]     char ${c.uuid} props=0x%02x".format(c.properties))
                }
            }
            if (!services.isCompleted) services.complete(status == BluetoothGatt.GATT_SUCCESS)
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            writeAcks.trySend(status)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
        ) {
            descriptorAcks.trySend(status)
        }

        // API 33+ delivers the value as a parameter; older devices via getValue().
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) = onNotification(value)

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                onNotification(c.value ?: return)
            }
        }
    }

    /** Reassemble Bose's segmentation header, then split the BMAP frames. */
    private fun onNotification(value: ByteArray) {
        maxNotification = maxOf(maxNotification, value.size)
        Log.i(TAG, "[$label] RX raw (${value.size}B): ${value.hex()}")
        if (value.isEmpty()) return
        val seg = value[0].toInt() and 0xFF
        rxSegments.add(value.copyOfRange(1, value.size))
        if ((seg shr 4) == (seg and 0x0F)) {
            val data = rxSegments.reduce { a, b -> a + b }
            rxSegments.clear()
            val packets = parseAllPackets(data)
            if (packets.isEmpty()) Log.w(TAG, "[$label]   unparsed: ${data.hex()}")
            packets.forEach {
                Log.i(TAG, "[$label]   -> $it")
                inbox.trySend(it)
            }
        }
    }

    /** Open the LE transport and subscribe. Returns false if anything failed. */
    private suspend fun open(device: BluetoothDevice, requestMtu: Int): Boolean {
        connected = CompletableDeferred()
        services = CompletableDeferred()
        mtu = CompletableDeferred()
        rxSegments.clear()

        gatt = device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
        val g = gatt ?: run { Log.e(TAG, "[$label] connectGatt returned null"); return false }
        if (withTimeoutOrNull(20_000) { connected.await() }?.takeIf { it >= 0 } == null) {
            Log.e(TAG, "[$label] LE connect failed/timed out")
            return false
        }
        g.requestMtu(requestMtu)
        Log.i(TAG, "[$label] requested MTU $requestMtu, granted " +
            "${withTimeoutOrNull(5000) { mtu.await() }}")
        g.discoverServices()
        if (withTimeoutOrNull(20_000) { services.await() } != true) {
            Log.e(TAG, "[$label] service discovery failed")
            return false
        }
        val svc = g.getService(SERVICE) ?: run {
            Log.e(TAG, "[$label] FEBE service absent"); return false
        }
        char = svc.getCharacteristic(RWN_SECURE)?.also {
            Log.i(TAG, "[$label] using SECURE characteristic")
        } ?: svc.getCharacteristic(RWN_UNSECURE)?.also {
            Log.i(TAG, "[$label] using UNSECURE characteristic")
        } ?: run { Log.e(TAG, "[$label] no RWN characteristic"); return false }

        g.setCharacteristicNotification(char, true)
        char!!.getDescriptor(CCCD)?.let {
            writeDescriptor(g, it, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            Log.i(TAG, "[$label] CCCD status=${withTimeoutOrNull(5000) { descriptorAcks.receive() }}")
        }
        return true
    }

    /** Send one BMAP packet, collect the frames that come back. */
    private suspend fun exchange(packet: ByteArray, what: String): List<BmapPacket> {
        val g = gatt ?: return emptyList()
        val c = char ?: return emptyList()
        while (inbox.tryReceive().isSuccess) Unit
        Log.i(TAG, "[$label] TX $what: ${packet.hex()}")
        val granted = withTimeoutOrNull(1) { mtu.await() } ?: DEFAULT_MTU
        for (frame in segment(packet, granted)) {
            writeChar(g, c, frame)
            val status = withTimeoutOrNull(5000) { writeAcks.receive() }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.w(TAG, "[$label]   write status=$status (link up=$linkUp)")
                return emptyList()
            }
        }
        val got = mutableListOf<BmapPacket>()
        withTimeoutOrNull(2500) { got.add(inbox.receive()) }
        while (true) {
            got.add(withTimeoutOrNull(600) { inbox.receive() } ?: break)
        }
        if (got.isEmpty()) Log.w(TAG, "[$label]   no reply to $what")
        return got
    }

    private suspend fun get(fblock: Int, func: Int, what: String) =
        exchange(bmapPacket(fblock, func, Op.GET), "[$fblock.$func] GET $what")

    suspend fun run(requestMtu: Int) {
        val device = device() ?: return
        if (!open(device, requestMtu)) return close()
        for ((fblock, func, name) in READ_SWEEP) {
            get(fblock, func, name)
            delay(400)
        }
        Log.i(TAG, "[$label] largest single notification=$maxNotification bytes")
        close()
    }

    /**
     * Disconnect this phone's own classic link and see whether BMAP over BLE keeps
     * working — i.e. can the app control the earbuds while they play to something
     * else? Restores the connection with [4.1] at the end.
     */
    suspend fun runOffline(requestMtu: Int) {
        val device = device() ?: return
        if (!open(device, requestMtu)) return close()

        val mac = get(4, 9, "AppAddress (this phone)").firstOrNull()?.payload
        if (mac == null || mac.size != 6) {
            Log.e(TAG, "[$label] no usable [4.9] AppAddress, aborting")
            return close()
        }
        Log.i(TAG, "[$label] this phone is ${mac.hex()}")
        get(5, 1, "active source BEFORE")
        get(4, 4, "device list BEFORE")

        Log.i(TAG, "[$label] ===== dropping this phone's classic link =====")
        exchange(bmapPacket(4, 2, Op.START, mac), "[4.2] START disconnect self")

        // Audio is gone now. Does the LE link survive, and does BMAP still answer?
        for (round in 0 until 6) {
            delay(3000)
            Log.i(TAG, "[$label] --- offline round $round (LE link up=$linkUp) ---")
            if (!linkUp) {
                Log.w(TAG, "[$label] LE link dropped with the classic one; reopening")
                close()
                if (!open(device, requestMtu)) {
                    Log.e(TAG, "[$label] could not reopen LE with no classic link")
                    break
                }
                Log.i(TAG, "[$label] reopened LE with no classic link")
            }
            get(2, 2, "battery OFFLINE")
            get(31, 3, "current mode OFFLINE")
            if (round == 2) get(4, 4, "device list OFFLINE")
        }

        Log.i(TAG, "[$label] ===== reconnecting this phone =====")
        if (!linkUp) open(device, requestMtu)
        // [4.1] payload is [0x00] + mac (PROTOCOL.md §14).
        exchange(bmapPacket(4, 1, Op.START, byteArrayOf(0) + mac), "[4.1] START reconnect self")
        repeat(4) {
            delay(3000)
            val list = get(4, 4, "device list AFTER")
            if (list.any { it.payload.firstOrNull()?.toInt()?.and(0xFF) ?: 0 != 0 }) {
                Log.i(TAG, "[$label] connected mask is non-zero again")
            }
        }
        get(5, 1, "active source AFTER")
        Log.i(TAG, "[$label] largest single notification=$maxNotification bytes")
        close()
    }

    private suspend fun device(): BluetoothDevice? {
        val mac = DeviceRepository.savedDeviceMac() ?: run {
            Log.e(TAG, "[$label] no device selected"); return null
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return null
        val device = adapter.getRemoteDevice(mac)
        Log.i(TAG, "[$label] device $mac bondState=${device.bondState} " +
            "(12 = BONDED), type=${device.type} (3 = DUAL)")
        return device
    }

    /** `[seg] + chunk`, seg = (lastIndex shl 4) or index; see PROTOCOL.md §15. */
    private fun segment(packet: ByteArray, mtuValue: Int): List<ByteArray> {
        val chunk = (mtuValue - 4).coerceAtLeast(1)
        val chunks = packet.toList().chunked(chunk).map { it.toByteArray() }
        val last = chunks.size - 1
        return chunks.mapIndexed { i, c -> byteArrayOf(((last shl 4) or i).toByte()) + c }
    }

    @Suppress("DEPRECATION")
    private fun writeChar(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT)
        } else {
            c.value = value
            c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            g.writeCharacteristic(c)
        }
    }

    @Suppress("DEPRECATION")
    private fun writeDescriptor(g: BluetoothGatt, d: BluetoothGattDescriptor, value: ByteArray) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            g.writeDescriptor(d, value)
        } else {
            d.value = value
            g.writeDescriptor(d)
        }
    }

    private fun close() {
        gatt?.close()
        gatt = null
        char = null
        linkUp = false
    }

    private companion object {
        const val TAG = "BmapProbe"
        const val DEFAULT_MTU = 23
        val SERVICE: UUID = UUID.fromString("0000febe-0000-1000-8000-00805f9b34fb")
        val RWN_SECURE: UUID = UUID.fromString("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8")
        val RWN_UNSECURE: UUID = UUID.fromString("d417c028-9818-4354-99d1-2ac09d074591")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

        val READ_SWEEP = listOf(
            Triple(0, 1, "bmap version"),
            Triple(0, 7, "serial (long reply)"),
            Triple(2, 2, "battery"),
            Triple(1, 10, "multipoint"),
            Triple(31, 3, "current mode"),
            Triple(31, 10, "audio settings"),
            Triple(5, 1, "active source"),
            Triple(4, 4, "device list (long reply)"),
            Triple(9, 2, "notify mask"),
        )

        fun ByteArray.hex() = joinToString(" ") { "%02x".format(it) }
    }
}
