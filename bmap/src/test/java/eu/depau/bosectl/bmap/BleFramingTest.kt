package eu.depau.bosectl.bmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Vectors are real captures against QC Ultra Earbuds 2nd gen over GATT — from
 * Linux/BlueZ at an ATT MTU of 23 and from Android at 247 (`docs/PROTOCOL.md` §15).
 */
private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class BleFramingTest {

    @Test
    fun shortPacketIsOneSegmentWithZeroHeader() {
        // Capture: TX 00 00 01 01 00 -> RX [0.1] STATUS "1.2.0"
        val frames = segmentForBle(bmapPacket(0, 1, Op.GET), mtu = 23)
        assertEquals(1, frames.size)
        assertArrayEquals(hex("00 00 01 01 00"), frames[0])
    }

    @Test
    fun longPacketSplitsExactlyAsCaptured() {
        // Capture at MTU 23: a [0.7] GET carrying a 24-byte junk payload, which
        // the firmware reassembled and answered with the serial number.
        val packet = hex("00 07 01 18") + ByteArray(24) { it.toByte() }
        val frames = segmentForBle(packet, mtu = 23)
        assertEquals(2, frames.size)
        assertArrayEquals(hex("10 00 07 01 18 00 01 02 03 04 05 06 07 08 09 0a 0b 0c 0d 0e"), frames[0])
        assertArrayEquals(hex("11 0f 10 11 12 13 14 15 16 17"), frames[1])
    }

    @Test
    fun chunkSizeLeavesRoomForAttAndSegmentHeaders() {
        assertEquals(19, bleChunkSize(23))     // the Connect IQ worst case
        assertEquals(243, bleChunkSize(247))   // what Android negotiated
    }

    @Test
    fun packetTooLargeForSixteenSegmentsIsRejected() {
        val tooBig = ByteArray(16 * 19 + 1)
        val e = runCatching { segmentForBle(tooBig, mtu = 23) }.exceptionOrNull()
        assertEquals(BmapException::class.java, e?.javaClass)
    }

    @Test
    fun everySegmentedMessageRoundTrips() {
        val packet = bmapPacket(31, 6, Op.SETGET, ByteArray(40) { (it * 3).toByte() })
        val reassembler = BleReassembler()
        val frames = segmentForBle(packet, mtu = 23)
        val complete = frames.mapNotNull { reassembler.accept(it) }
        assertEquals(1, complete.size)
        assertArrayEquals(packet, complete.single())
    }

    @Test
    fun singleNotificationParsesToItsPacket() {
        // Capture: RX 00 00 01 03 05 31 2e 32 2e 30
        val message = BleReassembler().accept(hex("00 00 01 03 05 31 2e 32 2e 30"))!!
        val packet = parseAllPackets(message).single()
        assertEquals(0, packet.fblock)
        assertEquals(1, packet.func)
        assertEquals(Op.STATUS, packet.op)
        assertEquals("1.2.0", String(packet.payload))
    }

    @Test
    fun longNotificationArrivesUnsegmentedWhenTheMtuAllows() {
        // Capture at MTU 247: a 36-byte [4.4] reply, segment header 0x00 — the
        // firmware does not segment when it does not have to.
        val raw = hex(
            "00 04 04 03 1f 01 5c f3 70 9b 8c ff 08 8b c8 51 d4 8d 84 2f 57 30 " +
                "19 cd 38 f9 f5 24 6f 9a 94 e7 0b db b1 26"
        )
        val message = BleReassembler().accept(raw)!!
        val packet = parseAllPackets(message).single()
        assertEquals(4, packet.fblock)
        assertEquals(4, packet.func)
        assertEquals(31, packet.payload.size)
        // Byte 0 is the connected mask: entry 0 (the laptop) was the only one up.
        assertEquals(0x01, packet.payload[0].toInt())
    }

    @Test
    fun incompleteMessageYieldsNothing() {
        val reassembler = BleReassembler()
        assertNull(reassembler.accept(hex("10 00 07 01 18 00 01 02")))
    }

    @Test
    fun restartedSequenceDoesNotSpliceTwoMessages() {
        val reassembler = BleReassembler()
        // First segment of a two-segment message, then the device (or a push)
        // starts a fresh single-frame message instead of finishing it.
        assertNull(reassembler.accept(hex("10 00 07 01 18 00 01 02")))
        val message = reassembler.accept(hex("00 00 01 03 05 31 2e 32 2e 30"))!!
        assertEquals("1.2.0", String(parseAllPackets(message).single().payload))
    }

    @Test
    fun strayTailSegmentIsDiscarded() {
        // A tail with no head must not be mistaken for a complete message.
        assertNull(BleReassembler().accept(hex("11 0f 10 11 12")))
    }
}
