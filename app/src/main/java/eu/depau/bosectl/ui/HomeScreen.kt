package eu.depau.bosectl.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.PairedDevice
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository

/** Device work runs on the repository scope: leaving the screen can't cancel it. */
fun deviceAction(block: suspend () -> Unit) = DeviceRepository.runAsync(block)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenProfiles: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenConnections: () -> Unit,
) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    var disconnectTarget by remember { mutableStateOf<PairedDevice?>(null) }
    var sheetMac by remember { mutableStateOf<String?>(null) }

    fun disconnect(device: PairedDevice) {
        // Dropping the phone this app runs on also drops our own BMAP link, so
        // that one asks first. Every other disconnect stays a single tap.
        if (device.isLocalDevice) disconnectTarget = device
        else deviceAction { DeviceRepository.disconnectSource(device.mac) }
    }

    DeviceSheetHost(
        mac = sheetMac,
        onConnect = { deviceAction { DeviceRepository.connectSource(it.mac) } },
        onDisconnect = ::disconnect,
        onDismiss = { sheetMac = null },
    )

    disconnectTarget?.let { device ->
        ConfirmLocalDisconnectDialog(
            device = device,
            onDismiss = { disconnectTarget = null },
            onConfirm = {
                disconnectTarget = null
                deviceAction { DeviceRepository.disconnectSource(device.mac) }
            },
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.deviceName ?: "Bose Control") },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(AppIcons.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (!state.connected) {
                DisconnectedCard(busy = state.busy, error = state.lastError) {
                    DeviceRepository.onDeviceAppeared()
                }
            } else if (state.lastError != null) {
                Text(
                    state.lastError!!,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            // First read in flight: show placeholders instead of empty rows.
            val loading = state.modes.isEmpty() && (state.busy || state.connected)

            // 8dp here plus the column's 8dp gap gives 16dp below, matching the
            // slack the top app bar leaves above the chips.
            Box(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
                if (state.battery == null && loading) BatterySkeleton() else BatteryRow(state)
            }

            // Device-only, like the rows in Settings: hidden while disconnected
            // rather than shown empty, since we have no idea what's attached.
            if (state.connected) {
                SectionCard(title = "Connected to", linkLabel = "All devices",
                    onLink = onOpenConnections) {
                    if (state.pairedDevices.isEmpty() && loading) {
                        ConnectedDeviceSkeleton()
                    } else if (state.connectedDevices.isEmpty()) {
                        Text(
                            "No devices connected",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        state.connectedDevices.forEach { device ->
                            ConnectedDeviceRow(
                                device = device,
                                playing = device.mac == state.activeSourceMac,
                                pending = device.mac in state.pendingDevices,
                                onClick = { sheetMac = device.mac },
                                onDisconnect = { disconnect(device) },
                            )
                        }
                    }
                }
            }

            // No "Audio" title: it stacked straight onto the "Mode" heading
            // below it and read as a double header.
            SectionCard(title = "Mode", linkLabel = "All modes", onLink = onOpenProfiles) {
                if (loading) {
                    ModeCardsSkeleton()
                } else {
                    ModeCards(
                        modes = state.starredModes.ifEmpty { state.modes.filter { !it.isFreeSlot } },
                        currentIdx = state.currentModeIdx,
                        enabled = state.connected,
                        onSelect = { deviceAction { DeviceRepository.setMode(it) } },
                    )
                }

                SubSectionHeader("Immersive audio")
                if (state.audioSettings == null && loading) {
                    BarSkeleton()
                } else {
                    ImmersiveAudioSelector(
                        selected = state.audioSettings?.spatial,
                        enabled = state.connected,
                        onSelect = { deviceAction { DeviceRepository.setSpatial(it) } },
                    )
                }
            }

            SectionCard {
                if (state.audioSettings == null && loading) {
                    NoiseControlsSkeleton()
                } else {
                    NoiseControls(state)
                }
            }

            state.touchControls?.let { touchOn ->
                SectionCard {
                    SwitchRow(
                        icon = AppIcons.TouchApp, label = "Touch controls",
                        checked = touchOn, enabled = state.connected,
                        supporting = "Tap and swipe gestures on the earbuds",
                    ) { deviceAction { DeviceRepository.setTouchControls(it) } }
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun NoiseControlsSkeleton() {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconSkeleton(20.dp)
        Spacer(Modifier.width(8.dp))
        SkeletonBox(Modifier.width(160.dp).height(20.dp), cornerRadius = 4.dp)
    }
    Spacer(Modifier.height(12.dp))
    BarSkeleton(height = 16.dp, cornerRadius = 8.dp)
    Spacer(Modifier.height(20.dp))
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        IconSkeleton(20.dp)
        Spacer(Modifier.width(8.dp))
        SkeletonBox(Modifier.width(180.dp).height(20.dp), cornerRadius = 4.dp)
        Spacer(Modifier.weight(1f))
        SkeletonBox(Modifier.width(52.dp).height(32.dp), cornerRadius = 16.dp)
    }
}

/**
 * A Home section. Grouping into cards is what lets the "All …" link sit on the
 * title row: inside a card it obviously belongs to that section, so it no
 * longer needs a slab of whitespace to separate it from the section below.
 */
@Composable
private fun SectionCard(
    title: String? = null,
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
                // Devices and modes arrive after the first frame; grow into
                // them instead of snapping.
                .animateContentSize(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            title?.let {
                HeaderRow(it, MaterialTheme.typography.titleMedium, linkLabel, onLink)
            }
            content()
        }
    }
}

/** Heading for a second block inside a card, e.g. Immersive audio under Mode. */
@Composable
private fun SubSectionHeader(
    title: String,
    linkLabel: String? = null,
    onLink: (() -> Unit)? = null,
) {
    HeaderRow(
        title, MaterialTheme.typography.titleMedium, linkLabel, onLink,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun HeaderRow(
    title: String,
    style: androidx.compose.ui.text.TextStyle,
    linkLabel: String?,
    onLink: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier.fillMaxWidth().height(32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = style, modifier = Modifier.weight(1f))
        if (linkLabel != null && onLink != null) {
            // Height pinned: TextButton's 40dp minimum made every header row
            // taller than its text, which is what left the cards looking
            // top-heavy against a flat 12dp bottom padding.
            TextButton(
                onClick = onLink,
                modifier = Modifier.height(32.dp),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp),
            ) {
                Text(linkLabel)
                Spacer(Modifier.width(4.dp))
                Icon(
                    AppIcons.ArrowForward, contentDescription = null,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

@Composable
fun DisconnectedCard(
    busy: Boolean,
    error: String?,
    modifier: Modifier = Modifier,
    onRetry: () -> Unit,
) {
    Card(
        modifier.fillMaxWidth().padding(top = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        ),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.size(32.dp))
                Text("Connecting…")
            } else {
                Icon(AppIcons.BluetoothDisabled, contentDescription = null)
                Text("Not connected", style = MaterialTheme.typography.titleMedium)
                error?.let {
                    Text(
                        it, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
                Button(onClick = onRetry) { Text("Connect") }
            }
        }
    }
}

@Composable
private fun BatteryRow(state: eu.depau.bosectl.data.BoseState) {
    val battery = state.battery ?: return
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
    ) {
        val cells = listOfNotNull(
            battery.left?.let { BatteryCell(AppIcons.EarbudLeft, "L", it) },
            battery.right?.let { BatteryCell(AppIcons.EarbudRight, "R", it) },
            battery.case?.let { BatteryCell(AppIcons.EarbudCase, "Case", it) },
        ).ifEmpty {
            listOfNotNull(battery.overall?.let { BatteryCell(AppIcons.BatteryStd, "", it) })
        }
        for (cell in cells) {
            InfoPill(
                icon = cell.icon,
                text = if (cell.label.isEmpty()) "${cell.value}%"
                else "${cell.label} ${cell.value}%",
            )
        }
    }
}

/**
 * A read-only chip. Deliberately not AssistChip: these report state and do
 * nothing when tapped, and a chip that ripples under your finger promises
 * otherwise.
 */
@Composable
private fun InfoPill(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Surface(
        shape = MaterialTheme.shapes.small,
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

private data class BatteryCell(
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val label: String,
    val value: Int,
)

@Composable
private fun ModeCards(
    modes: List<ModeConfig>,
    currentIdx: Int?,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
) {
    if (modes.isEmpty()) {
        Text(
            "No modes loaded yet",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    if (modes.size <= 4) {
        // Few enough to fit: stretch across the full width
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            modes.forEach { mode ->
                ModeCard(
                    mode, selected = mode.modeIdx == currentIdx, enabled = enabled,
                    onSelect = onSelect, modifier = Modifier.weight(1f),
                )
            }
        }
    } else {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(modes, key = { it.modeIdx }) { mode ->
                ModeCard(
                    mode, selected = mode.modeIdx == currentIdx, enabled = enabled,
                    onSelect = onSelect, modifier = Modifier.width(96.dp),
                )
            }
        }
    }
}

@Composable
private fun ModeCard(
    mode: ModeConfig,
    selected: Boolean,
    enabled: Boolean,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = { onSelect(mode.modeIdx) },
        enabled = enabled,
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer
            else MaterialTheme.colorScheme.surfaceContainerHighest,
        ),
        // Outlined when unselected. These sit inside the Audio card now, and
        // with dynamic colour every surface tone lands within a few percent of
        // the card's own — the tiles were invisible until they had a border.
        border = if (selected) null
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 16.dp, horizontal = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                promptIcon(mode.promptId), contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                mode.name,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
fun ImmersiveAudioSelector(
    selected: Spatial?,
    enabled: Boolean,
    onSelect: (Spatial) -> Unit,
) {
    val options = listOf(
        Triple(Spatial.OFF, "Off", AppIcons.SpatialAudioOff),
        Triple(Spatial.STILL, "Still", AppIcons.SpatialTracking),
        Triple(Spatial.MOTION, "Motion", AppIcons.SpatialAudio),
    )
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label, icon) ->
            SegmentedButton(
                selected = selected == value,
                onClick = { onSelect(value) },
                enabled = enabled,
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
                // Default inactive container is `surface`, which is darker than
                // the card this now sits on and made the unselected segments
                // read as holes punched through it.
                colors = SegmentedButtonDefaults.colors(
                    inactiveContainerColor = Color.Transparent,
                ),
                icon = {},
                label = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(icon, contentDescription = null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(label)
                    }
                },
            )
        }
    }
}

@Composable
private fun NoiseControls(state: eu.depau.bosectl.data.BoseState) {
    val settings = state.audioSettings ?: return
    // Wire scale is inverted (0 = max ANC); display right = more cancelling.
    var sliderPos by remember(settings.cncLevel) {
        mutableFloatStateOf((10 - settings.cncLevel).toFloat())
    }

    SwitchRow(
        icon = if (settings.ancToggle) AppIcons.NoiseControlOn
        else AppIcons.NoiseControlOff,
        label = "Noise cancelling",
        checked = settings.ancToggle, enabled = state.connected,
    ) { deviceAction { DeviceRepository.setAnc(it) } }

    Row(
        Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "Level",
            style = MaterialTheme.typography.bodyMedium,
            color = if (settings.ancToggle) MaterialTheme.colorScheme.onSurface
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            cncLevelName(sliderPos.toInt()),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Slider(
        value = sliderPos,
        onValueChange = { sliderPos = it },
        onValueChangeFinished = {
            deviceAction { DeviceRepository.setCnc(10 - sliderPos.toInt()) }
        },
        valueRange = 0f..10f,
        steps = 9,
        enabled = state.connected && settings.ancToggle,
    )
    Row(Modifier.fillMaxWidth()) {
        Text(
            "Hear surroundings",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.weight(1f))
        Text(
            "Block noise",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Slider position (10 = most cancelling) as words, not a bare number. */
private fun cncLevelName(displayLevel: Int) = when (displayLevel) {
    0 -> "Full transparency"
    in 1..2 -> "Very low"
    in 3..4 -> "Low"
    in 5..6 -> "Medium"
    in 7..8 -> "High"
    else -> "Maximum"
}

/**
 * One connected source. [4.4] gives no name for a device we've never read
 * [4.5] for, so the MAC stands in until that lands.
 *
 * "this device" and "playing" are different facts and both are worth showing:
 * the first is the phone running the app, the second is whichever device
 * currently holds audio — often not the same one.
 */
@Composable
private fun ConnectedDeviceRow(
    device: PairedDevice,
    playing: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val label = device.name.ifEmpty { device.mac }
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(AppIcons.Bluetooth, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, maxLines = 1)
            deviceTags(device, playing)?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (pending) {
            // Same 48dp footprint as the IconButton it stands in for, so the
            // row keeps its height and the name doesn't shift.
            Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
            }
        } else {
            IconButton(onClick = onDisconnect) {
                Icon(AppIcons.LinkOff, contentDescription = "Disconnect $label")
            }
        }
    }
}

@Composable
private fun ConnectedDeviceSkeleton() {
    // Mirrors exactly one ConnectedDeviceRow: its trailing IconButton is what
    // sets the 48dp row height, and the two bars are the name and the tag line.
    // One device is the common case; a second animates in via the card's
    // animateContentSize rather than being guessed at here.
    Row(
        Modifier.fillMaxWidth().height(48.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconSkeleton(20.dp)
        Spacer(Modifier.width(8.dp))
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SkeletonBox(Modifier.width(120.dp).height(16.dp), cornerRadius = 4.dp)
            SkeletonBox(Modifier.width(76.dp).height(12.dp), cornerRadius = 4.dp)
        }
    }
}

@Composable
fun SwitchRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    checked: Boolean,
    enabled: Boolean,
    supporting: String? = null,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label)
            supporting?.let {
                Text(
                    it, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
    }
}
