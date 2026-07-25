package eu.depau.bosectl.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.runBlocking

/**
 * Debug-only protocol probe. Not compiled into release builds.
 *   adb shell am broadcast -a eu.depau.bosectl.PROBE -p eu.depau.bosectl
 */
class ProbeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        DeviceRepository.init(context)
        val mode = intent.getStringExtra("mode")
        val label = intent.getStringExtra("label") ?: "run"
        val pending = goAsync()
        Thread {
            try {
                runBlocking {
                    when (mode) {
                        "quick" -> quickProbe(label)
                        "settings" -> settingsProbe(label)
                        else -> probe()
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "probe failed", e)
            } finally {
                pending.finish()
            }
        }.start()
    }

    /** Settings GetAll only — the fast way to diff after a stock-app toggle. */
    private suspend fun settingsProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            conn.raw(bmapPacket(1, 1, Op.START), drain = true)
                .filter { it.op == Op.STATUS }
                .forEach { Log.i(TAG, "[$label] $it") }
        }
    }

    /** Focused re-read of the registers under investigation, for delta testing. */
    private suspend fun quickProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            for ((fblock, func) in listOf(
                1 to 10, 5 to 3, 5 to 4, 5 to 5, 5 to 7, 5 to 13,
            )) {
                runCatching { conn.raw(bmapPacket(fblock, func, Op.GET)) }
                    .onSuccess { it.forEach { p -> Log.i(TAG, "[$label] $p") } }
                    .onFailure { Log.i(TAG, "[$label] [$fblock.$func] ERR $it") }
            }
        }
    }

    private suspend fun probe() {
        DeviceRepository.withDevice { conn ->
            // GetAll on Settings [1.1] and Control [7.1]: one START each, drained
            for (fblock in listOf(1, 7, 5)) {
                Log.i(TAG, "===== GetAll [$fblock.1] START =====")
                runCatching {
                    conn.raw(bmapPacket(fblock, 1, Op.START), drain = true)
                }.onSuccess { packets ->
                    packets.forEach { Log.i(TAG, "  $it") }
                }.onFailure { Log.i(TAG, "  ERR $it") }
            }

            // Raw payloads of settings whose parse is suspect (multipoint shows
            // as enabled in the app while it is actually off).
            Log.i(TAG, "===== Settings raw =====")
            for ((fblock, func, name) in listOf(
                Triple(1, 10, "multipoint"), Triple(1, 24, "autoPause"),
                Triple(1, 27, "autoAnswer"), Triple(1, 11, "sidetone"),
            )) {
                runCatching { conn.raw(bmapPacket(fblock, func, Op.GET)) }
                    .onSuccess { it.forEach { p -> Log.i(TAG, "  $name -> $p") } }
                    .onFailure { Log.i(TAG, "  $name -> ERR $it") }
            }

            // Buttons [1.9]: plain GET, then GET with a button id (left=4, right=3,
            // shortcut=0x80) to see if the device answers per-button.
            Log.i(TAG, "===== Buttons [1.9] =====")
            for (payload in listOf(
                byteArrayOf(), byteArrayOf(3), byteArrayOf(4), byteArrayOf(0x80.toByte()),
                byteArrayOf(3, 9), byteArrayOf(4, 9), byteArrayOf(4, 5),
            )) {
                val label = payload.joinToString("") { "%02x".format(it) }.ifEmpty { "(none)" }
                runCatching {
                    conn.raw(bmapPacket(1, 9, Op.GET, payload), drain = true)
                }.onSuccess { packets ->
                    packets.forEach { Log.i(TAG, "  GET $label -> $it") }
                }.onFailure { Log.i(TAG, "  GET $label -> ERR $it") }
            }

            // Hunt for volume: AudioManagement [5.x] and Control [7.x] functions.
            Log.i(TAG, "===== Block 5 / 7 scan =====")
            for ((fblock, funcs) in listOf(5 to 0..20, 7 to 0..10)) {
                for (func in funcs) {
                    runCatching {
                        conn.raw(bmapPacket(fblock, func, Op.GET))
                    }.onSuccess { packets ->
                        packets.filter { it.op != Op.ERROR || it.payload.firstOrNull()
                            ?.toInt()?.and(0xFF) !in listOf(3, 4) }
                            .forEach { Log.i(TAG, "  [$fblock.$func] $it") }
                    }.onFailure { }
                }
            }
        }
    }

    private companion object {
        const val TAG = "BmapProbe"
    }
}
