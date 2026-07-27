package eu.depau.bosectl.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * The one rule worth a test: an automatic connect must never bring up a classic
 * link, because RFCOMM to an idle device steals the audio from whatever is
 * playing. Everything else here is ordering preference.
 */
class LinkLayerTest {

    @Test
    fun automaticConnectNeverInitiatesClassic() {
        for (preference in LinkPreference.entries) {
            val order = linkOrder(preference, classicLinkUp = false, automatic = true)
            assertFalse(
                "$preference would have initiated a classic connection",
                LinkLayer.CLASSIC in order,
            )
        }
    }

    @Test
    fun automaticConnectUsesClassicWhenTheLinkIsAlreadyUp() {
        // Nothing to steal: the earbuds are already connected to this phone.
        assertEquals(
            listOf(LinkLayer.CLASSIC, LinkLayer.LE),
            linkOrder(LinkPreference.AUTO, classicLinkUp = true, automatic = true),
        )
    }

    @Test
    fun explicitConnectMayForceClassic() {
        // A deliberate tap is allowed to take the audio.
        assertEquals(
            listOf(LinkLayer.CLASSIC),
            linkOrder(LinkPreference.CLASSIC_ONLY, classicLinkUp = false, automatic = false),
        )
    }

    @Test
    fun autoPrefersClassicWhenConnectedAndLeOtherwise() {
        assertEquals(
            listOf(LinkLayer.CLASSIC, LinkLayer.LE),
            linkOrder(LinkPreference.AUTO, classicLinkUp = true),
        )
        assertEquals(
            listOf(LinkLayer.LE),
            linkOrder(LinkPreference.AUTO, classicLinkUp = false),
        )
    }

    @Test
    fun forcingLeIgnoresTheClassicLink() {
        assertEquals(
            listOf(LinkLayer.LE),
            linkOrder(LinkPreference.LE_ONLY, classicLinkUp = true, automatic = true),
        )
    }

    @Test
    fun classicOnlyWithNoLinkLeavesNothingToTryAutomatically() {
        // The caller must treat an empty order as "don't touch the radio".
        assertEquals(
            emptyList<LinkLayer>(),
            linkOrder(LinkPreference.CLASSIC_ONLY, classicLinkUp = false, automatic = true),
        )
    }
}
