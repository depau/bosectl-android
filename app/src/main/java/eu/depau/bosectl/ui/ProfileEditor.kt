package eu.depau.bosectl.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import eu.depau.bosectl.bmap.ModeConfig
import eu.depau.bosectl.bmap.Prompt
import eu.depau.bosectl.bmap.Spatial
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.launch

/** Icons offered by the official app's "new mode" page, in the same order. */
private val PICKER_PROMPTS = listOf(
    Prompt.COMMUTE, Prompt.FOCUS, Prompt.HOME, Prompt.MUSIC, Prompt.OUTDOOR,
    Prompt.RELAX, Prompt.RUN, Prompt.WALK, Prompt.WORK, Prompt.WORKOUT,
    Prompt.QUIET, Prompt.AWARE, Prompt.IMMERSION, Prompt.CINEMA, Prompt.COMMUTE,
).distinct() + listOf(
    Prompt.FLIGHT, Prompt.AIRPORT, Prompt.DRIVING, Prompt.GYM, Prompt.HIKE,
    Prompt.TALK, Prompt.CALL, Prompt.HEARING, Prompt.LEARN, Prompt.PODCAST,
    Prompt.AUDIOBOOK, Prompt.CALM, Prompt.SLEEP, Prompt.MEDITATE, Prompt.YOGA,
    Prompt.STEREO, Prompt.NONE,
)

/**
 * Create/edit/view a profile. For presets ([mode] not editable) everything is
 * shown disabled with a lock note, like the official app.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditorSheet(mode: ModeConfig?, slot: Int, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val editable = mode?.editable ?: true
    val isNew = mode == null

    var name by remember { mutableStateOf(mode?.name ?: "") }
    var promptId by remember { mutableIntStateOf(mode?.promptId ?: Prompt.MUSIC.id) }
    var cncSlider by remember { mutableFloatStateOf((10 - (mode?.cncLevel ?: 0)).toFloat()) }
    var spatial by remember { mutableStateOf(mode?.spatial ?: Spatial.OFF) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                when {
                    isNew -> "New mode"
                    editable -> "Edit mode"
                    else -> mode!!.name
                },
                style = MaterialTheme.typography.headlineSmall,
            )

            if (!editable) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        AppIcons.Lock, contentDescription = null,
                        Modifier.size(18.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Preset modes can't be modified",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(31) },
                label = { Text("Name") },
                singleLine = true,
                enabled = editable,
                modifier = Modifier.fillMaxWidth(),
            )

            Text("Icon and voice prompt", style = MaterialTheme.typography.titleSmall)
            LazyHorizontalGrid(
                rows = GridCells.Fixed(2),
                modifier = Modifier.fillMaxWidth().height(112.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(PICKER_PROMPTS, key = { it.id }) { prompt ->
                    val selected = prompt.id == promptId
                    Surface(
                        shape = CircleShape,
                        color = if (selected) MaterialTheme.colorScheme.primaryContainer
                        else MaterialTheme.colorScheme.surfaceVariant,
                        modifier = Modifier
                            .size(48.dp)
                            .let {
                                if (selected) it.border(
                                    2.dp, MaterialTheme.colorScheme.primary, CircleShape
                                ) else it
                            }
                            .clickable(enabled = editable) { promptId = prompt.id },
                    ) {
                        Icon(
                            prompt.icon, contentDescription = prompt.label,
                            Modifier.padding(12.dp),
                            tint = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(AppIcons.Hearing, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text("Noise cancellation", Modifier.weight(1f))
                Text(
                    "${cncSlider.toInt()}/10",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Slider(
                value = cncSlider,
                onValueChange = { cncSlider = it },
                valueRange = 0f..10f,
                steps = 9,
                enabled = editable,
            )

            Text("Immersive audio", style = MaterialTheme.typography.titleSmall)
            ImmersiveAudioSelector(
                selected = spatial,
                enabled = editable,
                onSelect = { spatial = it },
            )

            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

            if (editable) {
                Button(
                    onClick = {
                        saving = true
                        error = null
                        scope.launch {
                            runCatching {
                                DeviceRepository.saveProfile(
                                    slot = slot,
                                    name = name.ifBlank { "Custom" },
                                    promptId = promptId,
                                    cncLevel = 10 - cncSlider.toInt(),
                                    spatial = spatial,
                                )
                            }.onSuccess { onDismiss() }
                                .onFailure {
                                    saving = false
                                    error = it.message ?: "Write failed"
                                }
                        }
                    },
                    enabled = !saving && name.isNotBlank(),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (saving) "Saving…" else "Save")
                }
                if (!isNew) {
                    TextButton(
                        onClick = {
                            saving = true
                            scope.launch {
                                runCatching { DeviceRepository.deleteProfile(slot) }
                                    .onSuccess { onDismiss() }
                                    .onFailure {
                                        saving = false
                                        error = it.message ?: "Delete failed"
                                    }
                            }
                        },
                        enabled = !saving,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("Delete mode", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}
