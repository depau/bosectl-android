package eu.depau.bosectl.bmap

import kotlinx.coroutines.flow.SharedFlow
import java.io.Closeable

interface BmapTransport : Closeable {
    /** Send a packet and return the next response frame. */
    suspend fun request(packet: ByteArray): BmapPacket

    /** Send a packet and collect response frames until the device goes silent. */
    suspend fun requestDrain(packet: ByteArray): List<BmapPacket>

    /** Frames the device pushes while no request is pending (e.g. gesture mode switch). */
    val unsolicited: SharedFlow<BmapPacket>

    val isConnected: Boolean
}
