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

/**
 * DeviceManagement [4.x] and AudioManagementSource [5.1].
 *
 * All vectors are real captures taken over RFCOMM from a laptop while the
 * earbuds were also connected to a phone. Device state at capture time:
 * "Frigo" 5C:F3:70:9B:8C:FF is the laptop running the probe (so isLocalDevice),
 * "Pixel 9 Pro" 08:8B:C8:51:D4:8D is the phone, the rest are known but idle.
 */
class DeviceManagementTest {
    private val laptop = "5C:F3:70:9B:8C:FF"
    private val phone = "08:8B:C8:51:D4:8D"

    @Test
    fun macRoundTrip() {
        assertEquals(laptop, bytesToMac(macToBytes(laptop)))
        assertArrayEquals(hex("5cf3709b8cff"), macToBytes(laptop))
    }

    @Test
    fun deviceListMaskIsPositional() {
        // Both laptop and phone connected: mask 03 = entries 0 and 1.
        val devices = parseDeviceList(
            hex(
                "03 5cf3709b8cff 088bc851d48d 842f573019cd " +
                        "38f9f5246f9a 94e70bdbb126 001a7dda7113"
            )
        )
        assertEquals(6, devices.size)
        assertEquals(laptop to true, devices[0])
        assertEquals(phone to true, devices[1])
        assertFalse(devices[2].second)
    }

    /**
     * The same physical state as above moments later, after the phone was
     * dropped: the list came back in a different order and the mask followed
     * the new positions. A count would have read 01 here, not 02 — this pair of
     * vectors is what proves byte 0 is a positional bitmask, and why the mask
     * must never be applied to MACs from an earlier frame.
     */
    @Test
    fun deviceListReordersBetweenReads() {
        val devices = parseDeviceList(
            hex(
                "02 088bc851d48d 5cf3709b8cff 842f573019cd " +
                        "38f9f5246f9a 94e70bdbb126 001a7dda7113"
            )
        )
        assertEquals(phone to false, devices[0])
        assertEquals(laptop to true, devices[1])
        assertEquals(1, devices.count { it.second })
    }

    @Test
    fun emptyDeviceListIsMaskOnly() {
        assertTrue(parseDeviceList(hex("00")).isEmpty())
        assertTrue(parseDeviceList(ByteArray(0)).isEmpty())
    }

    @Test
    fun deviceInfoNamesAndFlags() {
        val local = parseDeviceInfo(hex("5cf3709b8cff 03 0203 467269676f"))!!
        assertEquals(laptop, local.mac)
        assertEquals("Frigo", local.name)
        assertTrue(local.connected)
        assertTrue(local.isLocalDevice)
        assertFalse(local.isBoseProduct)

        val idle = parseDeviceInfo(hex("088bc851d48d 00 0203 506978656c20392050726f"))!!
        assertEquals("Pixel 9 Pro", idle.name)
        assertFalse(idle.connected)
        assertFalse(idle.isLocalDevice)

        // Names run to the end of the payload — no NUL, no length prefix.
        val long = parseDeviceInfo(
            hex("842f573019cd 00 0203 496c696164436f72702d4a3943364852584b4858")
        )!!
        assertEquals("IliadCorp-J9C6HRXKHX", long.name)
    }

    @Test
    fun deviceInfoRejectsShortPayload() {
        assertNull(parseDeviceInfo(hex("5cf3709b8cff")))
    }

    @Test
    fun extendedInfoProfileMasks() {
        val connected = parseDeviceExtendedInfo(hex("5cf3709b8cff 0f 0f 5414"))!!
        assertTrue(connected.paired.a2dp)
        assertTrue(connected.paired.spp)
        assertFalse(connected.paired.iap)
        assertTrue(connected.connected.avrcp)

        // REDPINE_GATT_V02: a2dp+avrcp+spp paired, nothing connected, no HFP.
        val idle = parseDeviceExtendedInfo(hex("38f9f5246f9a 0d 00 5414"))!!
        assertTrue(idle.paired.a2dp)
        assertFalse(idle.paired.hfp)
        assertTrue(idle.paired.avrcp)
        assertFalse(idle.connected.a2dp)
    }

    @Test
    fun activeSourceIsTheTrailingMac() {
        assertEquals(laptop, parseActiveSource(hex("000f01 5cf3709b8cff")))
        assertNull(parseActiveSource(hex("000f01")))
    }

    /**
     * The merge has to survive the reorder, because that is what actually
     * happens between two reads: names learned from [4.5] must follow their MAC
     * to its new position, and the connected flags must come from the new frame
     * rather than from the cached entries.
     */
    @Test
    fun mergeKeepsNamesAcrossAReorder() {
        val cached = listOf(
            PairedDevice(laptop, "Frigo", connected = true, isLocalDevice = true, isBoseProduct = false),
            PairedDevice(phone, "Pixel 9 Pro", connected = true, isLocalDevice = false, isBoseProduct = false),
        )
        // Phone first now, and only the laptop still connected.
        val merged = mergePairedDevices(cached, listOf(phone to false, laptop to true))

        assertEquals(listOf(phone, laptop), merged.map { it.mac })
        assertEquals(listOf("Pixel 9 Pro", "Frigo"), merged.map { it.name })
        assertFalse(merged[0].connected)
        assertTrue(merged[1].connected)
        assertTrue(merged[1].isLocalDevice)
    }

    @Test
    fun mergeAddsUnknownDevicesUnnamedAndDropsVanishedOnes() {
        val cached = listOf(
            PairedDevice(laptop, "Frigo", connected = true, isLocalDevice = true, isBoseProduct = false)
        )
        val merged = mergePairedDevices(cached, listOf("94:E7:0B:DB:B1:26" to false))
        assertEquals(1, merged.size)
        assertEquals("94:E7:0B:DB:B1:26", merged[0].mac)
        // Empty name is the signal to go read [4.5] for it.
        assertEquals("", merged[0].name)
    }

    @Test
    fun connectPayloadCarriesAddressTypeByte() {
        // [4.1] takes a leading 00; [4.2] and [4.3] take a bare MAC.
        assertArrayEquals(hex("00 088bc851d48d"), buildConnectDevice(phone))
        assertArrayEquals(hex("088bc851d48d"), macToBytes(phone))
    }
}
