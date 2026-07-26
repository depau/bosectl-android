package eu.depau.bosectl.service

import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.IntentCompat
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Presence detection: ACL (dis)connect broadcasts (implicit-broadcast exempt,
 * so they wake the app) trigger connect/teardown for the selected device.
 */
class AclReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val device = IntentCompat.getParcelableExtra(
            intent, BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java
        ) ?: return
        DeviceRepository.init(context)
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                if (DeviceRepository.savedDeviceMac() != device.address) return@launch
                when (intent.action) {
                    // Bluetooth broadcasts guarded by BLUETOOTH_CONNECT are exempt
                    // from the background foreground-service start restrictions.
                    BluetoothDevice.ACTION_ACL_CONNECTED -> BoseConnectionService.start(context)
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        DeviceRepository.onDeviceDisappeared()
                        BoseConnectionService.stop(context)
                    }
                }
            } finally {
                pending.finish()
            }
        }
    }
}
