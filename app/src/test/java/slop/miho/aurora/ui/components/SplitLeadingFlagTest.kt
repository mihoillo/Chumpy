package slop.miho.aurora.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The leading-flag split: panels prefix profile names with their country's flag, and the
 * list moves it into the icon slot, out of the title.
 */
class SplitLeadingFlagTest {
    @Test
    fun `flag with title`() {
        val (flag, title) = splitLeadingFlag("🇩🇪 Германия · DE 1")
        assertEquals("🇩🇪", flag)
        assertEquals("Германия · DE 1", title)
    }

    @Test
    fun `two leading flags travel together`() {
        val (flag, title) = splitLeadingFlag("🇺🇸🇬🇧 US-UK mixed")
        assertEquals("🇺🇸🇬🇧", flag)
        assertEquals("US-UK mixed", title)
    }

    @Test
    fun `no flag comes back whole`() {
        val (flag, title) = splitLeadingFlag("Frankfurt 1")
        assertNull(flag)
        assertEquals("Frankfurt 1", title)
    }

    @Test
    fun `leading whitespace before the flag is skipped`() {
        val (flag, title) = splitLeadingFlag("  🇫🇮 Helsinki")
        assertEquals("🇫🇮", flag)
        assertEquals("Helsinki", title)
    }

    @Test
    fun `non-flag emoji is not a flag`() {
        // Lightning bolts and lock emoji are not regional indicators: the name stays whole.
        val (flag, title) = splitLeadingFlag("⚡ Fast exit")
        assertNull(flag)
        assertEquals("⚡ Fast exit", title)
    }


    @Test
    fun `rainbow flag splits off`() {
        val (pride, title) = splitLeadingPrideFlag("🏳️‍🌈 Pride node")
        assertEquals(PrideFlag.RAINBOW, pride)
        assertEquals("Pride node", title)
    }

    @Test
    fun `trans flag splits off`() {
        val (pride, title) = splitLeadingPrideFlag("🏳️‍⚧️ Trans node")
        assertEquals(PrideFlag.TRANS, pride)
        assertEquals("Trans node", title)
    }

    @Test
    fun `pride before a country flag leaves the country one behind`() {
        val (pride, rest) = splitLeadingPrideFlag("🏳️‍🌈🇩🇪 Berlin")
        assertEquals(PrideFlag.RAINBOW, pride)
        assertEquals("🇩🇪 Berlin", rest)
    }

    @Test
    fun `plain name has no pride flag`() {
        val (pride, title) = splitLeadingPrideFlag("🇩🇪 Германия")
        assertNull(pride)
        assertEquals("🇩🇪 Германия", title)
    }
}
