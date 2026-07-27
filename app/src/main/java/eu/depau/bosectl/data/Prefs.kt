package eu.depau.bosectl.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import org.json.JSONArray
import org.json.JSONObject

val Context.dataStore by preferencesDataStore(name = "bosectl")

object Prefs {
    val DEVICE_MAC = stringPreferencesKey("device_mac")

    /** Which link layer to carry BMAP over; see [LinkLayer]. */
    val LINK_LAYER = stringPreferencesKey("link_layer")

    /** Nearby detection: last BLE advertisement seen, epoch millis. */
    val LAST_SEEN_AT = longPreferencesKey("last_seen_at")

    /** Whether the earbuds said they were available to connect when last seen. */
    val LAST_SEEN_AVAILABLE = booleanPreferencesKey("last_seen_available")

    /**
     * BLE product id of the selected device, learned the first time it is seen
     * advertising its identity address. Lets later sightings be recognised even
     * when the earbuds advertise a rotating random address.
     */
    val DEVICE_BLE_PRODUCT_ID = intPreferencesKey("device_ble_product_id")

    /** Whether the user enabled nearby detection (needs BLUETOOTH_SCAN). */
    val PRESENCE_ENABLED = booleanPreferencesKey("presence_enabled")

    // Widget cache: last known state so the widget renders instantly/offline.
    val CACHE_DEVICE_NAME = stringPreferencesKey("cache_device_name")
    val CACHE_CONNECTED = booleanPreferencesKey("cache_connected")
    val CACHE_CURRENT_MODE = intPreferencesKey("cache_current_mode")
    val CACHE_SPATIAL = intPreferencesKey("cache_spatial")
    val CACHE_BAT_LEFT = intPreferencesKey("cache_bat_left")
    val CACHE_BAT_RIGHT = intPreferencesKey("cache_bat_right")
    val CACHE_BAT_CASE = intPreferencesKey("cache_bat_case")
    val CACHE_BAT_OVERALL = intPreferencesKey("cache_bat_overall")
    val CACHE_STARRED = stringPreferencesKey("cache_starred")
    val CACHE_ANC = booleanPreferencesKey("cache_anc")
    val CACHE_CNC = intPreferencesKey("cache_cnc")
    val CACHE_TOUCH = booleanPreferencesKey("cache_touch")

    /** [LinkLayer.id] of the live connection, so the widget can show which link. */
    val CACHE_LINK = stringPreferencesKey("cache_link")

    /** Whether the earbuds are playing *this* phone, per [5.1]. */
    val CACHE_PLAYING_HERE = booleanPreferencesKey("cache_playing_here")
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
