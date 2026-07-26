package eu.depau.bosectl.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import eu.depau.bosectl.bmap.DeviceExtendedInfo
import eu.depau.bosectl.bmap.DeviceProfiles
import eu.depau.bosectl.bmap.PairedDevice
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.delay

/** How long to keep showing progress for a connect that never lands. */
private const val CONNECT_TIMEOUT_MS = 20_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectionsScreen(onBack: () -> Unit) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    var sheetMac by remember { mutableStateOf<String?>(null) }
    var confirmLocalDisconnect by remember { mutableStateOf<PairedDevice?>(null) }
    // [4.1] acks with PROCESSING and the link comes up seconds later, so the
    // only real signal is the device turning up connected in a [4.4] push.
    var pending by remember { mutableStateOf(emptySet<String>()) }
    var multipoint by remember { mutableStateOf<Boolean?>(null) }

    LaunchedEffect(state.connected) {
        multipoint =
            if (!state.connected) null
            else runCatching { DeviceRepository.withDevice { it.multipoint() } }.getOrNull()
    }

    LaunchedEffect(state.pairedDevices) {
        pending = pending.filterTo(mutableSetOf()) { mac ->
            state.pairedDevices.none { it.mac == mac && it.connected }
        }
    }

    LaunchedEffect(pending) {
        if (pending.isEmpty()) return@LaunchedEffect
        delay(CONNECT_TIMEOUT_MS)
        pending = emptySet()
    }

    fun connect(device: PairedDevice) {
        pending = pending + device.mac
        deviceAction { DeviceRepository.connectSource(device.mac) }
    }

    fun disconnect(device: PairedDevice) {
        if (device.isLocalDevice) confirmLocalDisconnect = device
        else deviceAction { DeviceRepository.disconnectSource(device.mac) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connections") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(AppIcons.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
    ) { padding ->
        if (!state.connected) {
            DisconnectedCard(
                busy = state.busy, error = state.lastError,
                modifier = Modifier.padding(padding).padding(16.dp),
            ) { DeviceRepository.onDeviceAppeared() }
            return@Scaffold
        }

        val connected = state.connectedDevices
        val known = state.pairedDevices.filter { !it.connected }

        // One list, keyed by MAC: when a device connects it changes section and
        // animateItem slides it there instead of blinking out and back.
        LazyColumn(Modifier.padding(padding).fillMaxSize()) {
            item(key = "pairing") {
                ListItem(
                    headlineContent = { Text("Pairing mode") },
                    supportingContent = { Text("Make the earbuds discoverable to a new device") },
                    trailingContent = {
                        Button(onClick = {
                            deviceAction {
                                DeviceRepository.withDevice { it.setPairingMode(true) }
                            }
                        }) { Text("Start") }
                    },
                )
            }
            item(key = "multipoint") {
                ListItem(
                    headlineContent = { Text("Multipoint") },
                    supportingContent = { Text("Stay connected to two devices at once") },
                    trailingContent = {
                        Switch(
                            checked = multipoint == true,
                            enabled = multipoint != null,
                            onCheckedChange = { value ->
                                multipoint = value
                                deviceAction {
                                    DeviceRepository.withDevice { it.setMultipoint(value) }
                                }
                            },
                        )
                    },
                )
            }
            item(key = "divider") { HorizontalDivider() }

            if (connected.isNotEmpty()) {
                item(key = "header-connected") { SectionHeader("Connected") }
            }
            items(connected, key = { it.mac }) { device ->
                DeviceRow(
                    device = device,
                    playing = device.mac == state.activeSourceMac,
                    pending = false,
                    onClick = { sheetMac = device.mac },
                    onToggle = { disconnect(device) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (known.isNotEmpty()) {
                item(key = "header-known") { SectionHeader("Known devices") }
            }
            items(known, key = { it.mac }) { device ->
                DeviceRow(
                    device = device,
                    playing = false,
                    pending = device.mac in pending,
                    onClick = { sheetMac = device.mac },
                    onToggle = { connect(device) },
                    modifier = Modifier.animateItem(),
                )
            }
            if (state.pairedDevices.isEmpty()) {
                item {
                    Text(
                        "The earbuds don't list any paired devices.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
    }

    DeviceSheetHost(
        mac = sheetMac,
        pending = pending,
        onConnect = ::connect,
        onDisconnect = ::disconnect,
        onDismiss = { sheetMac = null },
    )

    confirmLocalDisconnect?.let { device ->
        ConfirmLocalDisconnectDialog(
            device = device,
            onDismiss = { confirmLocalDisconnect = null },
            onConfirm = {
                confirmLocalDisconnect = null
                deviceAction { DeviceRepository.disconnectSource(device.mac) }
            },
        )
    }
}

@Composable
private fun DeviceRow(
    device: PairedDevice,
    playing: Boolean,
    pending: Boolean,
    onClick: () -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ListItem(
        headlineContent = { Text(device.name.ifEmpty { device.mac }) },
        supportingContent = deviceTags(device, playing)?.let { { Text(it) } },
        leadingContent = { Icon(AppIcons.Bluetooth, contentDescription = null) },
        trailingContent = {
            // Optimistically on while a connect is in flight, and locked until
            // the [4.4] push settles it — flipping back to off mid-connect
            // would read as failure.
            Switch(
                checked = device.connected || pending,
                enabled = !pending,
                onCheckedChange = { onToggle() },
            )
        },
        modifier = modifier.clickable(onClick = onClick),
    )
}

/**
 * The device details sheet plus its confirmations. Home and the Connections
 * page open the same sheet, so the wiring lives here once; the caller only
 * tracks which MAC is open.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DeviceSheetHost(
    mac: String?,
    pending: Set<String>,
    onConnect: (PairedDevice) -> Unit,
    onDisconnect: (PairedDevice) -> Unit,
    onDismiss: () -> Unit,
) {
    val state by DeviceRepository.state.collectAsStateWithLifecycle()
    var forgetTarget by remember { mutableStateOf<PairedDevice?>(null) }

    // Resolved against live state so the sheet follows connect/disconnect while
    // open, rather than showing a snapshot from when the row was tapped.
    val device = mac?.let { m -> state.pairedDevices.firstOrNull { it.mac == m } }
    LaunchedEffect(mac, device == null) {
        if (mac != null && device == null) onDismiss()
    }
    if (device == null) return

    var profiles by remember(device.mac) { mutableStateOf<DeviceExtendedInfo?>(null) }
    LaunchedEffect(device.mac, device.connected) {
        profiles = runCatching {
            DeviceRepository.withDevice { it.deviceExtendedInfo(device.mac) }
        }.getOrNull()
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primaryContainer,
                modifier = Modifier.size(56.dp),
            ) {
                Icon(
                    AppIcons.Bluetooth, contentDescription = null,
                    modifier = Modifier.padding(16.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                )
            }
            Text(
                device.name.ifEmpty { "Unnamed device" },
                style = MaterialTheme.typography.headlineSmall,
                textAlign = TextAlign.Center,
            )
            Text(
                device.mac,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                statusLine(device, device.mac == state.activeSourceMac),
                style = MaterialTheme.typography.bodyMedium,
            )

            // Which Bluetooth profiles are live (or, when it's away, which it
            // supports). Plain text under a label: these are facts about the
            // device, and as chips they looked tappable.
            //
            // The divider and label render immediately and the value is a
            // skeleton until [4.6] answers — the section used to appear a
            // moment after the sheet opened and shove the buttons downwards.
            HorizontalDivider(Modifier.padding(vertical = 4.dp))
            Text(
                if (device.connected) "ACTIVE CONNECTIONS" else "SUPPORTS",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (profiles == null) {
                SkeletonBox(Modifier.width(180.dp).height(20.dp), cornerRadius = 4.dp)
            } else {
                val set = if (device.connected) profiles!!.connected else profiles!!.paired
                Text(
                    profileNames(set).joinToString(" · ").ifEmpty { "None" },
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                )
            }

            if (device.connected) {
                OutlinedButton(
                    onClick = { onDisconnect(device) },
                    modifier = Modifier.fillMaxWidth(),
                ) { Text("Disconnect") }
            } else {
                val busy = device.mac in pending
                Button(
                    onClick = { onConnect(device) },
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    if (busy) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Text("Connect")
                    }
                }
            }
            TextButton(onClick = { forgetTarget = device }) {
                Text("Forget device", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    forgetTarget?.let { target ->
        val name = target.name.ifEmpty { target.mac }
        AlertDialog(
            onDismissRequest = { forgetTarget = null },
            title = { Text("Forget $name?") },
            text = {
                Text(
                    "The earbuds will remove $name from their list of known " +
                            "devices. This can't be undone from here — to use it " +
                            "again you'll have to pair it from that device."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgetTarget = null
                    onDismiss()
                    deviceAction { DeviceRepository.forgetSource(target.mac) }
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { forgetTarget = null }) { Text("Cancel") }
            },
        )
    }
}

private fun profileNames(profiles: DeviceProfiles): List<String> = listOfNotNull(
    "Audio".takeIf { profiles.a2dp },
    "Calls".takeIf { profiles.hfp },
    "Controls".takeIf { profiles.avrcp },
    "Data".takeIf { profiles.spp },
)

private fun statusLine(device: PairedDevice, playing: Boolean): String {
    if (!device.connected) return "Not connected"
    return listOfNotNull(
        "Connected",
        "this device".takeIf { device.isLocalDevice },
        "playing".takeIf { playing },
    ).joinToString(" · ")
}

/**
 * "this device" and "playing" are different facts — the phone running the app
 * is usually not the one holding audio — so both are shown when they apply.
 */
internal fun deviceTags(device: PairedDevice, playing: Boolean): String? =
    listOfNotNull(
        "this device".takeIf { device.isLocalDevice },
        "playing".takeIf { playing },
    ).joinToString(" · ").ifEmpty { null }

/**
 * Dropping the device the app runs on also closes our own BMAP link, so that
 * one disconnect asks first. Shared with the Home screen's section.
 */
@Composable
fun ConfirmLocalDisconnectDialog(
    device: PairedDevice,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Disconnect this device?") },
        text = {
            Text(
                "Bose Control is running on ${device.name.ifEmpty { device.mac }}. " +
                        "Disconnecting it also closes the app's connection to the earbuds."
            )
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("Disconnect") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
