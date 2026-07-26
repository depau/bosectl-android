package eu.depau.bosectl.bmap

/**
 * Bose's BLE segmentation layer — the only difference between BMAP over GATT and
 * BMAP over RFCOMM (`docs/PROTOCOL.md` §15).
 *
 * Every ATT write and every notification is `[seg] + chunk`, where
 * `seg = (lastIndex shl 4) or index`. A whole message therefore fits in at most
 * 16 segments, and `0x00` is the common case of a single unsegmented frame.
 * Reassembled, the buffer is one or more ordinary BMAP frames — parse it with
 * [parseAllPackets], exactly as for a RFCOMM read.
 */

/** Segments are indexed by one nibble each, so a message is 16 segments at most. */
const val BLE_MAX_SEGMENTS = 16

/** Usable BMAP bytes per notification/write at a given ATT MTU: 3 ATT + 1 seg. */
fun bleChunkSize(mtu: Int): Int = (mtu - 4).coerceAtLeast(1)

/**
 * Split [packet] into writable frames for an ATT MTU of [mtu].
 *
 * @throws BmapException if the packet needs more than [BLE_MAX_SEGMENTS].
 */
fun segmentForBle(packet: ByteArray, mtu: Int): List<ByteArray> {
    val chunk = bleChunkSize(mtu)
    val count = (packet.size + chunk - 1) / chunk
    if (count > BLE_MAX_SEGMENTS) {
        throw BmapException("Packet of ${packet.size}B needs $count segments at MTU $mtu")
    }
    val last = (count - 1).coerceAtLeast(0)
    return (0 until count.coerceAtLeast(1)).map { i ->
        val from = i * chunk
        val to = minOf(from + chunk, packet.size)
        byteArrayOf(((last shl 4) or i).toByte()) + packet.copyOfRange(from, to)
    }
}

/**
 * Accumulates notification frames until a message is complete.
 *
 * Not thread-safe: feed it from one place (the GATT callback thread).
 */
class BleReassembler {
    private val segments = mutableListOf<ByteArray>()
    private var expectedIndex = 0

    /** @return the reassembled message, or null while more segments are due. */
    fun accept(frame: ByteArray): ByteArray? {
        if (frame.isEmpty()) return null
        val header = frame[0].toInt() and 0xFF
        val last = header shr 4
        val index = header and 0x0F
        val body = frame.copyOfRange(1, frame.size)

        // A mid-message restart (dropped tail, or a push interleaved with a
        // reply) would otherwise splice two messages into one unparsable buffer.
        if (index != expectedIndex) {
            segments.clear()
            if (index != 0) {
                expectedIndex = 0
                return null
            }
        }
        segments.add(body)
        if (index == last) {
            val message = if (segments.size == 1) segments[0] else segments.reduce(ByteArray::plus)
            segments.clear()
            expectedIndex = 0
            return message
        }
        expectedIndex = index + 1
        return null
    }

    fun reset() {
        segments.clear()
        expectedIndex = 0
    }
}
