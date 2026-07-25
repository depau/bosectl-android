package eu.depau.bosectl.ui

import android.annotation.SuppressLint
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bluetooth
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.launch

/**
 * Device picker over the phone's bonded (paired) devices — the earbuds are
 * already paired, so no discovery scan is needed (a scan wouldn't find them
 * anyway: connected headphones don't answer Bluetooth inquiry).
 * Only audio devices are shown by default; there is no reliable "is Bose"
 * signal (custom names, SDP cache usually lacks the vendor UUID).
 */
@SuppressLint("MissingPermission")  // BLUETOOTH_CONNECT granted before this screen
@Composable
fun DeviceSetupScreen(onDeviceSelected: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var showAll by remember { mutableStateOf(false) }

    val bonded = remember {
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter
        (adapter?.bondedDevices ?: emptySet()).toList()
    }
    fun isAudio(device: BluetoothDevice) =
        device.bluetoothClass?.majorDeviceClass == BluetoothClass.Device.Major.AUDIO_VIDEO

    val devices = if (showAll) bonded.sortedByDescending(::isAudio)
    else bonded.filter(::isAudio)

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(top = 24.dp)) {
        Text(
            "Select your Bose headphones",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.padding(horizontal = 24.dp),
        )
        Text(
            if (showAll) "All paired devices:" else "Paired audio devices:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (devices.isEmpty()) {
            Text(
                "Nothing found. Pair the headphones in the system Bluetooth " +
                        "settings first.",
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn(Modifier.weight(1f)) {
            items(devices, key = { it.address }) { device ->
                ListItem(
                    headlineContent = { Text(device.name ?: device.address) },
                    supportingContent = { Text(device.address) },
                    leadingContent = {
                        Icon(
                            if (isAudio(device)) Icons.Outlined.Headphones
                            else Icons.Outlined.Bluetooth,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp),
                            tint = if (isAudio(device)) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    },
                    modifier = Modifier.clickable {
                        scope.launch {
                            DeviceRepository.setDevice(device.address)
                            DeviceRepository.onDeviceAppeared()
                            onDeviceSelected(device.address)
                        }
                    },
                )
            }
        }
        TextButton(
            onClick = { showAll = !showAll },
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(if (showAll) "Show audio devices only" else "Show all devices")
        }
    }
}
