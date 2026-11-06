package slop.miho.chumpy.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The LAN-friendly route split, asserted on the whole IPv4 space: the blocks must add
 * up to the public internet and nothing more. A hole in the coverage leaks traffic
 * around the tunnel; an overzealous block swallows the local network back into the
 * core — the exact breakage the split exists to prevent (KDE Connect's discovery
 * broadcasts cannot ride a tunnel).
 */
class LanBypassRouteTest {
    /** The addresses whose path must not change: they ride the physical network. */
    private val localSamples = listOf(
        "10.0.0.1",          // RFC1918 10/8
        "172.16.0.1",         // RFC1918 172.16/12
        "172.19.0.1",         // RFC1918 172.16/12 — the TUN's own address lives here too
        "172.31.255.254",     // RFC1918 172.16/12, last address
        "192.168.1.1",        // RFC1918 192.168/16 — the home LAN
        "192.168.1.255",      // the subnet broadcast KDE Connect shouts at
        "224.0.0.251",        // mDNS
        "239.255.255.250",    // SSDP
        "255.255.255.255",    // the global broadcast
    )

    /** The addresses that must ride the tunnel: the internet, DNS included. */
    private val publicSamples = listOf(
        "1.1.1.1",           // the VPN's own DNS server
        "8.8.8.8",           // the VPN's own DNS server
        "9.9.9.9",           // 9/8 rides on the 8/7 block
        "11.0.0.1",          // the 11/8 island the split routes around 10/8 with
        "100.64.0.1",        // CGNAT: routed like today, the core's private rules keep it direct
        "172.15.0.1",        // just below the 172.16/12 hole
        "172.32.0.1",        // just above it
        "192.0.0.1",         // the 192.0.0.0/24 special block stays routed
        "192.167.0.1",       // just below the 192.168/16 hole
        "192.169.0.1",       // just above it
        "200.100.50.25",     // a plain public address
        "104.244.42.1",      // a plain public address
    )

    @Test
    fun `every sixteenth is either fully routed or fully local`() {
        // A /16 is the finest grain the block boundaries cut at, so each of the 65536
        // must fall entirely on one side: routed into the tunnel, or left on the
        // physical network. The local side is exactly RFC1918 plus the 224–255 tail
        // (multicast, class E, broadcast) — anything else on that side is a routing
        // hole, and anything local on the routed side is the breakage this split
        // exists to prevent.
        for (first in 0..255) {
            for (second in 0..255) {
                val expectedLocal = first == 10 ||
                    (first == 172 && second in 16..31) ||
                    (first == 192 && second == 168) ||
                    first in 224..255
                val routed = ChumpyVpnService.ROUTED_IP_BLOCKS.any { (block, prefix) ->
                    covers(block, prefix, "$first.$second.0.0", 16)
                }
                if (expectedLocal) {
                    assertFalse("/$first.$second.0.0/16 must stay out of the tunnel", routed)
                } else {
                    assertTrue("/$first.$second.0.0/16 must be routed into the tunnel", routed)
                }
            }
        }
    }

    @Test
    fun `local addresses stay off the tunnel`() {
        localSamples.forEach { address ->
            assertFalse(
                "$address must ride the physical network",
                ChumpyVpnService.ROUTED_IP_BLOCKS.any { (block, prefix) -> covers(block, prefix, address, 32) },
            )
        }
    }

    @Test
    fun `public addresses ride the tunnel`() {
        publicSamples.forEach { address ->
            assertTrue(
                "$address must ride the tunnel",
                ChumpyVpnService.ROUTED_IP_BLOCKS.any { (block, prefix) -> covers(block, prefix, address, 32) },
            )
        }
    }

    /** Whether [block] with [prefix] contains [address] with [addressPrefix]. */
    private fun covers(block: String, prefix: Int, address: String, addressPrefix: Int): Boolean {
        val blockValue = ipOf(block)
        val addressValue = ipOf(address)
        // A block contains a subnet only when it is at least as wide as the subnet.
        if (prefix > addressPrefix) return false
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return blockValue and mask == addressValue and mask
    }

    /** The address as an unsigned 32-bit integer, /32 semantics. */
    private fun ipOf(address: String): Long {
        val parts = address.split(".").map { it.toInt() }
        assertEquals("malformed test address: $address", 4, parts.size)
        return parts.fold(0L) { acc, part -> acc shl 8 or (part.toLong() and 0xFF) }
    }
}
