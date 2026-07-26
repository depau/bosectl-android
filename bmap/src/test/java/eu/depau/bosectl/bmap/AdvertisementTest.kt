package eu.depau.bosectl.bmap

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Both vectors are real advertisements from QC Ultra Earbuds 2nd gen, captured
 * with BlueZ (`ManufacturerData` for company 0x009E, company id stripped).
 */
private fun hex(s: String): ByteArray =
    s.replace(" ", "").chunked(2).map { it.toInt(16).toByte() }.toByteArray()

class AdvertisementTest {

    @Test
    fun idleEarbudsAvailableToConnect() {
        // Captured while connected to a laptop, not in pairing mode.
        val ad = parseBoseAdvertisement(hex("00 a8 06 88 ef b4 6b 71 49"))!!
        assertEquals(108, ad.bleProductId)   // Edith = QC Ultra Earbuds 2nd gen
        assertEquals(5, ad.variant)
        assertTrue(ad.availableToConnect)
        assertTrue(ad.supportsIap)
        assertFalse(ad.inPairingMode)
        assertFalse(ad.charging)
        assertFalse(ad.leAudioSupported)
    }

    @Test
    fun pairingModeIsVisibleInTheAdvertisement() {
        // Captured seconds after holding the Bluetooth button; this is also when
        // the earbuds switch to advertising their identity address.
        val ad = parseBoseAdvertisement(hex("00 a8 0e 07 bf bb 61 22 28"))!!
        assertEquals(108, ad.bleProductId)
        assertTrue(ad.inPairingMode)
        assertTrue(ad.availableToConnect)
    }

    @Test
    fun unknownFormatIsRejectedRatherThanGuessed() {
        // Flag offsets differ per advertising format, so a format we have never
        // seen must not be decoded with the 1.2.0 layout.
        assertNull(parseBoseAdvertisement(hex("03 a8 06 88 ef b4")))
    }

    @Test
    fun truncatedDataIsRejected() {
        assertNull(parseBoseAdvertisement(hex("00 a8")))
        assertNull(parseBoseAdvertisement(ByteArray(0)))
    }
}
