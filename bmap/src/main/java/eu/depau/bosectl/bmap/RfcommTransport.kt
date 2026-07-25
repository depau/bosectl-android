package eu.depau.bosectl.bmap

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.util.UUID

private const val TAG = "RfcommTransport"

/** SDP UUID of the BMAP control channel (RFCOMM channel 2 on QC Ultra). */
val BMAP_UUID: UUID = UUID.fromString("00000000-deca-fade-deca-deafdecacaff")

private const val POST_SEND_DELAY_MS = 200L   // required by BMAP between send and recv
private const val FIRST_RESPONSE_TIMEOUT_MS = 3000L
private const val DRAIN_SILENCE_MS = 500L
private const val CONNECT_ROUNDS = 3
private const val CONNECT_RETRY_DELAY_MS = 1200L

/**
 * BMAP over Bluetooth RFCOMM. A single reader coroutine splits the stream into
 * frames; frames arriving while a request is pending are routed to the
 * requester, everything else is emitted on [unsolicited].
 */
@SuppressLint("MissingPermission")  // caller holds BLUETOOTH_CONNECT
class RfcommTransport private constructor(private val socket: BluetoothSocket) : BmapTransport {

    companion object {
        suspend fun connect(device: BluetoothDevice): RfcommTransport =
            withContext(Dispatchers.IO) {
                // BMAP lives on RFCOMM channel 2 (same as bosectl). Do NOT use
                // SDP lookup by the BMAP UUID: it connects "successfully" to the
                // ff55 status-beacon channel, which silently ignores BMAP.
                val attempts: List<Pair<String, () -> BluetoothSocket>> = listOf(
                    "secure ch2" to {
                        device.javaClass.getMethod(
                            "createRfcommSocket", Int::class.javaPrimitiveType
                        ).invoke(device, 2) as BluetoothSocket
                    },
                    "insecure ch2" to {
                        device.javaClass.getMethod(
                            "createInsecureRfcommSocket", Int::class.javaPrimitiveType
                        ).invoke(device, 2) as BluetoothSocket
                    },
                )
                // The earbuds refuse the channel for a second or two after a
                // previous socket closes, so a single pass is not enough.
                var lastError: Exception? = null
                repeat(CONNECT_ROUNDS) { round ->
                    if (round > 0) delay(CONNECT_RETRY_DELAY_MS)
                    for ((label, factory) in attempts) {
                        try {
                            val socket = factory().apply { connect() }
                            Log.i(TAG, "Connected to ${device.address} via $label")
                            return@withContext RfcommTransport(socket)
                        } catch (e: Exception) {
                            Log.w(TAG, "Connect attempt failed ($label, round $round): $e")
                            lastError = e
                        }
                    }
                }
                throw BmapConnectionException("Failed to connect to ${device.address}", lastError)
            }
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val requestMutex = Mutex()
    private val inbox = Channel<BmapPacket>(Channel.UNLIMITED)
    @Volatile
    private var requestPending = false

    private val _unsolicited = MutableSharedFlow<BmapPacket>(extraBufferCapacity = 16)
    override val unsolicited: SharedFlow<BmapPacket> = _unsolicited

    @Volatile
    override var isConnected: Boolean = true
        private set

    private val readerJob: Job = scope.launch {
        val buf = ByteArray(4096)
        var pending = ByteArray(0)
        try {
            while (true) {
                val n = runInterruptible { socket.inputStream.read(buf) }
                if (n < 0) break
                pending += buf.copyOfRange(0, n)
                while (true) {
                    val packet = parsePacket(pending) ?: break
                    pending = pending.copyOfRange(4 + packet.payload.size, pending.size)
                    Log.d(TAG, "RX $packet")
                    if (requestPending) inbox.trySend(packet)
                    else _unsolicited.emit(packet)
                }
            }
        } catch (e: IOException) {
            Log.d(TAG, "Reader terminated: ${e.message}")
        } finally {
            isConnected = false
            inbox.close()
        }
    }

    private suspend fun send(packet: ByteArray) {
        Log.d(TAG, "TX ${packet.joinToString("") { "%02x".format(it) }}")
        try {
            runInterruptible(Dispatchers.IO) {
                socket.outputStream.write(packet)
                socket.outputStream.flush()
            }
        } catch (e: IOException) {
            isConnected = false
            throw BmapConnectionException("Send failed", e)
        }
        delay(POST_SEND_DELAY_MS)
    }

    private suspend fun receiveOne(timeoutMs: Long): BmapPacket =
        try {
            withTimeout(timeoutMs) { inbox.receive() }
        } catch (e: TimeoutCancellationException) {
            throw BmapTimeoutException("No response within ${timeoutMs}ms")
        } catch (e: CancellationException) {
            throw e  // caller cancelled — never masquerade as an I/O error
        } catch (e: Exception) {
            throw BmapConnectionException("Connection closed while waiting for response", e)
        }

    override suspend fun request(packet: ByteArray): BmapPacket = requestMutex.withLock {
        requestPending = true
        try {
            drainInbox()
            send(packet)
            receiveOne(FIRST_RESPONSE_TIMEOUT_MS)
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
        try {
            socket.close()
        } catch (_: IOException) {
        }
        readerJob.cancel()
        scope.cancel()
    }
}
