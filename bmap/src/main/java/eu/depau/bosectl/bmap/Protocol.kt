package eu.depau.bosectl.bmap

/**
 * BMAP (Bose Media Application Protocol) packet codec.
 *
 * Packet format: [fblock_id, function_id, flags, payload_length, ...payload]
 * Flags byte: (device_id << 6) | (port_num << 4) | (operator & 0x0F)
 */
object Op {
    const val SET = 0
    const val GET = 1
    const val SETGET = 2
    const val STATUS = 3
    const val ERROR = 4
    const val START = 5
    const val RESULT = 6
    const val PROCESSING = 7
}

val ERROR_NAMES = mapOf(
    0 to "Unknown", 1 to "Length", 2 to "Chksum", 3 to "FblockNotSupp",
    4 to "FuncNotSupp", 5 to "OpNotSupp(auth)", 6 to "InvalidData",
    7 to "DataUnavail", 8 to "Runtime", 9 to "Timeout", 10 to "InvalidState",
    15 to "InvalidTransition", 20 to "InsecureTransport",
)

data class BmapPacket(val fblock: Int, val func: Int, val op: Int, val payload: ByteArray) {
    fun matches(addr: Pair<Int, Int>) = fblock == addr.first && func == addr.second

    override fun equals(other: Any?) = other is BmapPacket && fblock == other.fblock &&
            func == other.func && op == other.op && payload.contentEquals(other.payload)

    override fun hashCode() = ((fblock * 31 + func) * 31 + op) * 31 + payload.contentHashCode()

    override fun toString() =
        "[%d.%d] op=%d: %s".format(fblock, func, op, payload.joinToString("") { "%02x".format(it) })
}

fun bmapPacket(fblock: Int, func: Int, op: Int, payload: ByteArray = ByteArray(0)): ByteArray =
    byteArrayOf(fblock.toByte(), func.toByte(), (op and 0x0F).toByte(), payload.size.toByte()) + payload

fun parsePacket(data: ByteArray, offset: Int = 0): BmapPacket? {
    if (data.size - offset < 4) return null
    val length = data[offset + 3].toInt() and 0xFF
    if (offset + 4 + length > data.size) return null
    return BmapPacket(
        fblock = data[offset].toInt() and 0xFF,
        func = data[offset + 1].toInt() and 0xFF,
        op = data[offset + 2].toInt() and 0x0F,
        payload = data.copyOfRange(offset + 4, offset + 4 + length),
    )
}

/** Split concatenated BMAP frames (e.g. a GetAll drain) into packets. */
fun parseAllPackets(data: ByteArray): List<BmapPacket> {
    val packets = mutableListOf<BmapPacket>()
    var pos = 0
    while (pos + 4 <= data.size) {
        val packet = parsePacket(data, pos) ?: break
        packets.add(packet)
        pos += 4 + packet.payload.size
    }
    return packets
}

/** Mode names are stored by the firmware as fixed 32-byte null-padded UTF-8. */
fun encodeModeName(name: String): ByteArray {
    val buf = ByteArray(32)
    val bytes = name.encodeToByteArray()
    val end = minOf(bytes.size, 31)
    bytes.copyInto(buf, 0, 0, end)
    return buf
}
