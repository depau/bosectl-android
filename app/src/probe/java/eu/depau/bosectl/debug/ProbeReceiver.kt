package eu.depau.bosectl.debug

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import eu.depau.bosectl.bmap.Op
import eu.depau.bosectl.bmap.bmapPacket
import eu.depau.bosectl.data.DeviceRepository
import kotlinx.coroutines.launch
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
                        "write" -> writeProbe(label)
                        "variants" -> writeVariants(label)
                        "prompts" -> promptSweep(label)
                        "roundtrip" -> roundTrip(label)
                        "listen" -> listenProbe(label)
                        "notify" -> notifyProbe(label)
                        "subscribe" -> subscribeProbe(label)
                        "notifystart" -> notifyStartProbe(label)
                        "ble" -> BleProbe(context, label)
                            .run(intent.getIntExtra("mtu", 23))
                        "bleoffline" -> BleProbe(context, label)
                            .runOffline(intent.getIntExtra("mtu", 23))
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

    /** Attempt a profile write into the first free slot and read it back. */
    private suspend fun writeProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            val before = conn.snapshot()
            before.modes.forEach { Log.i(TAG, "[$label] before: $it") }
            val slot = before.modes.firstOrNull { it.isFreeSlot }?.modeIdx
            if (slot == null) {
                Log.i(TAG, "[$label] no free slot")
                return@withDevice
            }
            Log.i(TAG, "[$label] writing slot $slot")
            runCatching {
                conn.writeMode(
                    slot = slot, name = "ProbeTest", promptId = 12,
                    cncLevel = 5, spatial = eu.depau.bosectl.bmap.Spatial.OFF,
                )
            }.onSuccess { Log.i(TAG, "[$label] write returned OK") }
                .onFailure { Log.e(TAG, "[$label] write threw: $it") }
            conn.snapshot().modes.filter { it.modeIdx == slot }
                .forEach { Log.i(TAG, "[$label] after: $it") }
        }
    }

    /** Bisect which ModeConfig field the firmware objects to. Free slots only. */
    private suspend fun writeVariants(label: String) {
        DeviceRepository.withDevice { conn ->
            val free = conn.snapshot().modes.filter { it.isFreeSlot }.map { it.modeIdx }
            Log.i(TAG, "[$label] free slots: $free")
            val slot = free.firstOrNull() ?: return@withDevice

            // name, promptId, cnc, autoCnc, spatial, wind, anc
            val variants = listOf(
                Triple("baseline mirrors slot as reported", slot,
                    byteArrayOf(0, 0, 10, 0, 0, 1, 1)),
                Triple("named, otherwise as reported", slot,
                    byteArrayOf(1, 0, 10, 0, 0, 1, 1)),
                Triple("wind off", slot, byteArrayOf(1, 0, 10, 0, 0, 0, 1)),
                Triple("cnc 5", slot, byteArrayOf(1, 0, 5, 0, 0, 1, 1)),
                Triple("prompt COMMUTE(7)", slot, byteArrayOf(1, 7, 10, 0, 0, 1, 1)),
                Triple("prompt MUSIC(12)", slot, byteArrayOf(1, 12, 10, 0, 0, 1, 1)),
                Triple("second free slot", free.getOrElse(1) { slot },
                    byteArrayOf(1, 0, 10, 0, 0, 1, 1)),
            )
            for ((desc, target, v) in variants) {
                val name = if (v[0].toInt() == 0) "None" else "ProbeTest"
                val payload = byteArrayOf(target.toByte(), 0, v[1]) +
                    eu.depau.bosectl.bmap.encodeModeName(name) +
                    byteArrayOf(v[2], v[3], v[4], v[5], v[6])
                val result = runCatching {
                    conn.raw(bmapPacket(31, 6, Op.SETGET, payload), drain = true)
                }.getOrElse { listOf<eu.depau.bosectl.bmap.BmapPacket>() }
                Log.i(TAG, "[$label] $desc (slot $target) -> " +
                    (result.joinToString { "$it" }.ifEmpty { "no reply" }))
            }
        }
    }

    /** Which voice-prompt ids does the firmware accept? Writes to a free slot. */
    private suspend fun promptSweep(label: String) {
        DeviceRepository.withDevice { conn ->
            val slot = conn.snapshot().modes.firstOrNull { it.isFreeSlot }?.modeIdx
            if (slot == null) {
                Log.i(TAG, "[$label] no free slot")
                return@withDevice
            }
            val accepted = mutableListOf<Int>()
            for (prompt in 0..36) {
                val payload = byteArrayOf(slot.toByte(), 0, prompt.toByte()) +
                    eu.depau.bosectl.bmap.encodeModeName("P$prompt") +
                    byteArrayOf(10, 0, 0, 0, 1)   // wind MUST be 0 or Runtime 8
                val ok = runCatching {
                    conn.raw(bmapPacket(31, 6, Op.SETGET, payload), drain = true)
                }.getOrDefault(emptyList()).any { it.op == Op.STATUS }
                if (ok) accepted.add(prompt)
            }
            Log.i(TAG, "[$label] accepted prompt ids: $accepted")
            // Put the slot back the way it was found.
            val restore = byteArrayOf(slot.toByte(), 0, 0) +
                eu.depau.bosectl.bmap.encodeModeName("None") +
                byteArrayOf(10, 0, 0, 0, 1)
            conn.raw(bmapPacket(31, 6, Op.SETGET, restore), drain = true)
            conn.snapshot().modes.filter { it.modeIdx == slot }
                .forEach { Log.i(TAG, "[$label] restored: $it") }
        }
    }

    /** Exercise the real app path: create a profile, read back, delete, verify. */
    private suspend fun roundTrip(label: String) {
        DeviceRepository.withDevice { conn ->
            // Clear any leftovers from earlier probing first.
            conn.snapshot().modes.filter { it.name == "ProbeTest" || it.name.startsWith("P") &&
                it.name.drop(1).toIntOrNull() != null }.forEach {
                Log.i(TAG, "[$label] clearing stale ${it.name} in slot ${it.modeIdx}")
                runCatching { conn.deleteMode(it.modeIdx) }
            }
            val slot = conn.snapshot().modes.firstOrNull { it.isFreeSlot }?.modeIdx ?: return@withDevice
            runCatching {
                conn.writeMode(slot, "Roundtrip", promptId = 12, cncLevel = 5, spatial = eu.depau.bosectl.bmap.Spatial.STILL)
            }.onSuccess { Log.i(TAG, "[$label] create OK in slot $slot") }
                .onFailure { Log.e(TAG, "[$label] create FAILED: $it") }
            conn.snapshot().modes.filter { it.modeIdx == slot }
                .forEach { Log.i(TAG, "[$label] created: $it") }
            runCatching { conn.deleteMode(slot) }
                .onSuccess { Log.i(TAG, "[$label] delete OK") }
                .onFailure { Log.e(TAG, "[$label] delete FAILED: $it") }
            conn.snapshot().modes.filter { it.modeIdx == slot }
                .forEach { Log.i(TAG, "[$label] after delete: $it") }
        }
    }

    /**
     * Is the socket alive, and does the device push anything unprompted?
     * Reads state, sits idle for 40s logging every frame, then reads again.
     */
    private suspend fun listenProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            Log.i(TAG, "[$label] connected=${conn.isConnected}")
            Log.i(TAG, "[$label] mode BEFORE=${conn.currentModeIdx()} " +
                "spatial=${conn.audioSettings()?.spatial}")
            val seen = mutableListOf<String>()
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                conn.unsolicited.collect {
                    seen.add(it.toString())
                    Log.i(TAG, "[$label] UNSOLICITED: $it")
                }
            }
            Log.i(TAG, "[$label] listening for 40s — change the mode on the earbuds now")
            kotlinx.coroutines.delay(40_000)
            job.cancel()
            Log.i(TAG, "[$label] unsolicited frames seen: ${seen.size}")
            Log.i(TAG, "[$label] still connected=${conn.isConnected}")
            Log.i(TAG, "[$label] mode AFTER=${conn.currentModeIdx()} " +
                "spatial=${conn.audioSettings()?.spatial}")
        }
    }

    /** Look for a notification/subscription mechanism (read-only). */
    private suspend fun notifyProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            for (func in 0..8) {
                runCatching { conn.raw(bmapPacket(9, func, Op.GET)) }
                    .onSuccess { it.forEach { p -> Log.i(TAG, "[$label] [9.$func] $p") } }
                    .onFailure { Log.i(TAG, "[$label] [9.$func] ERR $it") }
            }
            // Some BMAP blocks start pushing once GetAll is issued on them.
            Log.i(TAG, "[$label] GetAll on Notification block:")
            runCatching { conn.raw(bmapPacket(9, 1, Op.START), drain = true) }
                .onSuccess { it.forEach { p -> Log.i(TAG, "[$label]   $p") } }
                .onFailure { Log.i(TAG, "[$label]   ERR $it") }
        }
    }

    /** Try [9.2] as a notification subscription mask, then restore it. */
    private suspend fun subscribeProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            val before = conn.raw(bmapPacket(9, 2, Op.GET)).firstOrNull()
            Log.i(TAG, "[$label] [9.2] before: $before")
            for (mask in listOf("ffffffff", "00000001")) {
                val bytes = mask.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
                val reply = runCatching {
                    conn.raw(bmapPacket(9, 2, Op.SETGET, bytes), drain = true)
                }.getOrElse { listOf() }
                Log.i(TAG, "[$label] SETGET $mask -> ${reply.joinToString()}")
                if (reply.any { it.op == Op.STATUS }) break
            }
            Log.i(TAG, "[$label] [9.2] now: ${conn.raw(bmapPacket(9, 2, Op.GET)).firstOrNull()}")

            val seen = mutableListOf<String>()
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                conn.unsolicited.collect {
                    seen.add(it.toString())
                    Log.i(TAG, "[$label] PUSH: $it")
                }
            }
            Log.i(TAG, "[$label] listening 40s — change the mode on the earbuds now")
            kotlinx.coroutines.delay(40_000)
            job.cancel()
            Log.i(TAG, "[$label] pushes seen: ${seen.size}")

            // Leave the device as we found it.
            conn.raw(bmapPacket(9, 2, Op.SETGET, byteArrayOf(0, 0, 0, 0)), drain = true)
            Log.i(TAG, "[$label] restored: ${conn.raw(bmapPacket(9, 2, Op.GET)).firstOrNull()}")
        }
    }

    /** Does GetAll on the Notification block [9.1] subscribe us to pushes? */
    private suspend fun notifyStartProbe(label: String) {
        DeviceRepository.withDevice { conn ->
            val r = runCatching { conn.raw(bmapPacket(9, 1, Op.START), drain = true) }
                .getOrElse { listOf() }
            Log.i(TAG, "[$label] [9.1] START -> ${r.joinToString()}")

            val seen = mutableListOf<String>()
            val job = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                conn.unsolicited.collect {
                    seen.add(it.toString())
                    Log.i(TAG, "[$label] PUSH: $it")
                }
            }
            Log.i(TAG, "[$label] listening 40s — change mode/head tracking now")
            kotlinx.coroutines.delay(40_000)
            job.cancel()
            Log.i(TAG, "[$label] pushes seen: ${seen.size}")
        }
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
