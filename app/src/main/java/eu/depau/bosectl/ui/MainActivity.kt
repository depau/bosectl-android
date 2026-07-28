package eu.depau.bosectl.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import eu.depau.bosectl.data.DeviceRepository
import eu.depau.bosectl.data.Prefs
import eu.depau.bosectl.data.dataStore
import eu.depau.bosectl.ui.theme.BoseControlTheme
import eu.depau.bosectl.widget.publishWidgetPreview
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/** Base for all screens: theme + edge-to-edge + repository init. */
abstract class BoseActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DeviceRepository.init(this)
        // App launch is the only moment the widget picker can be prepared before
        // the user opens it.
        lifecycleScope.launch { publishWidgetPreview(this@BoseActivity) }
        enableEdgeToEdge()
        setContent {
            BoseControlTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Content()
                }
            }
        }
    }

    // Refresh on resume, but only if the earbuds are reachable: opening a screen
    // is not a request to wake them up (see autoConnectIfAvailable).
    override fun onResume() {
        super.onResume()
        DeviceRepository.restorePresence()
        // Re-arming is cheap and idempotent (FLAG_UPDATE_CURRENT), and the scan
        // is registered with the system, so it outlives the process either way.
        DeviceRepository.startPresenceScanIfEnabled()
        DeviceRepository.onScreenVisible()
    }

    override fun onPause() {
        super.onPause()
        DeviceRepository.onScreenHidden()
    }

    @Composable
    abstract fun Content()
}

class MainActivity : BoseActivity() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        var hasPermission by remember {
            mutableStateOf(
                ContextCompat.checkSelfPermission(
                    context, Manifest.permission.BLUETOOTH_CONNECT
                ) == PackageManager.PERMISSION_GRANTED
            )
        }
        val permissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission()
        ) { hasPermission = it }

        if (!hasPermission) {
            Column(
                Modifier.fillMaxSize().padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    "Bose Control needs the Bluetooth permission to talk to your headphones.",
                    style = MaterialTheme.typography.bodyLarge,
                )
                Button(onClick = {
                    permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
                }) {
                    Text("Grant permission")
                }
            }
            return
        }

        // Reactive: "Change device" in Settings clears the pref and this flips
        // back to the setup screen automatically.
        val deviceMac by remember {
            context.dataStore.data.map { it[Prefs.DEVICE_MAC] }
        }.collectAsState(initial = LOADING)

        when (deviceMac) {
            LOADING -> Unit
            null -> DeviceSetupScreen(onDeviceSelected = {})
            else -> HomeScreen(
                onOpenProfiles = {
                    context.startActivity(Intent(context, ProfilesActivity::class.java))
                },
                onOpenSettings = {
                    context.startActivity(Intent(context, SettingsActivity::class.java))
                },
                onOpenConnections = {
                    context.startActivity(Intent(context, ConnectionsActivity::class.java))
                },
            )
        }
    }

    private companion object {
        const val LOADING = "\u0000loading"
    }
}

class ProfilesActivity : BoseActivity() {
    @Composable
    override fun Content() = ProfilesScreen(onBack = { finish() })
}

class SettingsActivity : BoseActivity() {
    @Composable
    override fun Content() {
        val context = LocalContext.current
        SettingsScreen(
            onBack = { finish() },
            onOpenEq = { context.startActivity(Intent(context, EqActivity::class.java)) },
            onOpenConnections = {
                context.startActivity(Intent(context, ConnectionsActivity::class.java))
            },
        )
    }
}

class EqActivity : BoseActivity() {
    @Composable
    override fun Content() = EqScreen(onBack = { finish() })
}

class ConnectionsActivity : BoseActivity() {
    @Composable
    override fun Content() = ConnectionsScreen(onBack = { finish() })
}
