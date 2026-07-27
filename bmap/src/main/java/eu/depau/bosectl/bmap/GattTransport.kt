package eu.depau.bosectl.bmap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID

private const val TAG = "GattTransport"

/** Bose's GATT service and its read/write/notify characteristics (PROTOCOL.md §15). */
val BMAP_GATT_SERVICE: UUID = UUID.fromString("0000febe-0000-1000-8000-00805f9b34fb")
val BMAP_RWN_SECURE: UUID = UUID.fromString("c65b8f2f-aee2-4c89-b758-bc4892d6f2d8")
val BMAP_RWN_UNSECURE: UUID = UUID.fromString("d417c028-9818-4354-99d1-2ac09d074591")
private val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

/** Android grants far less than it is asked for; segmentation copes either way. */
private const val PREFERRED_MTU = 247

private const val CONNECT_TIMEOUT_MS = 20_000L
private const val CONNECT_ROUNDS = 3
private const val CONNECT_RETRY_DELAY_MS = 1200L
private const val SETUP_TIMEOUT_MS = 10_000L
private const val WRITE_TIMEOUT_MS = 5_000L
private const val FIRST_RESPONSE_TIMEOUT_MS = 3000L
private const val DRAIN_SILENCE_MS = 500L

/**
 * BMAP over BLE/GATT. Interchangeable with [RfcommTransport]; the difference is
 * the transport, not the protocol.
 *
 * Two things this buys over RFCOMM, both verified on QC Ultra Earbuds 2nd gen:
 * the earbuds answer even when they are connected to a *different* host for
 * audio, and no hidden `createRfcommSocket` reflection is involved.
 *
 * Needs the phone to be bonded with the earbuds: the firmware answers almost
 * everything with error 20 `InsecureTransport` on an unencrypted link, and the
 * classic bond supplies LE encryption via CTKD.
 *
 * Verified on hardware: GETs, replies larger than one notification, multi-frame
 * drains (`GetAll [31.1]` answering with eleven `[31.6]` frames), and the `[9.2]`
 * subscription, which reports blocks `[1, 2, 4, 5, 31]` exactly as over RFCOMM.
 *
 * `ponytail:` a *delivered* push has not been observed over LE yet — subscribing
 * works, but nothing changed on the device while it was watched. If live updates
 * ever look stale over LE, suspect delivery rather than the subscription.
 */
@SuppressLint("MissingPermission")  // caller holds BLUETOOTH_CONNECT
class GattTransport private constructor(
    private val gatt: BluetoothGatt,
    private val characteristic: BluetoothGattCharacteristic,
    @Volatile private var mtu: Int,
) : BmapTransport {

    private val requestMutex = Mutex()
    private val inbox = Channel<BmapPacket>(Channel.UNLIMITED)
    private val writeAcks = Channel<Int>(Channel.UNLIMITED)

    @Volatile
    private var requestPending = false

    private val _unsolicited = MutableSharedFlow<BmapPacket>(extraBufferCapacity = 16)
    override val unsolicited: SharedFlow<BmapPacket> = _unsolicited

    @Volatile
    override var isConnected: Boolean = true
        private set

    private val reassembler = BleReassembler()

    /**
     * Wired up after construction: the callback needs the transport, and the
     * transport needs the callback's connect/discover results to exist at all.
     */
    private fun attach(session: Session) {
        session.owner = this
    }

    private fun onDisconnected() {
        isConnected = false
        inbox.close()
        writeAcks.close()
    }

    private fun onFrame(frame: ByteArray) {
        val message = reassembler.accept(frame) ?: return
        val packets = parseAllPackets(message)
        if (packets.isEmpty()) {
            Log.w(TAG, "Unparsable message: ${message.joinToString("") { "%02x".format(it) }}")
            return
        }
        for (packet in packets) {
            Log.d(TAG, "RX $packet")
            if (requestPending) inbox.trySend(packet)
            else _unsolicited.tryEmit(packet)
        }
    }

    private fun onWriteAck(status: Int) {
        writeAcks.trySend(status)
    }

    private fun onMtu(value: Int) {
        mtu = value
    }

    private suspend fun send(packet: ByteArray) {
        if (!isConnected) throw BmapConnectionException("LE link is down")
        Log.d(TAG, "TX ${packet.joinToString("") { "%02x".format(it) }}")
        for (frame in segmentForBle(packet, mtu)) {
            writeFrame(frame)
            val status = try {
                withTimeout(WRITE_TIMEOUT_MS) { writeAcks.receive() }
            } catch (e: TimeoutCancellationException) {
                throw BmapTimeoutException("GATT write not acknowledged in ${WRITE_TIMEOUT_MS}ms")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                isConnected = false
                throw BmapConnectionException("GATT write failed", e)
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                throw BmapConnectionException("GATT write status $status")
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun writeFrame(frame: ByteArray) {
        val ok = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                characteristic, frame, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ) == BluetoothGatt.GATT_SUCCESS
        } else {
            characteristic.value = frame
            characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            gatt.writeCharacteristic(characteristic)
        }
        if (!ok) throw BmapConnectionException("GATT write rejected by the stack")
    }

    private suspend fun receiveOne(timeoutMs: Long): BmapPacket =
        try {
            withTimeout(timeoutMs) { inbox.receive() }
        } catch (e: TimeoutCancellationException) {
            throw BmapTimeoutException("No response within ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw BmapConnectionException("Link closed while waiting for response", e)
        }

    /** Same push-versus-reply discipline as RFCOMM — see [answersRequest]. */
    private suspend fun awaitResponse(sent: ByteArray): BmapPacket {
        val deadline = System.nanoTime() + FIRST_RESPONSE_TIMEOUT_MS * 1_000_000
        while (true) {
            val remaining = (deadline - System.nanoTime()) / 1_000_000
            val packet = receiveOne(remaining.coerceAtLeast(1))
            if (answersRequest(sent, packet)) return packet
            Log.d(TAG, "Push during request: $packet")
            _unsolicited.emit(packet)
        }
    }

    override suspend fun request(packet: ByteArray): BmapPacket = requestMutex.withLock {
        requestPending = true
        try {
            drainInbox()
            send(packet)
            awaitResponse(packet)
        } finally {
            requestPending = false
        }
    }

    override suspend fun requestDrain(packet: ByteArray): List<BmapPacket> =
        requestMutex.withLock {
            requestPending = true
            try {
                drainInbox()
                send(packet)
                val packets = mutableListOf(receiveOne(FIRST_RESPONSE_TIMEOUT_MS))
                while (true) {
                    val next = withTimeoutOrNull(DRAIN_SILENCE_MS) {
                        runCatching { inbox.receive() }.getOrNull()
                    } ?: break
                    packets.add(next)
                }
                packets
            } finally {
                requestPending = false
            }
        }

    private fun drainInbox() {
        while (inbox.tryReceive().isSuccess) Unit
    }

    override fun close() {
        isConnected = false
        // Drop the link before releasing it: an LE connection left open occupies
        // a slot on the earbuds, which have few of them.
        runCatching { gatt.disconnect() }
        runCatching { gatt.close() }
        reassembler.reset()
    }

    /** Callback state for one connection attempt, before the transport exists. */
    private class Session : BluetoothGattCallback() {
        val connected = CompletableDeferred<Boolean>()
        val discovered = CompletableDeferred<Boolean>()
        val mtu = CompletableDeferred<Int>()
        val descriptorWritten = CompletableDeferred<Int>()

        @Volatile
        var owner: GattTransport? = null

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            Log.i(TAG, "connectionState status=$status newState=$newState")
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> if (!connected.isCompleted) {
                    connected.complete(status == BluetoothGatt.GATT_SUCCESS)
                }

                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (!connected.isCompleted) connected.complete(false)
                    owner?.onDisconnected()
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            Log.i(TAG, "MTU is $newMtu (status=$status)")
            if (!mtu.isCompleted) mtu.complete(newMtu)
            owner?.onMtu(newMtu)
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (!discovered.isCompleted) {
                discovered.complete(status == BluetoothGatt.GATT_SUCCESS)
            }
        }

        override fun onCharacteristicWrite(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int,
        ) {
            owner?.onWriteAck(status)
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int,
        ) {
            if (!descriptorWritten.isCompleted) descriptorWritten.complete(status)
        }

        // API 33+ passes the value; older devices read it off the characteristic.
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray,
        ) {
            owner?.onFrame(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            g: BluetoothGatt, c: BluetoothGattCharacteristic,
        ) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                owner?.onFrame(c.value ?: return)
            }
        }
    }

    companion object {
        /**
         * Open an LE link and subscribe to the BMAP characteristic.
         *
         * [BluetoothDevice.TRANSPORT_LE] is the whole reason this works on a
         * device that is also bonded over classic BT — without it the stack may
         * pick BR/EDR, and BlueZ's lack of the same knob is why this cannot be
         * developed from Linux (PROTOCOL.md §15).
         */
        suspend fun connect(context: Context, device: BluetoothDevice): GattTransport {
            // Like RFCOMM (§1), the earbuds refuse a fresh link for a moment
            // after one tears down: the connection is accepted and then dropped
            // mid-setup, surfacing as a CCCD write failing with status 133.
            var lastError: Throwable? = null
            repeat(CONNECT_ROUNDS) { round ->
                if (round > 0) delay(CONNECT_RETRY_DELAY_MS)
                try {
                    return connectOnce(context, device)
                } catch (e: Throwable) {
                    Log.w(TAG, "LE connect attempt ${round + 1} failed: ${e.message}")
                    lastError = e
                }
            }
            throw lastError ?: BmapConnectionException("LE connect to ${device.address} failed")
        }

        private suspend fun connectOnce(
            context: Context,
            device: BluetoothDevice,
        ): GattTransport {
            val session = Session()
            val gatt = device.connectGatt(
                context, false, session, BluetoothDevice.TRANSPORT_LE
            ) ?: throw BmapConnectionException("connectGatt() returned null")

            try {
                if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { session.connected.await() } != true) {
                    throw BmapConnectionException("LE connect to ${device.address} failed")
                }
                gatt.requestMtu(PREFERRED_MTU)
                // A refused MTU exchange is not fatal: 23 still works, just with
                // more segments per message.
                val mtu = withTimeoutOrNull(SETUP_TIMEOUT_MS) { session.mtu.await() } ?: 23

                if (!gatt.discoverServices()) {
                    throw BmapConnectionException("discoverServices() rejected")
                }
                if (withTimeoutOrNull(CONNECT_TIMEOUT_MS) { session.discovered.await() } != true) {
                    throw BmapConnectionException("GATT service discovery failed")
                }
                val service = gatt.getService(BMAP_GATT_SERVICE)
                    ?: throw BmapConnectionException("Device exposes no BMAP GATT service")
                // Secure first: on an unencrypted link the unsecure one only
                // answers [0.1], [0.2] and [4.14] — everything else is error 20.
                val characteristic = service.getCharacteristic(BMAP_RWN_SECURE)
                    ?: service.getCharacteristic(BMAP_RWN_UNSECURE)
                    ?: throw BmapConnectionException("No BMAP characteristic on the service")

                if (!gatt.setCharacteristicNotification(characteristic, true)) {
                    throw BmapConnectionException("Could not enable notifications")
                }
                characteristic.getDescriptor(CCCD)?.let { cccd ->
                    writeCccd(gatt, cccd)
                    val status = withTimeoutOrNull(SETUP_TIMEOUT_MS) {
                        session.descriptorWritten.await()
                    }
                    if (status != BluetoothGatt.GATT_SUCCESS) {
                        throw BmapConnectionException("CCCD write failed (status=$status)")
                    }
                }

                Log.i(TAG, "Connected to ${device.address} over LE, MTU $mtu, " +
                    "characteristic ${characteristic.uuid}")
                return GattTransport(gatt, characteristic, mtu).also { it.attach(session) }
            } catch (e: Throwable) {
                gatt.close()
                throw e
            }
        }

        @Suppress("DEPRECATION")
        private fun writeCccd(gatt: BluetoothGatt, cccd: BluetoothGattDescriptor) {
            val value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeDescriptor(cccd, value)
            } else {
                cccd.value = value
                gatt.writeDescriptor(cccd)
            }
        }
    }
}
