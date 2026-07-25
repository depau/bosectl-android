package eu.depau.bosectl.ui

import android.media.AudioManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.bmap.ACTION_DISABLED
import eu.depau.bosectl.bmap.BUTTON_ACTIONS
import eu.depau.bosectl.bmap.ButtonId
import eu.depau.bosectl.bmap.BmapAuthException
import eu.depau.bosectl.bmap.ButtonMapping
import eu.depau.bosectl.bmap.Sidetone
import eu.depau.bosectl.bmap.VOICE_LANGUAGES
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.launch

private data class SettingsSnapshot(
    val sidetone: Sidetone = Sidetone.OFF,
    val multipoint: Boolean = false,
    val autoPause: Boolean = false,
    val autoAnswer: Boolean = false,
    val touchControls: Boolean = true,
    val autoTransparency: Boolean = false,
    val promptsEnabled: Boolean = false,
    val promptsLanguage: Int = 0,
    val buttons: List<ButtonMapping> = emptyList(),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, onOpenEq: () -> Unit) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var snapshot by remember { mutableStateOf<SettingsSnapshot?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var showPowerOff by remember { mutableStateOf(false) }
    var showRename by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching {
            DeviceRepository.withDevice { conn ->
                SettingsSnapshot(
                    sidetone = runCatching { conn.sidetone() }.getOrDefault(Sidetone.OFF),
                    multipoint = runCatching { conn.multipoint() }.getOrDefault(false),
                    autoPause = runCatching { conn.autoPause() }.getOrDefault(false),
                    autoAnswer = runCatching { conn.autoAnswer() }.getOrDefault(false),
                    touchControls = runCatching { conn.touchControls() }.getOrDefault(true),
                    autoTransparency = runCatching { conn.autoTransparency() }
                        .getOrDefault(false),
                    promptsEnabled = runCatching { conn.voicePrompts().first }.getOrDefault(false),
                    promptsLanguage = runCatching { conn.voicePrompts().second }.getOrDefault(0),
                    buttons = runCatching { conn.buttons() }.getOrDefault(emptyList()),
                )
            }
        }.onSuccess { snapshot = it }
            .onFailure { loadError = it.message }
    }

    fun update(block: SettingsSnapshot.() -> SettingsSnapshot) {
        snapshot = snapshot?.block()
    }

    fun act(block: suspend () -> Unit) {
        scope.launch {
            runCatching { block() }.onFailure {
                actionError = if (it is BmapAuthException)
                    "The device rejected this change (authentication required)"
                else it.message
            }
        }
    }

    // Layout is stable from first frame: titles render immediately, values show
    // skeletons until the device answers.
    val s = snapshot ?: SettingsSnapshot()
    val loading = snapshot == null && loadError == null
    val loaded = snapshot != null && state.connected

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())
        ) {
            (actionError ?: loadError)?.let {
                Text(
                    it,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }

            SectionHeader("Audio")
            VolumeItem()
            ListItem(
                headlineContent = { Text("Equalizer") },
                supportingContent = { Text("Bass, mid and treble") },
                modifier = Modifier.clickableIf(state.connected, onOpenEq),
            )
            RadioPickerItem(
                title = "Hear your own voice in calls",
                subtitle = "Plays your voice back during calls: " +
                        sidetoneLabels.getValue(s.sidetone),
                enabled = loaded,
                loading = loading,
                options = sidetoneLabels.mapKeys { it.key.value },
                selected = s.sidetone.value,
            ) { value ->
                val level = Sidetone.fromValue(value)
                update { copy(sidetone = level) }
                act { DeviceRepository.withDevice { it.setSidetone(level) } }
            }
            SwitchItem(
                "Voice prompts",
                // Don't show a default language before the device has answered.
                if (loading) "Spoken announcements"
                else "Spoken announcements (language: " +
                        "${VOICE_LANGUAGES[s.promptsLanguage] ?: "unknown"})",
                s.promptsEnabled, loaded, loading,
            ) { v ->
                update { copy(promptsEnabled = v) }
                act { DeviceRepository.withDevice { it.setVoicePrompts(v, s.promptsLanguage) } }
            }

            SectionHeader("In-ear detection")
            SwitchItem("Auto play/pause", "Pause when an earbud is removed",
                s.autoPause, loaded, loading) { v ->
                update { copy(autoPause = v) }
                act { DeviceRepository.withDevice { it.setAutoPause(v) } }
            }
            SwitchItem("Auto-answer calls", "Answer by putting an earbud in",
                s.autoAnswer, loaded, loading) { v ->
                update { copy(autoAnswer = v) }
                act { DeviceRepository.withDevice { it.setAutoAnswer(v) } }
            }
            SwitchItem(
                "Auto transparency", "Let outside sound through while only one earbud is worn",
                s.autoTransparency, loaded, loading,
            ) { v ->
                update { copy(autoTransparency = v) }
                act { DeviceRepository.withDevice { it.setAutoTransparency(v) } }
            }

            SectionHeader("Controls")
            SwitchItem(
                "Touch controls", "Tap and swipe gestures on the earbuds",
                s.touchControls, loaded, loading,
            ) { v ->
                update { copy(touchControls = v) }
                act { DeviceRepository.withDevice { it.setTouchControls(v) } }
            }
            // One row per configurable button: earbuds report a left and a
            // right shortcut, headphones a single one.
            if (loading) {
                repeat(2) { ShortcutRowSkeleton() }
            }
            s.buttons.sortedByDescending { it.buttonId }.forEach { button ->
                RadioPickerItem(
                    title = buttonTitle(button.buttonId),
                    subtitle = "Touch and hold: " +
                            (BUTTON_ACTIONS[button.action] ?: "Action ${button.action}"),
                    enabled = loaded && s.touchControls,
                    loading = false,
                    options = shortcutOptions(button),
                    selected = button.action,
                ) { action ->
                    update {
                        copy(buttons = buttons.map {
                            if (it.buttonId == button.buttonId) it.copy(action = action) else it
                        })
                    }
                    act {
                        DeviceRepository.withDevice {
                            it.setButtons(button.buttonId, button.event, action)
                        }
                    }
                }
            }

            SectionHeader("Bluetooth & device")
            ListItem(
                headlineContent = { Text("Bluetooth pairing mode") },
                supportingContent = { Text("Make the headphones discoverable for a new device") },
                trailingContent = {
                    Button(
                        onClick = { act { DeviceRepository.withDevice { it.setPairingMode(true) } } },
                        enabled = state.connected,
                    ) { Text("Start") }
                },
            )
            SwitchItem("Multipoint", "Connect two devices at once",
                s.multipoint, loaded, loading) { v ->
                update { copy(multipoint = v) }
                act { DeviceRepository.withDevice { it.setMultipoint(v) } }
            }
            ListItem(
                headlineContent = { Text("Device name") },
                supportingContent = { Text(state.deviceName ?: "") },
                modifier = Modifier.clickableIf(state.connected) { showRename = true },
            )
            ListItem(
                headlineContent = {
                    Text("Power off", color = MaterialTheme.colorScheme.error)
                },
                modifier = Modifier.clickableIf(state.connected) { showPowerOff = true },
            )

            HorizontalDivider()
            ListItem(
                headlineContent = { Text("Change device") },
                supportingContent = { Text("Set up different headphones") },
                modifier = Modifier.clickableIf(true) {
                    scope.launch {
                        DeviceRepository.clearDevice()
                        onBack()
                    }
                },
            )
            state.firmware?.let {
                ListItem(
                    headlineContent = { Text("Firmware") },
                    supportingContent = { Text(it) },
                )
            }
        }
    }

    if (showPowerOff) {
        AlertDialog(
            onDismissRequest = { showPowerOff = false },
            title = { Text("Power off?") },
            text = { Text("The headphones will disconnect and turn off.") },
            confirmButton = {
                TextButton(onClick = {
                    showPowerOff = false
                    act { DeviceRepository.withDevice { it.powerOff() } }
                }) { Text("Power off") }
            },
            dismissButton = {
                TextButton(onClick = { showPowerOff = false }) { Text("Cancel") }
            },
        )
    }

    if (showRename) {
        var newName by remember { mutableStateOf(state.deviceName ?: "") }
        AlertDialog(
            onDismissRequest = { showRename = false },
            title = { Text("Rename device") },
            text = {
                OutlinedTextField(
                    value = newName, onValueChange = { newName = it }, singleLine = true,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRename = false
                        act {
                            DeviceRepository.withDevice { it.setName(newName) }
                            DeviceRepository.refresh()
                        }
                    },
                    enabled = newName.isNotBlank(),
                ) { Text("Rename") }
            },
            dismissButton = {
                TextButton(onClick = { showRename = false }) { Text("Cancel") }
            },
        )
    }
}

private fun buttonTitle(buttonId: Int) = when (buttonId) {
    ButtonId.LEFT_SHORTCUT -> "Left earbud shortcut"
    ButtonId.RIGHT_SHORTCUT -> "Right earbud shortcut"
    else -> "Shortcut button"
}

/**
 * Selectable actions for a shortcut, in the official app's order, with
 * "Disabled" surfaced last as the off switch.
 */
private fun shortcutOptions(button: ButtonMapping): Map<Int, String> {
    val preferred = listOf(17, 19, 1, 16)  // modes, immersive audio, assistant, Spotify
    val supported = button.supportedActions.ifEmpty { preferred + ACTION_DISABLED }
    val ordered = preferred.filter { it in supported } +
            supported.filter { it !in preferred && it != ACTION_DISABLED }.sorted()
    return (ordered + listOf(ACTION_DISABLED).filter { it in supported })
        .associateWith { if (it == ACTION_DISABLED) "Off" else BUTTON_ACTIONS[it] ?: "Action $it" }
}

private val sidetoneLabels = mapOf(
    Sidetone.OFF to "Off", Sidetone.LOW to "Low",
    Sidetone.MEDIUM to "Medium", Sidetone.HIGH to "High",
)

fun Modifier.clickableIf(enabled: Boolean, onClick: () -> Unit): Modifier =
    if (enabled) this.then(Modifier.clickable(onClick = onClick)) else this

/**
 * Earbud volume. There is no BMAP volume register on this firmware — the
 * earbuds use AVRCP absolute volume, so the media stream volume IS the earbud
 * volume. Driving it here re-syncs the two when they drift apart.
 */
@Composable
private fun VolumeItem() {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    val maxVolume = remember {
        audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 15
    }
    // ponytail: read on entry only; add a ContentObserver if live sync with the
    // hardware keys while this screen is open ever matters.
    var volume by remember {
        mutableIntStateOf(audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0)
    }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(AppIcons.VolumeUp, contentDescription = null)
            Text("Volume", Modifier.padding(start = 16.dp).weight(1f))
            Text(
                "$volume/$maxVolume",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Slider(
            value = volume.toFloat(),
            onValueChange = {
                volume = it.toInt()
                audioManager?.setStreamVolume(AudioManager.STREAM_MUSIC, volume, 0)
            },
            valueRange = 0f..maxVolume.toFloat(),
            steps = (maxVolume - 1).coerceAtLeast(0),
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun SwitchItem(
    title: String,
    subtitle: String?,
    checked: Boolean,
    enabled: Boolean,
    loading: Boolean = false,
    onChange: (Boolean) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = subtitle?.let { { Text(it) } },
        trailingContent = {
            if (loading) SkeletonBox(Modifier.width(52.dp).height(32.dp), cornerRadius = 16.dp)
            else Switch(checked = checked, onCheckedChange = onChange, enabled = enabled)
        },
    )
}

/** Placeholder for a shortcut row whose button list hasn't arrived yet. */
@Composable
private fun ShortcutRowSkeleton() {
    ListItem(
        headlineContent = {
            SkeletonBox(Modifier.width(180.dp).height(18.dp), cornerRadius = 4.dp)
        },
        supportingContent = {
            SkeletonBox(Modifier.width(220.dp).height(14.dp), cornerRadius = 4.dp)
        },
    )
}

/** Settings-style row that opens a radio-list dialog. */
@Composable
private fun <K> RadioPickerItem(
    title: String,
    subtitle: String,
    enabled: Boolean,
    loading: Boolean = false,
    options: Map<K, String>,
    selected: K?,
    onSelect: (K) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            if (loading) SkeletonBox(Modifier.width(220.dp).height(14.dp), cornerRadius = 4.dp)
            else Text(subtitle)
        },
        modifier = Modifier.clickableIf(enabled) { open = true },
    )
    if (open) {
        AlertDialog(
            onDismissRequest = { open = false },
            title = { Text(title) },
            text = {
                Column {
                    options.forEach { (key, label) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .selectable(
                                    selected = key == selected,
                                    onClick = { open = false; onSelect(key) },
                                )
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = key == selected, onClick = null)
                            Text(label, Modifier.padding(start = 12.dp))
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { open = false }) { Text("Cancel") }
            },
        )
    }
}
