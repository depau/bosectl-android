package eu.depau.bosectl.bmap

import kotlinx.coroutines.flow.SharedFlow

/**
 * Typed API over a BMAP transport, ported from pybmap connection.py.
 * QC Ultra family (qc_ultra2 config). All writes use SETGET or START,
 * which are unauthenticated on the Settings [1.x] and AudioModes [31.x] blocks.
 */
class BmapConnection(private val transport: BmapTransport) : AutoCloseable {

    object Addr {
        val FIRMWARE = 0 to 5
        val PRODUCT_NAME = 1 to 2
        val VOICE_PROMPTS = 1 to 3
        val EQ = 1 to 7
        val BUTTONS = 1 to 9
        val MULTIPOINT = 1 to 10
        val SIDETONE = 1 to 11
        val AUTO_PAUSE = 1 to 24
        val AUTO_ANSWER = 1 to 27
        val AUTO_TRANSPARENCY = 1 to 29
        val TOUCH_CONTROLS = 1 to 34
        val BATTERY = 2 to 2
        val NOTIFY_BY_FBLOCK = 9 to 2
        val PAIRING = 4 to 8
        val POWER = 7 to 4
        val GET_ALL_MODES = 31 to 1
        val CURRENT_MODE = 31 to 3
        val MODE_CONFIG = 31 to 6
        val FAVORITES = 31 to 8
        val AUDIO_SETTINGS = 31 to 10
    }

    val isConnected get() = transport.isConnected
    val unsolicited: SharedFlow<BmapPacket> get() = transport.unsolicited

    private fun checkError(packet: BmapPacket): BmapPacket {
        if (packet.op == Op.ERROR) {
            val code = packet.payload.firstOrNull()?.toInt()?.and(0xFF) ?: -1
            val name = ERROR_NAMES[code] ?: "error $code"
            if (code == 5) throw BmapAuthException("Authentication required: $packet")
            throw BmapDeviceException("$name: $packet", code)
        }
        return packet
    }

    private suspend fun get(addr: Pair<Int, Int>): ByteArray =
        checkError(transport.request(bmapPacket(addr.first, addr.second, Op.GET))).payload

    private suspend fun setGet(addr: Pair<Int, Int>, payload: ByteArray): BmapPacket =
        checkError(transport.request(bmapPacket(addr.first, addr.second, Op.SETGET, payload)))

    private suspend fun start(addr: Pair<Int, Int>, payload: ByteArray = ByteArray(0)): BmapPacket =
        checkError(transport.request(bmapPacket(addr.first, addr.second, Op.START, payload)))

    // ── Reads ────────────────────────────────────────────────────────────────

    suspend fun battery(): BatteryStatus = parseBattery(get(Addr.BATTERY))
    suspend fun firmware(): String = parseFirmware(get(Addr.FIRMWARE))
    suspend fun productName(): String = parseProductName(get(Addr.PRODUCT_NAME))
    suspend fun currentModeIdx(): Int? = get(Addr.CURRENT_MODE).firstOrNull()?.toInt()?.and(0xFF)
    suspend fun eq(): List<EqBand> = parseEq(get(Addr.EQ))
    suspend fun sidetone(): Sidetone = parseSidetone(get(Addr.SIDETONE))
    suspend fun multipoint(): Boolean = parseMultipoint(get(Addr.MULTIPOINT))
    suspend fun autoPause(): Boolean = parseBool(get(Addr.AUTO_PAUSE))
    suspend fun autoAnswer(): Boolean = parseBool(get(Addr.AUTO_ANSWER))

    /** Master enable for the earbud touch surfaces. Verified: [1.34] 01/00. */
    suspend fun touchControls(): Boolean = parseBool(get(Addr.TOUCH_CONTROLS))

    /** Drop ANC while only one bud is worn. Verified: [1.29] 01/00. */
    suspend fun autoTransparency(): Boolean = parseBool(get(Addr.AUTO_TRANSPARENCY))
    suspend fun voicePrompts(): Pair<Boolean, Int> = parseVoicePrompts(get(Addr.VOICE_PROMPTS))
    /** All configurable buttons. Earbuds report two (left + right shortcut). */
    suspend fun buttons(): List<ButtonMapping> =
        transport.requestDrain(bmapPacket(1, 9, Op.GET))
            .filter { it.op == Op.STATUS }
            .mapNotNull { parseButtons(it.payload) }
    suspend fun audioSettings(): AudioSettings? = parseAudioSettings(get(Addr.AUDIO_SETTINGS))
    suspend fun favorites(): Favorites? = parseFavorites(get(Addr.FAVORITES))

    /**
     * One GetAll [31.1] START drain returns the whole AudioModes state:
     * current mode, all 11 mode configs, favorites bitmask, live settings.
     */
    suspend fun snapshot(): DeviceSnapshot {
        val packets = transport.requestDrain(bmapPacket(31, 1, Op.START))
        val modes = mutableListOf<ModeConfig>()
        var currentIdx: Int? = null
        var favorites: Favorites? = null
        var settings: AudioSettings? = null
        for (p in packets) {
            if (p.op != Op.STATUS) continue
            when {
                p.matches(Addr.MODE_CONFIG) && p.payload.size >= 6 ->
                    parseModeConfig(p.payload)?.let { modes.add(it) }
                p.matches(Addr.CURRENT_MODE) && p.payload.isNotEmpty() ->
                    currentIdx = p.payload[0].toInt() and 0xFF
                p.matches(Addr.FAVORITES) -> favorites = parseFavorites(p.payload)
                p.matches(Addr.AUDIO_SETTINGS) -> settings = parseAudioSettings(p.payload)
            }
        }
        return DeviceSnapshot(currentIdx, modes.sortedBy { it.modeIdx }, favorites, settings)
    }

    /**
     * Subscribe to unsolicited STATUS frames for whole function blocks.
     *
     * From the official app: NotificationByFblock is [9.2] and its payload is
     * [NotificationBitmask][function-block bitset]. The bitset is indexed by
     * *function block id* (FunctionBlocksBitSet.setBit uses getFunctionBlockId),
     * and BitSetUtil writes bit i into byte len-(i/8)-1 at position i%8 — i.e. a
     * big-endian integer where bit i has value 2^i.
     *
     * Beware [9.1]: that is NotificationReset, not GetAll — it clears these.
     */
    suspend fun enableNotifications(blocks: List<Int> = NOTIFY_BLOCKS): List<Int> {
        val reply = setGet(Addr.NOTIFY_BY_FBLOCK, buildNotifyByFblock(NotificationBitmask.ENABLE, blocks))
        return parseNotifyByFblock(reply.payload)
    }

    // ── Writes ───────────────────────────────────────────────────────────────

    /** Switch active mode. [announce] plays the voice prompt on the device. */
    suspend fun setMode(idx: Int, announce: Boolean = false) {
        val resp = start(Addr.CURRENT_MODE, byteArrayOf(idx.toByte(), if (announce) 1 else 0))
        if (resp.op != Op.RESULT) throw BmapDeviceException("Mode switch failed: $resp", -1)
    }

    suspend fun setFavorites(favorites: Favorites) {
        setGet(Addr.FAVORITES, buildFavorites(favorites))
    }

    suspend fun setAudioSettings(settings: AudioSettings) {
        setGet(Addr.AUDIO_SETTINGS, buildAudioSettings(settings))
    }

    /**
     * Write a user profile slot (4-10). Presets reject this with Runtime error 8.
     *
     * windBlock is deliberately not a parameter: verified on device, the QC Ultra
     * Earbuds reject *any* ModeConfig write carrying windBlock=1 with Runtime
     * error 8, then report the stored profile as windBlock=1 regardless. So the
     * only writable value is 0, and the field is not user-controllable.
     */
    suspend fun writeMode(
        slot: Int,
        name: String,
        promptId: Int,
        cncLevel: Int,
        spatial: Spatial,
        ancToggle: Boolean = true,
    ) {
        val payload = buildModeConfig(
            modeIdx = slot, name = name, promptId = promptId, cncLevel = cncLevel,
            spatial = spatial, windBlock = false, ancToggle = ancToggle,
        )
        // The device replies with multiple frames; a STATUS echo means success.
        val responses =
            transport.requestDrain(bmapPacket(31, 6, Op.SETGET, payload))
        responses.forEach { checkError(it) }
        if (responses.none { it.op == Op.STATUS })
            throw BmapDeviceException("Mode config write failed", -1)
    }

    /** Reset a user slot back to an empty "None" profile, as the device ships it. */
    suspend fun deleteMode(slot: Int) {
        writeMode(slot, "None", promptId = 0, cncLevel = 10, spatial = Spatial.OFF)
    }

    suspend fun setEq(bass: Int, mid: Int, treble: Int) {
        for ((bandId, value) in listOf(bass, mid, treble).withIndex()) {
            require(value in -10..10) { "EQ value must be -10..10" }
            setGet(Addr.EQ, buildEqBand(value, bandId))
        }
    }

    suspend fun setSidetone(level: Sidetone) {
        setGet(Addr.SIDETONE, buildSidetone(level))
    }

    suspend fun setMultipoint(enabled: Boolean) {
        val current = get(Addr.MULTIPOINT).firstOrNull()?.toInt()?.and(0xFF) ?: 0x06
        setGet(Addr.MULTIPOINT, buildMultipoint(current, enabled))
    }

    suspend fun setAutoPause(enabled: Boolean) {
        setGet(Addr.AUTO_PAUSE, buildToggle(enabled))
    }

    suspend fun setAutoAnswer(enabled: Boolean) {
        setGet(Addr.AUTO_ANSWER, buildToggle(enabled))
    }

    suspend fun setTouchControls(enabled: Boolean) {
        setGet(Addr.TOUCH_CONTROLS, buildToggle(enabled))
    }

    suspend fun setAutoTransparency(enabled: Boolean) {
        setGet(Addr.AUTO_TRANSPARENCY, buildToggle(enabled))
    }

    suspend fun setVoicePrompts(enabled: Boolean, languageId: Int) {
        setGet(Addr.VOICE_PROMPTS, buildVoicePrompts(enabled, languageId))
    }

    suspend fun setButtons(buttonId: Int, event: Int, action: Int) {
        setGet(Addr.BUTTONS, buildButtons(buttonId, event, action))
    }

    /** May be auth-gated (NOTES.md is contradictory) — surface BmapAuthException to UI. */
    suspend fun setName(name: String) {
        setGet(Addr.PRODUCT_NAME, name.encodeToByteArray())
    }

    suspend fun setPairingMode(enabled: Boolean) {
        start(Addr.PAIRING, byteArrayOf(if (enabled) 1 else 0))
    }

    suspend fun powerOff() {
        start(Addr.POWER, byteArrayOf(0))
    }

    /** Send a pre-built packet as-is. For protocol exploration. */
    suspend fun raw(packet: ByteArray, drain: Boolean = false): List<BmapPacket> =
        if (drain) transport.requestDrain(packet) else listOf(transport.request(packet))

    override fun close() = transport.close()
}
