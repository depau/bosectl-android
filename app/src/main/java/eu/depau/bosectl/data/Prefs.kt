package eu.depau.bosectl.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore by preferencesDataStore(name = "bosectl")

object Prefs {
    val DEVICE_MAC = stringPreferencesKey("device_mac")

    // Widget cache: last known state so the widget renders instantly/offline.
    val CACHE_DEVICE_NAME = stringPreferencesKey("cache_device_name")
    val CACHE_CONNECTED = booleanPreferencesKey("cache_connected")
    val CACHE_CURRENT_MODE = intPreferencesKey("cache_current_mode")
    val CACHE_SPATIAL = intPreferencesKey("cache_spatial")
    val CACHE_BATTERY = stringPreferencesKey("cache_battery")
    val CACHE_STARRED = stringPreferencesKey("cache_starred")
}

/** A starred mode as cached for the widget. */
data class CachedMode(val idx: Int, val promptId: Int, val name: String)

fun encodeCachedModes(modes: List<CachedMode>): String = JSONArray().apply {
    modes.forEach {
        put(JSONObject().put("i", it.idx).put("p", it.promptId).put("n", it.name))
    }
}.toString()

fun decodeCachedModes(json: String?): List<CachedMode> = runCatching {
    val arr = JSONArray(json ?: return emptyList())
    (0 until arr.length()).map {
        val o = arr.getJSONObject(it)
        CachedMode(o.getInt("i"), o.getInt("p"), o.getString("n"))
    }
}.getOrDefault(emptyList())
