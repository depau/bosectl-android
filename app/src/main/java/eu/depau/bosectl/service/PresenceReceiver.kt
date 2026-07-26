package eu.depau.bosectl.service

import android.bluetooth.le.ScanResult
import android.bluetooth.le.BluetoothLeScanner
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.depau.bosectl.bmap.BOSE_COMPANY_ID
import eu.depau.bosectl.bmap.parseBoseAdvertisement
import eu.depau.bosectl.data.DeviceRepository

/**
 * Receives system-delivered BLE scan results (see `PresenceScanner`). Fires while
 * the app is otherwise not running, which is what makes nearby detection free of
 * a foreground service.
 */
class PresenceReceiver : BroadcastReceiver() {

    @Suppress("DEPRECATION")  // the typed overload is API 33+, minSdk is 31
    override fun onReceive(context: Context, intent: Intent) {
        val error = intent.getIntExtra(BluetoothLeScanner.EXTRA_ERROR_CODE, -1)
        if (error != -1) {
            Log.w(TAG, "Scan reported error $error")
            return
        }
        val results = intent.getParcelableArrayListExtra<ScanResult>(
            BluetoothLeScanner.EXTRA_LIST_SCAN_RESULT
        ) ?: return

        val sightings = results.mapNotNull { result ->
            val data = result.scanRecord
                ?.getManufacturerSpecificData(BOSE_COMPANY_ID) ?: return@mapNotNull null
            val ad = parseBoseAdvertisement(data) ?: return@mapNotNull null
            result.device.address to ad
        }
        if (sightings.isEmpty()) return

        DeviceRepository.init(context)
        // The DataStore write outlives onReceive, so hold the receiver open.
        val pending = goAsync()
        DeviceRepository.onAdvertisementsSeen(sightings) { pending.finish() }
    }

    private companion object {
        const val TAG = "PresenceReceiver"
    }
}
