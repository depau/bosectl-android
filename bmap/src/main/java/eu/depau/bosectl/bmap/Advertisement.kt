package eu.depau.bosectl.bmap

/**
 * Bose's BLE advertisement (`docs/PROTOCOL.md` §15). Lets the app tell that the
 * earbuds are nearby, and whether they would accept a connection, without
 * connecting to them at all.
 */

/** Bluetooth SIG company identifier for Bose Corporation. */
const val BOSE_COMPANY_ID = 0x009E

/** BMAP 1.2.0 advertises product ids offset by 100 (`OFFSET_BLE_120_FORMAT_VSPITFIRE`). */
private const val PRODUCT_ID_OFFSET_120 = 100

data class BoseAdvertisement(
    /** BLE product id, e.g. 104 = QC Ultra Earbuds, 108 = QC Ultra Earbuds 2nd gen. */
    val bleProductId: Int,
    val variant: Int,
    val charging: Boolean,
    /** The device has a free slot and would accept a connection. */
    val availableToConnect: Boolean,
    val supportsIap: Boolean,
    val inPairingMode: Boolean,
    val leAudioSupported: Boolean,
)

/**
 * Parse the manufacturer-specific data of a Bose advertisement.
 *
 * [data] is the payload *after* the company id, i.e. exactly what Android's
 * `ScanRecord.getManufacturerSpecificData(BOSE_COMPANY_ID)` returns.
 *
 * Only the BMAP 1.2.0 "Spitfire" format (format byte 0) is understood; anything
 * else returns null rather than guessing, since the flag offsets move per format.
 */
fun parseBoseAdvertisement(data: ByteArray): BoseAdvertisement? {
    if (data.size < 3) return null
    val format = data[0].toInt() and 0xFF
    if (format != 0) return null
    val productByte = data[1].toInt() and 0xFF
    val flags = data[2].toInt() and 0xFF
    return BoseAdvertisement(
        bleProductId = PRODUCT_ID_OFFSET_120 + (productByte and 0x1F),
        variant = productByte shr 5,
        charging = flags and 0x01 != 0,
        availableToConnect = flags and 0x02 != 0,
        supportsIap = flags and 0x04 != 0,
        inPairingMode = flags and 0x08 != 0,
        leAudioSupported = flags and 0x40 != 0,
    )
}
