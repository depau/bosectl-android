package eu.depau.bosectl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import eu.depau.bosectl.bmap.AudioSettings
import eu.depau.bosectl.bmap.BatteryStatus
import eu.depau.bosectl.bmap.BmapConnection
import eu.depau.bosectl.bmap.BmapException
import eu.depau.bosectl.bmap.Favorites
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.RfcommTransport
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.bmap.parseAudioSettings
import eu.depau.bosectl.bmap.parseBattery
import eu.depau.bosectl.bmap.parseFavorites
import eu.depau.bosectl.bmap.parseModeConfig
import eu.depau.bosectl.widget.BoseWidget
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private const val TAG = "DeviceRepository"
private const val POLL_FOREGROUND_MS = 2000L
private const val POLL_BACKGROUND_MS = 10000L

data class BoseState(
    val connected: Boolean = false,
    val busy: Boolean = false,
    val deviceName: String? = null,
    val firmware: String? = null,
    val battery: BatteryStatus? = null,
    val modes: List<ModeConfig> = emptyList(),
    val currentModeIdx: Int? = null,
    val favorites: Favorites? = null,
    val audioSettings: AudioSettings? = null,
    val touchControls: Boolean? = null,
    val lastError: String? = null,
) {
    val starredModes: List<ModeConfig>
        get() = modes.filter { favorites?.starred?.contains(it.modeIdx) == true && !it.isFreeSlot }
    val currentMode: ModeConfig?
        get() = modes.firstOrNull { it.modeIdx == currentModeIdx }
}

/**
 * Process-wide owner of the BMAP connection and device state. The UI, the
 * companion service and the widget actions all go through here so they share
 * one socket and one source of truth.
 */
@SuppressLint("StaticFieldLeak")  // holds the application context only
object DeviceRepository {
    private lateinit var context: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connectMutex = Mutex()
    private var connection: BmapConnection? = null
    private var unsolicitedJob: Job? = null
    private var pollJob: Job? = null
    private var visibleScreens = 0
    private var notificationsActive = false

    private val _state = MutableStateFlow(BoseState())
    val state: StateFlow<BoseState> = _state.asStateFlow()

    fun init(appContext: Context) {
        if (!::context.isInitialized) context = appContext.applicationContext
    }

    suspend fun savedDeviceMac(): String? =
        context.dataStore.data.first()[Prefs.DEVICE_MAC]

    suspend fun setDevice(mac: String) {
        context.dataStore.edit { it[Prefs.DEVICE_MAC] = mac }
        disconnect()
    }

    suspend fun clearDevice() {
        disconnect()
        context.dataStore.edit { it.clear() }
        _state.value = BoseState()
    }

    /** Connect if not already connected. Throws BmapException on failure. */
    suspend fun ensureConnected(): BmapConnection = connectMutex.withLock {
        connection?.takeIf { it.isConnected }?.let { return it }
        connection?.close()
        connection = null

        val mac = savedDeviceMac()
            ?: throw BmapException("No device selected")
        val adapter = context.getSystemService(BluetoothManager::class.java).adapter
            ?: throw BmapException("Bluetooth unavailable")
        val device = adapter.getRemoteDevice(mac)

        _state.value = _state.value.copy(busy = true, lastError = null)
        try {
            val transport = RfcommTransport.connect(device)
            val conn = BmapConnection(transport)
            connection = conn
            unsolicitedJob?.cancel()
            unsolicitedJob = scope.launch {
                conn.unsolicited.collect { onUnsolicited(it) }
            }
            // Ask the device to push state changes; without this it stays silent
            // and we have to poll. Format comes from the official app's
            // NotificationByFblock [9.2].
            notificationsActive = runCatching { conn.enableNotifications() }
                .onSuccess { Log.i(TAG, "Notifications enabled for blocks $it") }
                .onFailure { Log.w(TAG, "Notification subscribe failed", it) }
                .getOrDefault(emptyList())
                .isNotEmpty()
            _state.value = _state.value.copy(connected = true, busy = false)
            // Push is the mechanism; polling only covers firmware that refuses
            // the subscription.
            if (!notificationsActive) startPolling()
            conn
        } catch (e: Exception) {
            _state.value = _state.value.copy(
                connected = false, busy = false, lastError = e.message
            )
            persistWidgetCache()
            throw e
        }
    }

    fun disconnect() {
        stopPolling()
        unsolicitedJob?.cancel()
        unsolicitedJob = null
        connection?.close()
        connection = null
        _state.value = _state.value.copy(connected = false, busy = false)
        scope.launch { persistWidgetCache() }
    }

    /**
     * Run device work on the repository scope so screen navigation can't
     * cancel it mid-request. Errors land in state.lastError.
     */
    fun runAsync(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure { Log.w(TAG, "Device action failed", it) }
        }
    }

    /** Explicit connect (ACL receiver, device picker, Connect button). */
    fun onDeviceAppeared() = runAsync {
        ensureConnected()
        refresh()
    }

    fun onDeviceDisappeared() = disconnect()

    /**
     * Connect + refresh only if the phone currently has a Bluetooth link to the
     * earbuds — app launch must not spin on "connecting" when they're away.
     */
    fun autoConnectIfAvailable() = runAsync {
        if (state.value.connected) {
            refresh()
        } else if (isAclUp()) {
            ensureConnected()
            refresh()
        }
    }

    @SuppressLint("MissingPermission")
    private suspend fun isAclUp(): Boolean {
        val mac = savedDeviceMac() ?: return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        val device = adapter.getRemoteDevice(mac)
        // ponytail: hidden-but-ubiquitous BluetoothDevice.isConnected(); if the
        // reflection ever gets blocked, fail open and let the connect attempt decide
        return runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        }.getOrDefault(true)
    }

    /**
     * Fallback for devices that refuse the [9.2] notification subscription.
     * When the subscription works — verified on QC Ultra Earbuds — the device
     * pushes [31.3]/[31.10] immediately and this never runs.
     */
    private fun startPolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch {
            while (true) {
                delay(if (visibleScreens > 0) POLL_FOREGROUND_MS else POLL_BACKGROUND_MS)
                if (!state.value.connected) continue
                runCatching { refreshLive() }
                    .onFailure { Log.d(TAG, "Poll failed: ${it.message}") }
            }
        }
    }

    private fun stopPolling() {
        pollJob?.cancel()
        pollJob = null
    }

    /** Screens report visibility so polling can slow down when nobody is looking. */
    fun onScreenVisible() {
        visibleScreens++
        autoConnectIfAvailable()
    }

    fun onScreenHidden() {
        visibleScreens = (visibleScreens - 1).coerceAtLeast(0)
    }

    /** The cheap subset that on-device controls can change. */
    private suspend fun refreshLive() {
        val conn = connection?.takeIf { it.isConnected } ?: return
        val mode = conn.currentModeIdx()
        val settings = conn.audioSettings()
        if (mode == _state.value.currentModeIdx && settings == _state.value.audioSettings) return
        _state.value = _state.value.copy(
            currentModeIdx = mode ?: _state.value.currentModeIdx,
            audioSettings = settings ?: _state.value.audioSettings,
        )
        persistWidgetCache()
    }

    /** Full state refresh: one GetAll drain + battery + name (+ firmware once). */
    suspend fun refresh() {
        val conn = ensureConnected()
        try {
            val snapshot = conn.snapshot()
            val battery = runCatching { conn.battery() }.getOrNull()
            val touch = runCatching { conn.touchControls() }.getOrNull()
            val name = runCatching { conn.productName() }.getOrNull()
            val firmware = _state.value.firmware
                ?: runCatching { conn.firmware() }.getOrNull()
            _state.value = _state.value.copy(
                connected = true,
                deviceName = name ?: _state.value.deviceName,
                firmware = firmware,
                battery = battery ?: _state.value.battery,
                modes = snapshot.modes,
                currentModeIdx = snapshot.currentModeIdx,
                favorites = snapshot.favorites,
                audioSettings = snapshot.audioSettings,
                touchControls = touch ?: _state.value.touchControls,
                lastError = null,
            )
        } catch (e: Exception) {
            onIoFailure(e)
            throw e
        }
        persistWidgetCache()
    }

    /** Device-pushed STATUS frames (e.g. mode switched via earbud gestures). */
    private suspend fun onUnsolicited(packet: eu.depau.bosectl.bmap.BmapPacket) {
        Log.d(TAG, "PUSH $packet")
        if (packet.op != Op.STATUS) return
        val s = _state.value
        when {
            packet.matches(BmapConnection.Addr.CURRENT_MODE) && packet.payload.isNotEmpty() ->
                _state.value = s.copy(currentModeIdx = packet.payload[0].toInt() and 0xFF)
            packet.matches(BmapConnection.Addr.AUDIO_SETTINGS) ->
                parseAudioSettings(packet.payload)?.let { _state.value = s.copy(audioSettings = it) }
            packet.matches(BmapConnection.Addr.FAVORITES) ->
                parseFavorites(packet.payload)?.let { _state.value = s.copy(favorites = it) }
            packet.matches(BmapConnection.Addr.MODE_CONFIG) ->
                parseModeConfig(packet.payload)?.let { cfg ->
                    _state.value = s.copy(modes = s.modes.map { if (it.modeIdx == cfg.modeIdx) cfg else it })
                }
            packet.matches(BmapConnection.Addr.BATTERY) ->
                _state.value = s.copy(battery = parseBattery(packet.payload))
            else -> return
        }
        persistWidgetCache()
    }

    private fun onIoFailure(e: Exception) {
        Log.w(TAG, "Device operation failed", e)
        if (connection?.isConnected != true) {
            disconnect()
        }
        _state.value = _state.value.copy(lastError = e.message)
    }

    // ── Actions (optimistic state update + widget refresh) ───────────────────

    private suspend fun <T> action(block: suspend (BmapConnection) -> T): T {
        val conn = ensureConnected()
        return try {
            block(conn)
        } catch (e: Exception) {
            onIoFailure(e)
            throw e
        } finally {
            persistWidgetCache()
        }
    }

    /** Run arbitrary typed calls against the connection (settings screen). */
    suspend fun <T> withDevice(block: suspend (BmapConnection) -> T): T = action(block)

    suspend fun setMode(idx: Int) = action { conn ->
        conn.setMode(idx)
        _state.value = _state.value.copy(currentModeIdx = idx)
        // Switching modes also changes the live audio settings; re-read them.
        runCatching { conn.audioSettings() }.getOrNull()?.let {
            _state.value = _state.value.copy(audioSettings = it)
        }
    }

    suspend fun setSpatial(spatial: Spatial) = updateAudioSettings { it.copy(spatial = spatial) }

    suspend fun setCnc(level: Int) = updateAudioSettings { it.copy(cncLevel = level) }

    suspend fun setAnc(enabled: Boolean) = updateAudioSettings { it.copy(ancToggle = enabled) }

    /**
     * Nudge the noise-cancelling level by [delta] steps on the *display* scale
     * (positive = more cancelling). The wire scale is inverted.
     */
    suspend fun adjustCnc(delta: Int) = updateAudioSettings {
        it.copy(cncLevel = (it.cncLevel - delta).coerceIn(0, 10))
    }

    // No setWind: [31.10] rejects wind-block writes with Runtime error 8 on the
    // QC Ultra Earbuds; wind block is only configurable per-profile via [31.6].

    private suspend fun updateAudioSettings(transform: (AudioSettings) -> AudioSettings) =
        action { conn ->
            val current = _state.value.audioSettings ?: conn.audioSettings()
                ?: throw BmapException("Audio settings unavailable")
            val updated = transform(current)
            conn.setAudioSettings(updated)
            _state.value = _state.value.copy(audioSettings = updated.copy(autoCnc = false))
        }

    suspend fun setTouchControls(enabled: Boolean) = action { conn ->
        conn.setTouchControls(enabled)
        _state.value = _state.value.copy(touchControls = enabled)
    }

    suspend fun toggleStar(modeIdx: Int) = action { conn ->
        val favorites = _state.value.favorites ?: conn.favorites()
            ?: throw BmapException("Favorites unavailable")
        val starred = favorites.starred.toMutableSet()
        if (!starred.remove(modeIdx)) starred.add(modeIdx)
        val updated = favorites.copy(starred = starred)
        conn.setFavorites(updated)
        _state.value = _state.value.copy(favorites = updated)
    }

    suspend fun saveProfile(
        slot: Int, name: String, promptId: Int, cncLevel: Int, spatial: Spatial,
        favourite: Boolean = false,
    ) = action { conn ->
        conn.writeMode(slot, name, promptId, cncLevel, spatial)
        if (favourite) {
            val current = _state.value.favorites ?: conn.favorites()
            current?.let { conn.setFavorites(it.copy(starred = it.starred + slot)) }
        }
        refresh()
    }

    suspend fun deleteProfile(slot: Int) = action { conn ->
        conn.deleteMode(slot)
        // Deleted slots shouldn't stay starred
        _state.value.favorites?.let { fav ->
            if (slot in fav.starred) conn.setFavorites(fav.copy(starred = fav.starred - slot))
        }
        refresh()
    }

    // ── Widget cache ─────────────────────────────────────────────────────────

    private suspend fun persistWidgetCache() {
        val s = _state.value
        context.dataStore.edit { prefs ->
            prefs[Prefs.CACHE_CONNECTED] = s.connected
            s.deviceName?.let { prefs[Prefs.CACHE_DEVICE_NAME] = it }
            s.currentModeIdx?.let { prefs[Prefs.CACHE_CURRENT_MODE] = it }
            s.audioSettings?.let {
                prefs[Prefs.CACHE_SPATIAL] = it.spatial.value
                prefs[Prefs.CACHE_ANC] = it.ancToggle
                prefs[Prefs.CACHE_CNC] = it.cncLevel
            }
            s.touchControls?.let { prefs[Prefs.CACHE_TOUCH] = it }
            s.battery?.let { b ->
                // Stored per cell so the widget can pair each with its icon.
                b.left?.let { prefs[Prefs.CACHE_BAT_LEFT] = it }
                b.right?.let { prefs[Prefs.CACHE_BAT_RIGHT] = it }
                b.case?.let { prefs[Prefs.CACHE_BAT_CASE] = it }
                b.overall?.let { prefs[Prefs.CACHE_BAT_OVERALL] = it }
            }
            if (s.modes.isNotEmpty()) {
                prefs[Prefs.CACHE_STARRED] = encodeCachedModes(
                    s.starredModes.map { CachedMode(it.modeIdx, it.promptId, it.name) }
                )
            }
        }
        runCatching { BoseWidget().updateAll(context) }
            .onFailure { Log.w(TAG, "Widget update failed", it) }
    }
}
