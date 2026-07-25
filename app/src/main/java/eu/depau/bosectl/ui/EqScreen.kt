package eu.depau.bosectl.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.bmap.EqBand
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqScreen(onBack: () -> Unit) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var bands by remember { mutableStateOf<List<EqBand>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        runCatching { DeviceRepository.withDevice { it.eq() } }
            .onSuccess { bands = it }
            .onFailure { error = it.message }
    }

    fun write() {
        scope.launch {
            runCatching {
                DeviceRepository.withDevice { conn ->
                    conn.setEq(
                        bass = bands.firstOrNull { it.bandId == 0 }?.current ?: 0,
                        mid = bands.firstOrNull { it.bandId == 1 }?.current ?: 0,
                        treble = bands.firstOrNull { it.bandId == 2 }?.current ?: 0,
                    )
                }
            }.onFailure { error = it.message }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Equalizer") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Outlined.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            error?.let {
                Text(it, color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            val bandNames = listOf("Bass", "Mid", "Treble")
            bands.forEach { band ->
                EqSlider(
                    name = bandNames.getOrElse(band.bandId) { "Band ${band.bandId}" },
                    band = band,
                    enabled = state.connected,
                    onChange = { value ->
                        bands = bands.map {
                            if (it.bandId == band.bandId) it.copy(current = value) else it
                        }
                    },
                    onFinished = ::write,
                )
            }
        }
    }
}

@Composable
private fun EqSlider(
    name: String,
    band: EqBand,
    enabled: Boolean,
    onChange: (Int) -> Unit,
    onFinished: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, Modifier.width(56.dp), style = MaterialTheme.typography.bodyMedium)
        Slider(
            value = band.current.toFloat(),
            onValueChange = { onChange(it.toInt()) },
            onValueChangeFinished = onFinished,
            valueRange = band.minVal.toFloat()..band.maxVal.toFloat(),
            steps = (band.maxVal - band.minVal - 1).coerceAtLeast(0),
            enabled = enabled,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        Text(
            "%+d".format(band.current),
            Modifier.width(36.dp),
            style = MaterialTheme.typography.labelMedium,
        )
    }
}
