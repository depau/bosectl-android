package eu.depau.bosectl.data

import android.bluetooth.BluetoothDevice
import android.content.Context
import eu.depau.bosectl.bmap.BmapTransport
import eu.depau.bosectl.bmap.GattTransport
import eu.depau.bosectl.bmap.RfcommTransport

/**
 * The transports BMAP can ride on. Both speak the identical protocol — see
 * `docs/PROTOCOL.md` §15 — so anything above [BmapTransport] is unaffected by
 * the choice.
 */
enum class LinkLayer(val id: String) {
    /** RFCOMM channel 2. Needs a classic link, which means the phone holds audio. */
    CLASSIC("classic"),

    /** BLE/GATT. Works while the earbuds play to a *different* host. */
    LE("le"),
    ;

    companion object {
        fun fromId(id: String?): LinkLayer? = entries.firstOrNull { it.id == id }
    }
}

/** What the user picked in Settings. */
enum class LinkPreference(val id: String) {
    /** Classic while a classic link exists, LE otherwise. The default. */
    AUTO("auto"),
    CLASSIC_ONLY("classic"),
    LE_ONLY("le"),
    ;

    companion object {
        fun fromId(id: String?): LinkPreference =
            entries.firstOrNull { it.id == id } ?: AUTO
    }
}

/**
 * Which transports to try, in order.
 *
 * Connecting RFCOMM to an idle device would *initiate* a classic connection and
 * steal the audio from whatever is playing — so [LinkPreference.AUTO] only
 * reaches for CLASSIC when the link is already up.
 *
 * [automatic] marks a connect the user did not ask for (a nearby sighting, the
 * ACL receiver, a screen opening). Those may **never** bring up a classic link,
 * whatever the preference says; only an explicit tap may. That is the difference
 * between "the app reconnected" and "the app hijacked my music".
 */
fun linkOrder(
    preference: LinkPreference,
    classicLinkUp: Boolean,
    automatic: Boolean = false,
): List<LinkLayer> {
    val order = when (preference) {
        LinkPreference.CLASSIC_ONLY -> listOf(LinkLayer.CLASSIC)
        LinkPreference.LE_ONLY -> listOf(LinkLayer.LE)
        LinkPreference.AUTO ->
            if (classicLinkUp) listOf(LinkLayer.CLASSIC, LinkLayer.LE)
            else listOf(LinkLayer.LE)
    }
    return if (automatic && !classicLinkUp) order - LinkLayer.CLASSIC else order
}

suspend fun openTransport(
    link: LinkLayer, context: Context, device: BluetoothDevice,
): BmapTransport = when (link) {
    LinkLayer.CLASSIC -> RfcommTransport.connect(device)
    LinkLayer.LE -> GattTransport.connect(context, device)
}
