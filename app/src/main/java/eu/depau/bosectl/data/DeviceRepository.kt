package eu.depau.bosectl.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.util.Log
import eu.depau.bosectl.bmap.BmapTransport
import androidx.datastore.preferences.core.edit
import androidx.glance.appwidget.updateAll
import eu.depau.bosectl.bmap.AudioSettings
import eu.depau.bosectl.bmap.BatteryStatus
import eu.depau.bosectl.bmap.BoseAdvertisement
import eu.depau.bosectl.bmap.BmapConnection
import eu.depau.bosectl.bmap.BmapException
import eu.depau.bosectl.bmap.Favorites
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.PairedDevice
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.bmap.parseActiveSource
import eu.depau.bosectl.bmap.parseAudioSettings
import eu.depau.bosectl.bmap.parseBattery
import eu.depau.bosectl.bmap.mergePairedDevices
import eu.depau.bosectl.bmap.parseDeviceList
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
import kotlinx.coroutines.withTimeoutOrNull

private const val TAG = "DeviceRepository"
private const val POLL_FOREGROUND_MS = 2000L
private const val POLL_BACKGROUND_MS = 10000L

/** Give up spinning on a connect/disconnect that never reports back. */
private const val DEVICE_ACTION_TIMEOUT_MS = 20_000L

/** Advertisements arrive every few seconds; don't retry a failed connect that often. */
private const val AUTO_CONNECT_RETRY_MS = 60_000L

/** A broadcast receiver is held open across this, so keep it short. */
private const val AUTO_CONNECT_BUDGET_MS = 8_000L

/** How often to notice that an LE link died. Costs no radio traffic. */
private const val LINK_WATCHDOG_MS = 15_000L

/** Comfortably inside the ~40s idle timeout the earbuds enforce on an LE link. */
private const val LE_KEEPALIVE_MS = 20_000L

/** A failed LE-to-classic switch lands back on LE; don't retry it in a tight loop. */
private const val LINK_UPGRADE_RETRY_MS = 60_000L

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
    /** Devices the earbuds know about, in the order [4.4] last reported them. */
    val pairedDevices: List<PairedDevice> = emptyList(),
    /** MAC of the device currently holding audio, from [5.1]. */
    val activeSourceMac: String? = null,
    /** Which transport the live connection is using, null when disconnected. */
    val link: LinkLayer? = null,
    /** When a BLE advertisement was last seen, epoch millis; null if never. */
    val lastSeenAt: Long? = null,
    /** The advertisement's "available to connect" bit at [lastSeenAt]. */
    val lastSeenAvailable: Boolean = false,
    /**
     * Devices with a connect or disconnect in flight, mapped to the state we
     * asked for. [4.1] never sends a RESULT and [4.2]'s link takes a moment to
     * drop, so "done" means the device reporting the target state in [4.4].
     */
    val pendingDevices: Map<String, Boolean> = emptyMap(),
    val lastError: String? = null,
) {
    val starredModes: List<ModeConfig>
        get() = modes.filter { favorites?.starred?.contains(it.modeIdx) == true && !it.isFreeSlot }
    val currentMode: ModeConfig?
        get() = modes.firstOrNull { it.modeIdx == currentModeIdx }
    /**
     * Connected devices, this one first, then whichever is playing.
     * [4.4]'s own order is not stable between reads, so imposing one here stops
     * rows from swapping around underneath the user.
     */
    val connectedDevices: List<PairedDevice>
        get() = pairedDevices.filter { it.connected }.sortedWith(
            compareByDescending<PairedDevice> { it.isLocalDevice }
                .thenByDescending { it.mac == activeSourceMac }
        )

    /** This phone's entry in the earbuds' device list — `isLocalDevice` in [4.5]. */
    private val localDevice: PairedDevice?
        get() = pairedDevices.firstOrNull { it.isLocalDevice }

    /**
     * Is this phone the device the earbuds are actually playing, per [5.1]?
     *
     * Distinct from "has an audio link": with multipoint the phone can be
     * connected while a laptop holds the audio (§14).
     */
    val playingHere: Boolean
        get() = activeSourceMac != null && activeSourceMac == localDevice?.mac
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
    private var watchdogJob: Job? = null
    private var keepaliveJob: Job? = null

    @Volatile
    private var lastAutoConnectAt = 0L

    @Volatile
    private var lastUpgradeAt = 0L
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

    suspend fun linkPreference(): LinkPreference =
        LinkPreference.fromId(context.dataStore.data.first()[Prefs.LINK_LAYER])

    suspend fun setLinkPreference(preference: LinkPreference) {
        context.dataStore.edit { it[Prefs.LINK_LAYER] = preference.id }
        disconnect()
    }

    /**
     * Open the first transport that works. Both carry the same protocol, so the
     * only visible difference is [BoseState.link] — and that LE keeps working
     * when the earbuds are playing to another device.
     */
    private suspend fun openFirstWorkingTransport(
        device: BluetoothDevice,
        automatic: Boolean,
    ): Pair<BmapTransport, LinkLayer> {
        val order = linkOrder(linkPreference(), classicLinkUp = isAclUp(), automatic = automatic)
        var lastError: Exception? = null
        for (link in order) {
            try {
                val transport = openTransport(link, context, device)
                Log.i(TAG, "Connected to ${device.address} over $link")
                return transport to link
            } catch (e: Exception) {
                Log.w(TAG, "$link transport failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: BmapException("No usable link to ${device.address}")
    }

    /**
     * Connect if not already connected. Throws BmapException on failure.
     *
     * [automatic] must be true for anything the user did not explicitly ask for
     * — it forbids initiating a classic link. See [linkOrder].
     */
    suspend fun ensureConnected(automatic: Boolean = false): BmapConnection =
        connectMutex.withLock {
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
                val (transport, link) = openFirstWorkingTransport(device, automatic)
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
                _state.value = _state.value.copy(connected = true, busy = false, link = link)
                // Push is the mechanism; polling only covers firmware that refuses
                // the subscription.
                if (!notificationsActive) startPolling()
                startLinkWatchdog()
                startKeepalive(link)
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
        stopLinkWatchdog()
        stopKeepalive()
        unsolicitedJob?.cancel()
        unsolicitedJob = null
        connection?.close()
        connection = null
        _state.value = _state.value.copy(connected = false, busy = false, link = null)
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
        // Already on LE when the classic link comes up? Move over to it.
        if (state.value.link == LinkLayer.LE) upgradeToClassicIfAvailable()
        ensureConnected()
        refresh()
    }

    /**
     * Move an LE connection onto the classic link once one exists.
     *
     * A2DP cannot play without a classic link, so the moment this phone holds the
     * audio, RFCOMM is available and free — and it is the better carrier: the
     * proven path, and it needs no keepalive, unlike LE which the earbuds hang up
     * after ~40 s of silence (§15).
     *
     * This never *initiates* classic: it is guarded on the ACL already being up,
     * so it cannot take the audio from another device.
     */
    private suspend fun upgradeToClassicIfAvailable() {
        if (state.value.link != LinkLayer.LE) return
        if (linkPreference() == LinkPreference.LE_ONLY) return
        if (!isAclUp()) return
        // Throttled so a failed switch (which lands back on LE) can't loop.
        val now = System.currentTimeMillis()
        if (now - lastUpgradeAt < LINK_UPGRADE_RETRY_MS) return
        lastUpgradeAt = now

        Log.i(TAG, "Classic link is up; moving BMAP off LE")
        // Briefly disconnected: the alternative is holding two live transports,
        // which is a lot of machinery to avoid one second of "connecting".
        disconnect()
        ensureConnected(automatic = true)
        refresh()
    }

    fun onDeviceDisappeared() = disconnect()

    /**
     * Connect + refresh only if the earbuds are actually reachable — app launch
     * must not spin on "connecting" when they're away.
     *
     * A classic link means RFCOMM is available. Failing that, a *recent BLE
     * sighting* means the LE transport is worth a try even with no classic link,
     * which is how the app can show state while the earbuds play to another
     * device. With neither, don't touch the radio.
     */
    fun autoConnectIfAvailable() = runAsync {
        if (state.value.connected) {
            refresh()
        } else if (isAclUp() || isNearby()) {
            ensureConnected(automatic = true)
            refresh()
        }
    }

    /**
     * A nearby sighting is only useful if something acts on it: connect over LE
     * so state and the widget go live while the earbuds play to another device.
     *
     * Rate-limited because advertisements arrive every couple of seconds, and
     * strictly [automatic] so it can never take the audio (see [linkOrder]).
     */
    private suspend fun autoConnectOnSighting() {
        if (state.value.connected || state.value.busy) return
        if (connection?.isConnected == true) return
        if (savedDeviceMac() == null) return
        // Nothing to try: the only permitted transport needs a link we don't have.
        if (linkOrder(linkPreference(), isAclUp(), automatic = true).isEmpty()) return
        val now = System.currentTimeMillis()
        if (now - lastAutoConnectAt < AUTO_CONNECT_RETRY_MS) return
        lastAutoConnectAt = now
        // Bounded: this runs while a broadcast receiver is held open.
        withTimeoutOrNull(AUTO_CONNECT_BUDGET_MS) {
            runCatching {
                ensureConnected(automatic = true)
                refresh()
            }.onFailure { Log.d(TAG, "Auto-connect on sighting failed: ${it.message}") }
        }
    }

    /**
     * Nothing tells us when an LE link dies — there is no ACL broadcast for it,
     * and with pushes working there are no requests to fail either. Without this
     * the UI and the widget would keep claiming "connected" forever.
     */
    private fun startLinkWatchdog() {
        if (watchdogJob?.isActive == true) return
        watchdogJob = scope.launch {
            while (true) {
                delay(LINK_WATCHDOG_MS)
                if (connection?.isConnected == false) {
                    Log.i(TAG, "Link went away; clearing state")
                    disconnect()
                    return@launch
                }
            }
        }
    }

    private fun stopLinkWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = null
    }

    /**
     * The earbuds hang up an idle LE link after ~40 s — `newState=0` with
     * `status=19`, `GATT_CONN_TERMINATE_PEER_USER`. Once `[9.2]` is subscribed we
     * have nothing to send, so a quiet link gets reaped and the widget goes stale.
     *
     * So poke it with the most inert GET there is: `[0.1]` BMAP version is a
     * constant string, reads nothing, and changes nothing.
     *
     * Classic needs none of this — that link is held up by the audio connection.
     */
    private fun startKeepalive(link: LinkLayer) {
        stopKeepalive()
        if (link != LinkLayer.LE) return
        keepaliveJob = scope.launch {
            while (true) {
                delay(LE_KEEPALIVE_MS)
                val conn = connection?.takeIf { it.isConnected } ?: return@launch
                runCatching { conn.raw(bmapPacket(0, 1, Op.GET)) }
                    .onFailure { Log.d(TAG, "Keepalive failed: ${it.message}") }
            }
        }
    }

    private fun stopKeepalive() {
        keepaliveJob?.cancel()
        keepaliveJob = null
    }

    /** Was a matching advertisement seen recently enough to bother connecting? */
    private suspend fun isNearby(): Boolean {
        if (linkPreference() == LinkPreference.CLASSIC_ONLY) return false
        val seen = context.dataStore.data.first()[Prefs.LAST_SEEN_AT] ?: return false
        return System.currentTimeMillis() - seen < PRESENCE_FRESH_MS
    }

    /**
     * A batch of Bose advertisements from the system scan (see [PresenceScanner]).
     * Runs from a broadcast receiver, so [onDone] releases its wake lock.
     */
    fun onAdvertisementsSeen(
        sightings: List<Pair<String, BoseAdvertisement>>,
        onDone: () -> Unit = {},
    ) {
        scope.launch {
            try {
                val saved = savedDeviceMac() ?: return@launch
                val prefs = context.dataStore.data.first()
                val knownProduct = prefs[Prefs.DEVICE_BLE_PRODUCT_ID]

                // An address match is proof. Otherwise fall back to "same model
                // as ours", which is all the advertisement offers: the rotating
                // address is Bose's own resolvable scheme, not a standard RPA we
                // could resolve with an IRK.
                // ponytail: another set of the same model in range would count as
                // ours. Harmless for a presence hint — it only ever causes an LE
                // connect attempt that then fails.
                val exact = sightings.firstOrNull { it.first.equals(saved, ignoreCase = true) }
                val match = exact
                    ?: sightings.firstOrNull { it.second.bleProductId == knownProduct }
                    ?: return@launch

                context.dataStore.edit {
                    it[Prefs.LAST_SEEN_AT] = System.currentTimeMillis()
                    it[Prefs.LAST_SEEN_AVAILABLE] = match.second.availableToConnect
                    if (exact != null) it[Prefs.DEVICE_BLE_PRODUCT_ID] = exact.second.bleProductId
                }
                _state.value = _state.value.copy(
                    lastSeenAt = System.currentTimeMillis(),
                    lastSeenAvailable = match.second.availableToConnect,
                )
                // The point of detecting them: go live without being asked.
                autoConnectOnSighting()
            } finally {
                onDone()
            }
        }
    }

    /** Re-arm the system scan if the user turned nearby detection on. */
    fun startPresenceScanIfEnabled(onDone: () -> Unit = {}) {
        scope.launch {
            try {
                if (context.dataStore.data.first()[Prefs.PRESENCE_ENABLED] == true) {
                    PresenceScanner.start(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Could not re-arm the nearby scan", e)
            } finally {
                onDone()
            }
        }
    }

    /** Load the persisted sighting so a freshly started UI isn't blank. */
    fun restorePresence() = runAsync {
        val prefs = context.dataStore.data.first()
        _state.value = _state.value.copy(
            lastSeenAt = prefs[Prefs.LAST_SEEN_AT],
            lastSeenAvailable = prefs[Prefs.LAST_SEEN_AVAILABLE] ?: false,
        )
    }

    @SuppressLint("MissingPermission")
    private suspend fun isAclUp(): Boolean {
        val mac = savedDeviceMac() ?: return false
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
            ?: return false
        val device = adapter.getRemoteDevice(mac)
        // ponytail: hidden-but-ubiquitous BluetoothDevice.isConnected(). If the
        // reflection ever gets blocked, fall back to "is any A2DP sink connected"
        // — coarser, but connecting must never be something the app does on its
        // own initiative, so guessing "yes" is the wrong default.
        return runCatching {
            device.javaClass.getMethod("isConnected").invoke(device) as Boolean
        }.getOrElse {
            adapter.getProfileConnectionState(BluetoothProfile.A2DP) ==
                    BluetoothProfile.STATE_CONNECTED
        }
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
            // Non-fatal: the device list is a nice-to-have next to modes and
            // battery, and it must not fail the whole refresh.
            runCatching { loadDevices(conn) }
                .onFailure { Log.d(TAG, "Device list refresh failed: ${it.message}") }
        } catch (e: Exception) {
            onIoFailure(e)
            throw e
        }
        persistWidgetCache()
    }

    // ── Paired devices [4.4]/[4.5]/[5.1] ─────────────────────────────────────

    /**
     * Fold a fresh [4.4] list into state. Synchronous by design: this also runs
     * from the unsolicited collector, which must never touch the transport (see
     * [onUnsolicited]). Names for unseen MACs arrive later via [fillDeviceNames].
     */
    private fun mergeDeviceList(entries: List<Pair<String, Boolean>>) {
        val merged = mergePairedDevices(_state.value.pairedDevices, entries)
        // A pending action is finished the moment the device reports the state
        // it was asked for — this frame is the acknowledgement.
        val stillPending = _state.value.pendingDevices.filterNot { (mac, target) ->
            merged.any { it.mac == mac && it.connected == target }
        }
        _state.value = _state.value.copy(pairedDevices = merged, pendingDevices = stillPending)
    }

    private fun markPending(mac: String, target: Boolean) {
        _state.value = _state.value.copy(
            pendingDevices = _state.value.pendingDevices + (mac to target)
        )
        // Backstop: a connect to a device that never answers would otherwise
        // spin forever, since there is no failure frame to react to.
        scope.launch {
            delay(DEVICE_ACTION_TIMEOUT_MS)
            clearPending(mac)
        }
    }

    private fun clearPending(mac: String) {
        if (mac !in _state.value.pendingDevices) return
        _state.value = _state.value.copy(
            pendingDevices = _state.value.pendingDevices - mac
        )
    }

    /**
     * Read [4.5] for devices we have no name for. Names don't change, so this
     * costs one GET each on first sight and nothing thereafter — which is what
     * keeps a refresh down to one [4.4] plus one [5.1].
     */
    private suspend fun fillDeviceNames(conn: BmapConnection) {
        // Connected first: the transport serialises requests, so with six known
        // devices the tail takes a couple of seconds. The Home section only
        // shows connected ones, and until a name lands the row reads as a raw
        // MAC — so resolve those two before the four nobody is looking at.
        val missing = _state.value.pairedDevices
            .filter { it.name.isEmpty() }
            .sortedByDescending { it.connected }
        // Applied one at a time rather than in a batch at the end, so the two
        // rows on Home stop reading as raw MACs as soon as their own [4.5]
        // lands instead of waiting for the whole list.
        for (target in missing) {
            val info = runCatching { conn.deviceInfo(target.mac) }.getOrNull() ?: continue
            // Re-read state each pass: the list can change while we read.
            _state.value = _state.value.copy(
                pairedDevices = _state.value.pairedDevices.map { device ->
                    if (device.mac != info.mac) device
                    else device.copy(
                        name = info.name, isLocalDevice = info.isLocalDevice,
                        isBoseProduct = info.isBoseProduct,
                    )
                }
            )
        }
    }

    private suspend fun loadDevices(conn: BmapConnection) {
        mergeDeviceList(conn.deviceList())
        runCatching { conn.activeSource() }.getOrNull()?.let {
            _state.value = _state.value.copy(activeSourceMac = it)
        }
        fillDeviceNames(conn)
    }

    /** Re-read the device list on demand (Connections screen pull/open). */
    suspend fun refreshDevices() = action { loadDevices(it) }

    suspend fun disconnectSource(mac: String) = action { conn ->
        markPending(mac, target = false)
        try {
            conn.disconnectDevice(mac)
        } catch (e: Exception) {
            clearPending(mac)
            throw e
        }
        loadDevices(conn)
    }

    /**
     * [4.1] acks with PROCESSING and the link comes up a few seconds later, so
     * there is nothing useful to read here — the [4.4] push reports the result.
     */
    suspend fun connectSource(mac: String) = action { conn ->
        markPending(mac, target = true)
        try {
            conn.connectDevice(mac)
        } catch (e: Exception) {
            clearPending(mac)
            throw e
        }
    }

    suspend fun forgetSource(mac: String) = action { conn ->
        conn.forgetDevice(mac)
        loadDevices(conn)
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
            // Pushed on every connect/disconnect once block 4 is subscribed —
            // this is what makes the device list live. Merge synchronously and
            // hand any name lookup to the repository scope: this collector runs
            // while the transport's request mutex may be held, so calling the
            // device from here can deadlock once the push buffer fills.
            packet.matches(BmapConnection.Addr.DEV_LIST) -> {
                mergeDeviceList(parseDeviceList(packet.payload))
                if (_state.value.pairedDevices.any { it.name.isEmpty() }) {
                    runAsync { connection?.let { fillDeviceNames(it) } }
                }
            }
            packet.matches(BmapConnection.Addr.ACTIVE_SOURCE) ->
                parseActiveSource(packet.payload)?.let {
                    _state.value = s.copy(activeSourceMac = it)
                }
            else -> return
        }
        // The audio picture just changed; if this phone now holds it, the classic
        // link exists and LE is no longer the only option. On its own coroutine:
        // the switch closes this very transport, which would otherwise cancel the
        // collector we are running in.
        if (packet.matches(BmapConnection.Addr.ACTIVE_SOURCE) ||
            packet.matches(BmapConnection.Addr.DEV_LIST)
        ) {
            runAsync { upgradeToClassicIfAvailable() }
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
            prefs[Prefs.CACHE_PLAYING_HERE] = s.playingHere
            s.link?.let { prefs[Prefs.CACHE_LINK] = it.id }
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
