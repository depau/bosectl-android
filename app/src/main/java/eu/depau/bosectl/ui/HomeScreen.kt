package eu.depau.bosectl.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.AssistChip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository

/** Device work runs on the repository scope: leaving the screen can't cancel it. */
fun deviceAction(block: suspend () -> Unit) = DeviceRepository.runAsync(block)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpenProfiles: () -> Unit, onOpenSettings: () -> Unit) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()

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

            if (state.battery == null && loading) BatterySkeleton() else BatteryRow(state)

            SectionLabel("Mode")
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
            TextButton(onClick = onOpenProfiles, modifier = Modifier.align(Alignment.End)) {
                Text("All modes")
                Spacer(Modifier.width(4.dp))
                Icon(AppIcons.ArrowForward, contentDescription = null,
                    modifier = Modifier.size(18.dp))
            }

            SectionLabel("Immersive audio")
            if (state.audioSettings == null && loading) {
                BarSkeleton()
            } else {
                ImmersiveAudioSelector(
                    selected = state.audioSettings?.spatial,
                    enabled = state.connected,
                    onSelect = { deviceAction { DeviceRepository.setSpatial(it) } },
                )
            }

            SectionLabel("Noise cancelling")
            if (state.audioSettings == null && loading) {
                NoiseControlsSkeleton()
            } else {
                NoiseControls(state)
            }

            state.touchControls?.let { touchOn ->
                SectionLabel("Controls")
                SwitchRow(
                    icon = AppIcons.TouchApp, label = "Touch controls",
                    checked = touchOn, enabled = state.connected,
                    supporting = "Tap and swipe gestures on the earbuds",
                ) { deviceAction { DeviceRepository.setTouchControls(it) } }
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

@Composable
private fun SectionLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp),
    )
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
            AssistChip(
                onClick = {},
                label = {
                    Text(if (cell.label.isEmpty()) "${cell.value}%" else "${cell.label} ${cell.value}%")
                },
                leadingIcon = {
                    Icon(
                        cell.icon, contentDescription = null,
                        Modifier.size(18.dp),
                    )
                },
            )
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
            else MaterialTheme.colorScheme.surfaceVariant,
        ),
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
