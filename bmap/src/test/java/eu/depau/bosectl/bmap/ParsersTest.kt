package eu.depau.bosectl.bmap

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vectors are real captures from `bosectl dump` (profiles.txt) against
 * QC Ultra Earbuds 2nd gen, firmware-verified against pybmap's parsing.
 */
private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class ProtocolTest {
    @Test
    fun packetRoundTrip() {
        val raw = bmapPacket(31, 3, Op.START, byteArrayOf(7, 0))
        assertArrayEquals(byteArrayOf(31, 3, 5, 2, 7, 0), raw)
        val parsed = parsePacket(raw)!!
        assertEquals(31, parsed.fblock)
        assertEquals(3, parsed.func)
        assertEquals(Op.START, parsed.op)
        assertArrayEquals(byteArrayOf(7, 0), parsed.payload)
    }

    @Test
    fun parseAllSplitsConcatenatedFrames() {
        val stream = bmapPacket(31, 3, Op.STATUS, byteArrayOf(0)) +
                bmapPacket(31, 8, Op.STATUS, hex("0b0183")) +
                bmapPacket(31, 10, Op.STATUS, hex("0000000001"))
        val packets = parseAllPackets(stream)
        assertEquals(3, packets.size)
        assertEquals(8, packets[1].func)
        // Truncated tail is dropped, earlier frames kept
        val truncated = parseAllPackets(stream.copyOfRange(0, stream.size - 2))
        assertEquals(2, truncated.size)
    }

    @Test
    fun modeNameEncoding() {
        val buf = encodeModeName("Esterno")
        assertEquals(32, buf.size)
        assertEquals("Esterno", buf.takeWhile { it != 0.toByte() }.toByteArray().decodeToString())
        // 31-byte truncation always leaves a null terminator
        val long = encodeModeName("x".repeat(64))
        assertEquals(0, long[31].toInt())
    }
}

class ModeConfigTest {
    @Test
    fun presetQuietStarred() {
        // profiles.txt mode 0: flags 00 00 01 (starred), preset
        val cfg = parseModeConfig(
            hex("000001000001517569657400000000000000000000000000000000000000000000000000000000000000000000000001")
        )!!
        assertEquals(0, cfg.modeIdx)
        assertEquals(Prompt.QUIET, cfg.prompt)
        assertEquals("Quiet", cfg.name)
        assertFalse(cfg.editable)
        assertFalse(cfg.configured)
        assertTrue(cfg.starred)
        assertEquals(0, cfg.cncLevel)
        assertTrue(cfg.ancToggle)
        assertFalse(cfg.windBlock)
        assertEquals(Spatial.OFF, cfg.spatial)
    }

    @Test
    fun customEsternoStarred() {
        // profiles.txt mode 7: "Esterno", prompt OUTDOOR, cnc 4, wind on, starred
        val cfg = parseModeConfig(
            hex("07000801010145737465726e6f0000000000000000000000000000000000000000000000000000000015040000010001")
        )!!
        assertEquals(7, cfg.modeIdx)
        assertEquals(Prompt.OUTDOOR, cfg.prompt)
        assertEquals("Esterno", cfg.name)
        assertTrue(cfg.editable)
        assertTrue(cfg.configured)
        assertTrue(cfg.starred)
        assertEquals(4, cfg.cncLevel)
        assertFalse(cfg.autoCnc)
        assertEquals(Spatial.OFF, cfg.spatial)
        assertTrue(cfg.windBlock)
        assertTrue(cfg.ancToggle)
    }

    @Test
    fun customLavoroNotStarredSpatialStill() {
        // profiles.txt mode 5: "Lavoro", prompt WORK, cnc 7, spatial 1, not starred
        val cfg = parseModeConfig(
            hex("05000b0101004c61766f726f000000000000000000000000000000000000000000000000000000000015070001010001")
        )!!
        assertEquals(Prompt.WORK, cfg.prompt)
        assertEquals("Lavoro", cfg.name)
        assertEquals(7, cfg.cncLevel)
        assertEquals(Spatial.STILL, cfg.spatial)
        assertFalse(cfg.starred)
        assertFalse(cfg.isFreeSlot)
    }

    @Test
    fun emptySlotIsFree() {
        // profiles.txt mode 9: "None", editable, unconfigured
        val cfg = parseModeConfig(
            hex("0900000100004e6f6e6500000000000000000000000000000000000000000000000000000000000000150a0000010001")
        )!!
        assertTrue(cfg.isFreeSlot)
        assertEquals(Prompt.NONE, cfg.prompt)
    }

    @Test
    fun buildMatchesWireFormat() {
        val payload = buildModeConfig(
            modeIdx = 7, name = "Esterno", promptId = Prompt.OUTDOOR.id,
            cncLevel = 4, spatial = Spatial.OFF, windBlock = true, ancToggle = true,
        )
        assertEquals(40, payload.size)
        assertArrayEquals(hex("070008"), payload.copyOfRange(0, 3))
        assertArrayEquals(hex("0400000101"), payload.copyOfRange(35, 40))
        // 40-byte SETGET echo parses back
        val echo = parseModeConfig(payload)!!
        assertEquals("Esterno", echo.name)
        assertEquals(4, echo.cncLevel)
    }
}

class FavoritesTest {
    @Test
    fun parseRealBitmask() {
        // profiles.txt [31.8]: 0b0183 -> 11 slots, starred = Quiet, Aware, Esterno, Corsa
        val fav = parseFavorites(hex("0b0183"))!!
        assertEquals(11, fav.slotCount)
        assertEquals(setOf(0, 1, 7, 8), fav.starred)
    }

    @Test
    fun buildRoundTrip() {
        val fav = Favorites(11, setOf(0, 1, 7, 8))
        assertArrayEquals(hex("0b0183"), buildFavorites(fav))
        assertEquals(fav, parseFavorites(buildFavorites(fav)))
    }
}

class SettingsParsersTest {
    @Test
    fun audioSettings() {
        // profiles.txt [31.10]: cnc 0, autoCnc off, spatial off, wind off, anc on
        val s = parseAudioSettings(hex("0000000001"))!!
        assertEquals(0, s.cncLevel)
        assertFalse(s.autoCnc)
        assertEquals(Spatial.OFF, s.spatial)
        assertFalse(s.windBlock)
        assertTrue(s.ancToggle)
        // Builder forces autoCnc=0 (firmware rejects 1 with Runtime error 8)
        assertArrayEquals(
            hex("0500020001"),
            buildAudioSettings(AudioSettings(5, true, Spatial.MOTION, false, true))
        )
    }

    @Test
    fun battery() {
        // NOTES.md headphones capture: single 80% battery
        val b = parseBattery(hex("50ffff00"))
        assertEquals(80, b.overall)
        assertNull(b.left)
        assertNull(b.right)
        assertNull(b.case)
        // Real earbuds capture: L 100, R 100, overall 100, case 50
        val e = parseBattery(hex("64ffff0164ffff0264ffff0432ffff03"))
        assertEquals(100, e.left)
        assertEquals(100, e.right)
        assertEquals(50, e.case)
        assertEquals(100, e.overall)
    }

    @Test
    fun eq() {
        // NOTES.md: f60a0500 f60a0001 f60af702 = bass +5, mid 0, treble -9
        val bands = parseEq(hex("f60a0500f60a0001f60af702"))
        assertEquals(3, bands.size)
        assertEquals(-10, bands[0].minVal)
        assertEquals(10, bands[0].maxVal)
        assertEquals(5, bands[0].current)
        assertEquals(0, bands[1].current)
        assertEquals(-9, bands[2].current)
        assertEquals(2, bands[2].bandId)
    }

    @Test
    fun voicePromptsRoundTrip() {
        val (enabled, lang) = parseVoicePrompts(buildVoicePrompts(true, 3))
        assertTrue(enabled)
        assertEquals(3, lang)
        assertFalse(parseVoicePrompts(buildVoicePrompts(false, 1)).first)
    }

    @Test
    fun sidetoneAndToggles() {
        assertEquals(Sidetone.MEDIUM, parseSidetone(hex("01020f")))  // NOTES.md capture
        assertArrayEquals(hex("0103"), buildSidetone(Sidetone.LOW))
        // Verified on device: 07 = multipoint on, 06 = off (bit 0)
        assertTrue(parseMultipoint(hex("07")))
        assertFalse(parseMultipoint(hex("06")))
        assertArrayEquals(hex("07"), buildMultipoint(0x06, true))
        assertArrayEquals(hex("06"), buildMultipoint(0x07, false))
    }

    @Test
    fun buttonsFromRealCapture() {
        // Real earbuds captures: left shortcut = modes carousel (17),
        // right shortcut = immersive audio (19); mask 000b4002.
        val left = parseButtons(hex("040911000b4002"))!!
        assertEquals(4, left.buttonId)
        assertEquals(9, left.event)
        assertEquals(17, left.action)
        val right = parseButtons(hex("030913000b4002"))!!
        assertEquals(3, right.buttonId)
        assertEquals(19, right.action)
        // Mask is a big-endian u32; bit N = action N. Matches the official
        // app's option list exactly.
        assertEquals(listOf(1, 14, 16, 17, 19), left.supportedActions)
    }
}
