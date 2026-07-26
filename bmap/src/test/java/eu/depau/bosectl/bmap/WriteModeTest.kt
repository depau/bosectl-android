package eu.depau.bosectl.bmap

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

/** Captures what the connection puts on the wire. */
private class RecordingTransport(private val reply: BmapPacket) : BmapTransport {
    val sent = mutableListOf<ByteArray>()
    override val unsolicited: SharedFlow<BmapPacket> = MutableSharedFlow()
    override val isConnected = true
    override suspend fun request(packet: ByteArray): BmapPacket {
        sent.add(packet); return reply
    }
    override suspend fun requestDrain(packet: ByteArray): List<BmapPacket> {
        sent.add(packet); return listOf(reply)
    }
    override fun close() = Unit
}

class WriteModeTest {
    /**
     * Regression: the earbuds reject any ModeConfig write with windBlock=1
     * (Runtime error 8), so profile saving silently failed. Verified on device.
     */
    @Test
    fun profileWriteNeverSetsWindBlock() = runBlocking {
        val echo = BmapPacket(31, 6, Op.STATUS, ByteArray(48))
        val transport = RecordingTransport(echo)
        BmapConnection(transport).writeMode(
            slot = 9, name = "Test", promptId = 12, cncLevel = 5, spatial = Spatial.OFF,
        )
        val payload = transport.sent.single().let { it.copyOfRange(4, it.size) }
        assertEquals(40, payload.size)
        assertEquals("windBlock must be 0", 0, payload[38].toInt())
        assertEquals("mode index", 9, payload[0].toInt())
        assertEquals("prompt", 12, payload[2].toInt())
        assertEquals("cnc", 5, payload[35].toInt())
    }

    @Test
    fun deleteResetsSlotToShippedState() = runBlocking {
        val transport = RecordingTransport(BmapPacket(31, 6, Op.STATUS, ByteArray(48)))
        BmapConnection(transport).deleteMode(9)
        val payload = transport.sent.single().let { it.copyOfRange(4, it.size) }
        assertEquals("windBlock must be 0", 0, payload[38].toInt())
        assertEquals("None", payload.copyOfRange(3, 35)
            .takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
    }
}
