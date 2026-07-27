package eu.depau.bosectl.service

import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.depau.bosectl.data.DeviceRepository

/**
 * Re-arms the nearby scan after the three things that silently drop it: a reboot,
 * an app update, and Bluetooth being turned off and on again.
 *
 * Without this, detection works until the first of those and then quietly stops —
 * the failure mode being "it worked yesterday".
 */
class PresenceBootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED &&
            intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, -1) != BluetoothAdapter.STATE_ON
        ) {
            return   // only re-arm once the adapter is actually up
        }
        Log.i(TAG, "Re-arming nearby scan after ${intent.action}")
        DeviceRepository.init(context)
        val pending = goAsync()
        DeviceRepository.startPresenceScanIfEnabled { pending.finish() }
    }

    private companion object {
        const val TAG = "PresenceBoot"
    }
}
