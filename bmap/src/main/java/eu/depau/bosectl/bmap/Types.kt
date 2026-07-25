package eu.depau.bosectl.bmap

/**
 * Voice prompt / mode icon identifier. The same enum drives both the voice
 * prompt spoken on mode switch and the icon shown in the Bose app.
 * Wire format is a byte pair; byte1 is always 0, byte2 is the id.
 */
enum class Prompt(val id: Int) {
    NONE(0), QUIET(1), AWARE(2), TRANSPARENT(3), TRANSPARENCY(4), MASKING(5),
    COMFORT(6), COMMUTE(7), OUTDOOR(8), WORKOUT(9), HOME(10), WORK(11),
    MUSIC(12), FOCUS(13), RELAX(14), FLIGHT(15), AIRPORT(16), DRIVING(17),
    TRAINING(18), GYM(19), RUN(20), WALK(21), HIKE(22), TALK(23), CALL(24),
    WHISPER(25), HEARING(26), LEARN(27), PODCAST(28), AUDIOBOOK(29), CALM(30),
    SLEEP(31), MEDITATE(32), YOGA(33), IMMERSION(34), STEREO(35), CINEMA(36);

    companion object {
        fun fromId(id: Int): Prompt? = entries.firstOrNull { it.id == id }
    }
}

/** Spatial audio ("immersive audio" / head tracking) mode. */
enum class Spatial(val value: Int) {
    OFF(0), STILL(1), MOTION(2);  // wire names: off / fixedToRoom / fixedToHead

    companion object {
        fun fromValue(value: Int): Spatial = entries.firstOrNull { it.value == value } ?: OFF
    }
}

enum class Sidetone(val value: Int) {
    OFF(0), HIGH(1), MEDIUM(2), LOW(3);

    companion object {
        fun fromValue(value: Int): Sidetone = entries.firstOrNull { it.value == value } ?: OFF
    }
}

val VOICE_LANGUAGES = mapOf(
    0 to "UK English", 1 to "US English", 2 to "French", 3 to "Italian",
    4 to "German", 5 to "EU Spanish", 6 to "MX Spanish", 7 to "BR Portuguese",
    8 to "Mandarin", 9 to "Korean", 10 to "Russian", 11 to "Polish",
    12 to "Hebrew", 13 to "Turkish", 14 to "Dutch", 15 to "Japanese",
    16 to "Cantonese", 17 to "Arabic", 18 to "Swedish", 19 to "Danish",
    20 to "Norwegian", 21 to "Finnish", 22 to "Hindi",
)

/** Configurable button ids (QC Ultra Earbuds expose 3 and 4). */
object ButtonId {
    const val RIGHT_SHORTCUT = 3
    const val LEFT_SHORTCUT = 4
    const val SHORTCUT = 0x80  // single-button devices (headphones)
}

/** Action id meaning "button does nothing" — used as the per-side off switch. */
const val ACTION_DISABLED = 14

val BUTTON_ACTIONS = mapOf(
    0 to "Not configured", 1 to "Voice assistant", 2 to "ANC cycle",
    3 to "Battery level", 4 to "Play/pause", 5 to "Increase noise cancelling",
    6 to "Decrease noise cancelling", 7 to "Toggle wake word", 8 to "Switch device",
    9 to "Conversation mode", 10 to "Next track", 11 to "Previous track",
    12 to "Fetch notifications", 13 to "Wind mode", 14 to "Disabled",
    15 to "Client interaction", 16 to "Spotify", 17 to "Modes carousel",
    19 to "Immersive audio", 20 to "Line-in switch", 21 to "Linking",
)

/**
 * A mode/profile slot as reported by ModeConfig [31.6] STATUS.
 * Slots 0-3 are firmware presets (not editable); 4-10 are user slots.
 */
data class ModeConfig(
    val modeIdx: Int,
    val prompt: Prompt?,
    val promptId: Int,
    val name: String,
    val cncLevel: Int,
    val autoCnc: Boolean,
    val spatial: Spatial,
    val windBlock: Boolean,
    val ancToggle: Boolean,
    val editable: Boolean,
    val configured: Boolean,
    /** Third flag byte — mirrors the Favorites [31.8] bitmask (the "star"). */
    val starred: Boolean,
) {
    /** An empty user slot ("None", unconfigured) available for a new profile. */
    val isFreeSlot: Boolean
        get() = editable && !configured && (name == "None" || name.isEmpty())
}

/** Live audio settings from AudioModesSettingsConfig [31.10]. */
data class AudioSettings(
    /** 0-10, INVERTED: 0 = max noise cancelling, 10 = most ambient. */
    val cncLevel: Int,
    val autoCnc: Boolean,
    val spatial: Spatial,
    val windBlock: Boolean,
    val ancToggle: Boolean,
)

data class EqBand(val bandId: Int, val minVal: Int, val maxVal: Int, val current: Int)

/** Battery [2.2]: per-component levels; single-battery devices only set [overall]. */
data class BatteryStatus(
    val overall: Int?,
    val left: Int?,
    val right: Int?,
    val case: Int?,
    val raw: ByteArray,
) {
    override fun equals(other: Any?) = other is BatteryStatus && raw.contentEquals(other.raw)
    override fun hashCode() = raw.contentHashCode()
}

data class ButtonMapping(
    val buttonId: Int,
    val event: Int,
    val action: Int,
    val supportedActions: List<Int>,
)

data class Favorites(val slotCount: Int, val starred: Set<Int>)

/** Everything the GetAll [31.1] drain returns in one burst. */
data class DeviceSnapshot(
    val currentModeIdx: Int?,
    val modes: List<ModeConfig>,
    val favorites: Favorites?,
    val audioSettings: AudioSettings?,
)

open class BmapException(message: String) : Exception(message)
class BmapAuthException(message: String) : BmapException(message)
class BmapDeviceException(message: String, val errorCode: Int) : BmapException(message)
class BmapTimeoutException(message: String) : BmapException(message)
class BmapConnectionException(message: String, cause: Throwable? = null) :
    BmapException(message) { init { cause?.let { initCause(it) } } }
