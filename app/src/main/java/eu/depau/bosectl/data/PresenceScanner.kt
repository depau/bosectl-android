package eu.depau.bosectl.data

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import eu.depau.bosectl.bmap.BOSE_COMPANY_ID
import eu.depau.bosectl.service.PresenceReceiver

private const val TAG = "PresenceScanner"
private const val REQUEST_CODE = 0x805E

/** Older sightings are stale: the earbuds advertise every couple of seconds. */
const val PRESENCE_FRESH_MS = 90_000L

/**
 * Detects the earbuds nearby without connecting and **without a foreground
 * service**: `startScan` with a [PendingIntent] is serviced by the system, which
 * wakes the app with a broadcast when a matching advertisement shows up. The scan
 * survives process death, so nothing needs to stay running.
 *
 * The filter is Bose's company id, so the system does the matching in the
 * Bluetooth stack rather than waking us for every beacon in the room.
 */
object PresenceScanner {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) ==
            PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")  // guarded by hasPermission
    fun start(context: Context): Boolean {
        if (!hasPermission(context)) return false
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner ?: return false
        val filter = ScanFilter.Builder()
            .setManufacturerData(BOSE_COMPANY_ID, byteArrayOf())
            .build()
        val settings = ScanSettings.Builder()
            // Low power is plenty: this answers "are they around", not "how far".
            .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
            .setCallbackType(ScanSettings.CALLBACK_TYPE_ALL_MATCHES)
            .build()
        val result = scanner.startScan(listOf(filter), settings, pendingIntent(context))
        Log.i(TAG, "startScan returned $result")
        return result == 0
    }

    @SuppressLint("MissingPermission")
    fun stop(context: Context) {
        if (!hasPermission(context)) return
        val scanner = context.getSystemService(BluetoothManager::class.java)
            ?.adapter?.bluetoothLeScanner ?: return
        runCatching { scanner.stopScan(pendingIntent(context)) }
            .onFailure { Log.w(TAG, "stopScan failed", it) }
    }

    /**
     * Must be mutable — the system fills the scan results into it — and must name
     * the receiver explicitly, which is also why the receiver need not be exported.
     */
    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, PresenceReceiver::class.java),
        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )
}
