package eu.depau.bosectl.bmap

/**
 * Payload parsers/builders, ported from pybmap devices/parsers.py.
 * Pure functions: wire bytes <-> typed values.
 */

private fun Byte.u() = toInt() and 0xFF
private fun Byte.signed() = toInt()

fun parseBattery(payload: ByteArray): BatteryStatus {
    // Repeating 4-byte groups [level, ff, ff, componentId].
    // Real captures — headphones: 50ffff00 (80%, single battery);
    // earbuds: 64ffff01 64ffff02 64ffff04 32ffff03 (L 100, R 100, overall 100, case 50).
    var overall: Int? = null
    var left: Int? = null
    var right: Int? = null
    var case: Int? = null
    var i = 0
    while (i + 3 < payload.size) {
        val level = payload[i].u().takeIf { it <= 100 }
        when (payload[i + 3].u()) {
            0, 4 -> overall = level
            1 -> left = level
            2 -> right = level
            3 -> case = level
        }
        i += 4
    }
    return BatteryStatus(overall, left, right, case, payload)
}

fun parseFirmware(payload: ByteArray): String = payload.decodeToString()

/** Device name GET [1.2]: first byte is a flag, name starts at byte 1. */
fun parseProductName(payload: ByteArray): String =
    if (payload.size > 1) payload.copyOfRange(1, payload.size).decodeToString().trimEnd('\u0000') else ""

/** EQ GET [1.7]: 4-byte groups [min, max, current, bandId], values are signed bytes. */
fun parseEq(payload: ByteArray): List<EqBand> {
    val bands = mutableListOf<EqBand>()
    var i = 0
    while (i + 3 < payload.size) {
        bands.add(
            EqBand(
                bandId = payload[i + 3].u(),
                minVal = payload[i].signed(),
                maxVal = payload[i + 1].signed(),
                current = payload[i + 2].signed(),
            )
        )
        i += 4
    }
    return bands
}

fun buildEqBand(value: Int, bandId: Int): ByteArray = byteArrayOf(value.toByte(), bandId.toByte())

/**
 * Buttons [1.9]: [buttonId, event, action, supportedMask(4B, big-endian)].
 * Bit N of the mask = action N is selectable for this button/event pair.
 * Verified on QC Ultra Earbuds: mask 000b4002 -> {1 VPA, 14 Disabled,
 * 16 Spotify, 17 ModesCarousel, 19 SpatialAudio}, matching the official app.
 */
fun parseButtons(payload: ByteArray): ButtonMapping? {
    if (payload.size < 3) return null
    var mask = 0L
    for (i in 3 until minOf(7, payload.size)) {
        mask = (mask shl 8) or payload[i].u().toLong()
    }
    val supported = (0 until 32).filter { mask and (1L shl it) != 0L }
    return ButtonMapping(payload[0].u(), payload[1].u(), payload[2].u(), supported)
}

fun buildButtons(buttonId: Int, event: Int, action: Int): ByteArray =
    byteArrayOf(buttonId.toByte(), event.toByte(), action.toByte())

/**
 * Multipoint GET [1.10]: bit 0 = enabled, bits 1-2 are capability flags that
 * stay set. Verified on QC Ultra Earbuds: 07 = on, 06 = off.
 * (pybmap reads bit 0x02, which is wrong — it never changes.)
 */
fun parseMultipoint(payload: ByteArray): Boolean =
    payload.isNotEmpty() && (payload[0].u() and 0x01) != 0

/** Multipoint SETGET: preserve the capability flags, flip bit 0. */
fun buildMultipoint(currentByte: Int, enabled: Boolean): ByteArray =
    byteArrayOf(((currentByte and 0xFE) or (if (enabled) 1 else 0)).toByte())

fun parseBool(payload: ByteArray): Boolean = payload.isNotEmpty() && payload[0] != 0.toByte()

fun buildToggle(enabled: Boolean): ByteArray = byteArrayOf(if (enabled) 1 else 0)

/** Sidetone GET [1.11]: level in byte 1. */
fun parseSidetone(payload: ByteArray): Sidetone =
    Sidetone.fromValue(if (payload.size >= 2) payload[1].u() else 0)

/** Sidetone SETGET payload: [persist_flag, level]. */
fun buildSidetone(level: Sidetone): ByteArray = byteArrayOf(1, level.value.toByte())

/** Voice prompts [1.3]: bit 5 = enabled, bits 4-0 = language id. */
fun parseVoicePrompts(payload: ByteArray): Pair<Boolean, Int> {
    if (payload.isEmpty()) return false to 0
    val b = payload[0].u()
    return ((b shr 5) and 1 == 1) to (b and 0x1F)
}

fun buildVoicePrompts(enabled: Boolean, languageId: Int): ByteArray =
    byteArrayOf((((if (enabled) 1 else 0) shl 5) or (languageId and 0x1F)).toByte())

/**
 * ModeConfig [31.6] STATUS, 48 bytes:
 *   [0] modeIndex, [1:3] voicePrompt, [3]=editable [4]=configured [5]=starred,
 *   [6:38] name (32B), [38:42] unknown, [42] cnc, [43] autoCnc, [44] spatial,
 *   [45] windBlock, [46] unknown, [47] ancToggle
 * Also handles the 40-byte SETGET echo (no flag bytes, settings at [35..39]).
 */
fun parseModeConfig(payload: ByteArray): ModeConfig? {
    if (payload.size < 6) return null
    val modeIdx = payload[0].u()
    val promptId = payload[2].u()  // byte 1 is always 0

    fun name(from: Int, to: Int) =
        payload.copyOfRange(from, minOf(to, payload.size))
            .takeWhile { it != 0.toByte() }.toByteArray().decodeToString()

    return when {
        payload.size >= 48 -> ModeConfig(
            modeIdx = modeIdx,
            prompt = Prompt.fromId(promptId), promptId = promptId,
            name = name(6, 38),
            cncLevel = payload[42].u(), autoCnc = payload[43] != 0.toByte(),
            spatial = Spatial.fromValue(payload[44].u()),
            windBlock = payload[45] != 0.toByte(), ancToggle = payload[47] != 0.toByte(),
            editable = payload[3] != 0.toByte(), configured = payload[4] != 0.toByte(),
            starred = payload[5] != 0.toByte(),
        )
        payload.size >= 40 -> ModeConfig(
            modeIdx = modeIdx,
            prompt = Prompt.fromId(promptId), promptId = promptId,
            name = name(3, 35),
            cncLevel = payload[35].u(), autoCnc = payload[36] != 0.toByte(),
            spatial = Spatial.fromValue(payload[37].u()),
            windBlock = payload[38] != 0.toByte(), ancToggle = payload[39] != 0.toByte(),
            editable = true, configured = true, starred = false,
        )
        else -> null
    }
}

/** ModeConfig [31.6] SETGET payload (40 bytes) — user slots 4-10 only. */
fun buildModeConfig(
    modeIdx: Int,
    name: String,
    promptId: Int = 0,
    cncLevel: Int = 0,
    autoCnc: Boolean = false,
    spatial: Spatial = Spatial.OFF,
    windBlock: Boolean = true,
    ancToggle: Boolean = true,
): ByteArray = byteArrayOf(modeIdx.toByte(), 0, promptId.toByte()) +
        encodeModeName(name) +
        byteArrayOf(
            cncLevel.toByte(), if (autoCnc) 1 else 0, spatial.value.toByte(),
            if (windBlock) 1 else 0, if (ancToggle) 1 else 0,
        )

/**
 * Favorites [31.8]: [slot_count, bitmask_hi, bitmask_lo].
 * Bit N of the 16-bit big-endian mask = mode index N is starred.
 * Observed: 0b 01 83 -> 11 slots, starred {0, 1, 7, 8}.
 */
fun parseFavorites(payload: ByteArray): Favorites? {
    if (payload.size < 3) return null
    val mask = (payload[1].u() shl 8) or payload[2].u()
    return Favorites(
        slotCount = payload[0].u(),
        starred = (0 until 16).filter { mask and (1 shl it) != 0 }.toSet(),
    )
}

// ponytail: SETGET write format assumed to mirror GET — verify on device
fun buildFavorites(favorites: Favorites): ByteArray {
    var mask = 0
    for (idx in favorites.starred) mask = mask or (1 shl idx)
    return byteArrayOf(favorites.slotCount.toByte(), (mask shr 8).toByte(), (mask and 0xFF).toByte())
}

/** AudioModesSettingsConfig [31.10]: [cnc, autoCnc, spatial, wind, anc]. */
fun parseAudioSettings(payload: ByteArray): AudioSettings? {
    if (payload.size < 5) return null
    return AudioSettings(
        cncLevel = payload[0].u(),
        autoCnc = payload[1] != 0.toByte(),
        spatial = Spatial.fromValue(payload[2].u()),
        windBlock = payload[3] != 0.toByte(),
        ancToggle = payload[4] != 0.toByte(),
    )
}

fun buildAudioSettings(s: AudioSettings): ByteArray = byteArrayOf(
    s.cncLevel.toByte(),
    // autoCnc=1 is rejected by firmware with Runtime error 8; always write 0
    0,
    s.spatial.value.toByte(),
    if (s.windBlock) 1 else 0,
    if (s.ancToggle) 1 else 0,
)

// ── DeviceManagement [4.x] ───────────────────────────────────────────────────

private const val MAC_LEN = 6

/** Wire bytes -> "AA:BB:CC:DD:EE:FF". MACs travel big-endian, as sent. */
fun bytesToMac(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }

fun macToBytes(mac: String): ByteArray =
    mac.split(":").map { it.toInt(16).toByte() }.toByteArray()

/**
 * ListDevices [4.4] STATUS: [connectedMask, mac(6) x n].
 *
 * Byte 0 is a bitmask indexed by position in this frame, *not* a count — with
 * one device connected at index 1 it reads 02, not 01. The order is also not
 * stable between reads (the same two devices were observed swapping places), so
 * this returns mac-to-connected pairs rather than the raw mask: there is then
 * no way for a caller to hold on to a position from an earlier frame.
 *
 * Real captures, same physical state moments apart:
 *   03 5cf3709b8cff 088bc851d48d 842f...  -> both connected
 *   02 088bc851d48d 5cf3709b8cff 842f...  -> reordered, only 5cf3.. connected
 * A payload of length 1 is an empty list.
 */
fun parseDeviceList(payload: ByteArray): List<Pair<String, Boolean>> {
    if (payload.isEmpty()) return emptyList()
    val mask = payload[0].u()
    return (1..payload.size - MAC_LEN step MAC_LEN).mapIndexed { index, offset ->
        bytesToMac(payload.copyOfRange(offset, offset + MAC_LEN)) to
                (mask shr index and 1 == 1)
    }
}

/**
 * Info [4.5] STATUS: [mac(6), flags, b7, b8, (variant), name...].
 *
 * flags: bit0 connected, bit1 isLocalDevice, bit2 isBoseProduct, bit3 isComponent.
 * For a Bose product bytes 7-8 are the product id and the name starts at 10;
 * otherwise 7-8 are the device class and the name starts at 9. The name is
 * UTF-8 running to the end of the payload — no NUL, no length prefix.
 *
 * The class bytes read 02 03 for every non-Bose device on this firmware, phone
 * and laptop alike, so they are not worth surfacing.
 */
fun parseDeviceInfo(payload: ByteArray): PairedDevice? {
    if (payload.size < 9) return null
    val flags = payload[MAC_LEN].u()
    val isBose = flags shr 2 and 1 == 1
    val nameFrom = if (isBose) 10 else 9
    return PairedDevice(
        mac = bytesToMac(payload.copyOfRange(0, MAC_LEN)),
        name = if (payload.size > nameFrom)
            payload.copyOfRange(nameFrom, payload.size).decodeToString() else "",
        connected = flags and 1 == 1,
        isLocalDevice = flags shr 1 and 1 == 1,
        isBoseProduct = isBose,
    )
}

/**
 * Fold a fresh [4.4] list into whatever is already known, keeping names.
 *
 * [4.4] carries MACs but no names, and it reorders between reads, so: the
 * incoming order wins outright, and cached entries are matched **by MAC only,
 * never by position**. Devices not seen before come back with an empty name for
 * the caller to fill in from [4.5]; devices no longer listed are dropped.
 */
fun mergePairedDevices(
    cached: List<PairedDevice>,
    entries: List<Pair<String, Boolean>>,
): List<PairedDevice> {
    val known = cached.associateBy { it.mac }
    return entries.map { (mac, connected) ->
        known[mac]?.copy(connected = connected)
            ?: PairedDevice(
                mac = mac, name = "", connected = connected,
                isLocalDevice = false, isBoseProduct = false,
            )
    }
}

/** ExtendedInfo [4.6] STATUS: [mac(6), pairedMask, connectedMask, 54, 14]. */
fun parseDeviceExtendedInfo(payload: ByteArray): DeviceExtendedInfo? {
    if (payload.size < MAC_LEN + 2) return null
    return DeviceExtendedInfo(
        mac = bytesToMac(payload.copyOfRange(0, MAC_LEN)),
        paired = DeviceProfiles.fromMask(payload[6].u()),
        connected = DeviceProfiles.fromMask(payload[7].u()),
    )
}

/**
 * AudioManagementSource [5.1] STATUS: [00, profileMask, 01, mac(6)] — the
 * device currently holding audio. Read-only: every SETGET shape is rejected
 * (docs/PROTOCOL.md §14).
 */
fun parseActiveSource(payload: ByteArray): String? {
    if (payload.size < 3 + MAC_LEN) return null
    return bytesToMac(payload.copyOfRange(3, 3 + MAC_LEN))
}

/** Connect [4.1] takes a leading address-type byte; disconnect/forget do not. */
fun buildConnectDevice(mac: String): ByteArray = byteArrayOf(0) + macToBytes(mac)

/** [9.2] NotificationByFblock mode byte. */
object NotificationBitmask {
    const val OVERWRITE: Byte = 0
    const val ENABLE: Byte = 1
    const val DISABLE: Byte = 2
}

/**
 * Blocks whose changes the UI cares about: settings, status, device management,
 * audio, modes. Block 4 makes the earbuds push an updated [4.4] on every
 * connect and disconnect, which is what keeps the device list live.
 */
val NOTIFY_BLOCKS = listOf(1, 2, 4, 5, 31)

/**
 * [9.2] payload: [bitmask][4-byte big-endian bitset], bit index = function
 * block id (so AudioModes = 31 sets the top bit).
 */
fun buildNotifyByFblock(mode: Byte, blocks: List<Int>): ByteArray {
    var mask = 0L
    for (b in blocks) mask = mask or (1L shl b)
    return byteArrayOf(
        mode,
        (mask ushr 24).toByte(), (mask ushr 16).toByte(),
        (mask ushr 8).toByte(), mask.toByte(),
    )
}

/** Parse an echoed block bitset back into block ids. */
fun parseNotifyByFblock(payload: ByteArray): List<Int> {
    if (payload.size < 4) return emptyList()
    var mask = 0L
    for (b in payload.takeLast(4)) mask = (mask shl 8) or (b.toLong() and 0xFF)
    return (0..31).filter { mask and (1L shl it) != 0L }
}
