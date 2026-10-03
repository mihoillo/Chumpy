package slop.miho.aurora.xray

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import slop.miho.aurora.db.ProfileEntity

/**
 * Routing intent of a session: everything through the tunnel, or everything but the
 * geo-RU block. The rule sets mirror v2rayNG's global and "white Russia" presets.
 */
/**
 * The routing modes: one bypass region each — the region's own sites and addresses go
 * direct, the rest rides the proxy — with the whole-world mode last. The region set is
 * the panels' own (hiddify's ir/cn/ru/af/id/tr/br, v2rayNG's Russia and Iran presets).
 */
enum class RoutingMode(val region: String?) {
    BYPASS_RU("ru"),
    BYPASS_CN("cn"),
    BYPASS_IR("ir"),
    BYPASS_AF("af"),
    BYPASS_ID("id"),
    BYPASS_TR("tr"),
    BYPASS_BR("br"),
    GLOBAL(null),
}

/**
 * Assembles the complete Xray config documents the core runs: the full VPN config (TUN +
 * local SOCKS inbounds, the selected proxy, direct/block outbounds, routing by
 * [RoutingMode]) and the bare single-outbound document used for delay measurements.
 *
 * Everything is plain JSON string building over [OutboundBuilder] — the core takes a JSON
 * document, the UI is the only place it is produced.
 */
object XrayConfigBuilder {
    const val PROXY_TAG = "proxy"
    const val DIRECT_TAG = "direct"
    const val BLOCK_TAG = "block"
    const val SOCKS_TAG = "socks"
    const val TUN_TAG = "tun"

    /** The loopback SOCKS port the UI reaches the running core on. */
    const val LOCAL_SOCKS_PORT = 10808

    /** The default URL real delay measurements fetch through the tested outbound. */
    const val DEFAULT_TEST_URL = "https://www.gstatic.com/generate_204"

    private const val TUN_NAME = "aurora0"
    private const val TUN_MTU = 1500
    private const val USER_LEVEL = 8

    /** The document the VPN service hands to startLoop alongside the TUN descriptor. */
    fun buildFull(profile: ProfileEntity, mode: RoutingMode): String = buildJsonObject {
        put("stats", buildJsonObject {})
        put("log", buildJsonObject { put("loglevel", "warning") })
        put(
            "policy",
            buildJsonObject {
                put(
                    "levels",
                    buildJsonObject {
                        put(
                            "8",
                            buildJsonObject {
                                put("handshake", 4)
                                put("connIdle", 300)
                                put("uplinkOnly", 1)
                                put("downlinkOnly", 1)
                            },
                        )
                    },
                )
                put(
                    "system",
                    buildJsonObject {
                        put("statsOutboundUplink", true)
                        put("statsOutboundDownlink", true)
                    },
                )
            },
        )
        put(
            "inbounds",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("tag", SOCKS_TAG)
                        put("port", LOCAL_SOCKS_PORT)
                        put("listen", "127.0.0.1")
                        put("protocol", "socks")
                        put(
                            "settings",
                            buildJsonObject {
                                put("auth", "noauth")
                                put("udp", true)
                                put("userLevel", USER_LEVEL)
                            },
                        )
                        sniffing()
                    },
                )
                add(
                    buildJsonObject {
                        put("tag", TUN_TAG)
                        put("protocol", "tun")
                        put(
                            "settings",
                            buildJsonObject {
                                put("name", TUN_NAME)
                                put("mtu", TUN_MTU)
                                put("userLevel", USER_LEVEL)
                            },
                        )
                        sniffing()
                    },
                )
            },
        )
        put(
            "outbounds",
            buildJsonArray {
                add(OutboundBuilder.build(profile, PROXY_TAG))
                add(
                    buildJsonObject {
                        put("tag", DIRECT_TAG)
                        put("protocol", "freedom")
                        put("settings", buildJsonObject { put("domainStrategy", "UseIP") })
                    },
                )
                add(
                    buildJsonObject {
                        put("tag", BLOCK_TAG)
                        put("protocol", "blackhole")
                        put("settings", buildJsonObject { put("response", buildJsonObject { put("type", "http") }) })
                    },
                )
            },
        )
        put("routing", routing(mode))
        put(
            "dns",
            buildJsonObject {
                put("hosts", buildJsonObject {})
                put(
                    "servers",
                    buildJsonArray {
                        add(JsonPrimitive("local"))
                        add(JsonPrimitive("1.1.1.1"))
                        add(JsonPrimitive("8.8.8.8"))
                    },
                )
            },
        )
    }.toString()

    /**
     * The bare document for [slop.miho.aurora.core.XrayCore.measureOutboundDelay]: one
     * outbound, no inbounds — the measurement core dials the test URL through it.
     */
    fun buildTest(profile: ProfileEntity): String = buildJsonObject {
        put("log", buildJsonObject { put("loglevel", "warning") })
        put("inbounds", buildJsonArray {})
        put(
            "outbounds",
            buildJsonArray { add(OutboundBuilder.build(profile, PROXY_TAG)) },
        )
        put(
            "routing",
            buildJsonObject {
                put("domainStrategy", "AsIs")
                put("rules", buildJsonArray {})
            },
        )
    }.toString()

    private fun kotlinx.serialization.json.JsonObjectBuilder.sniffing() {
        put(
            "sniffing",
            buildJsonObject {
                put("enabled", true)
                put(
                    "destOverride",
                    buildJsonArray {
                        add(JsonPrimitive("http"))
                        add(JsonPrimitive("tls"))
                        add(JsonPrimitive("quic"))
                    },
                )
            },
        )
    }

    private fun routing(mode: RoutingMode) = buildJsonObject {
        put("domainStrategy", "AsIs")
        put(
            "rules",
            buildJsonArray {
                mode.region?.let { region ->
                    // Sniffed BitTorrent goes direct in bypass mode, as in v2rayNG's presets.
                    rule {
                        put("protocol", buildJsonArray { add(JsonPrimitive("bittorrent")) })
                        put("outboundTag", DIRECT_TAG)
                    }
                    rule {
                        put("domain", buildJsonArray {
                            // The geosite lists exist for the big three (v2rayNG's own
                            // presets use these codes); the rest ride the country's TLD.
                            when (region) {
                                "ru" -> "geosite:category-ru"
                                "ir" -> "geosite:category-ir"
                                "cn" -> "geosite:cn"
                                else -> null
                            }?.let { add(JsonPrimitive(it)) }
                            add(JsonPrimitive("domain:$region"))
                        })
                        put("outboundTag", DIRECT_TAG)
                    }
                    rule {
                        put("ip", buildJsonArray { add(JsonPrimitive("geoip:$region")) })
                        put("outboundTag", DIRECT_TAG)
                    }
                }
                // QUIC would tunnel UDP 443; blocking it keeps traffic on sniffable TCP.
                rule {
                    put("port", "443")
                    put("network", "udp")
                    put("outboundTag", BLOCK_TAG)
                }
                rule {
                    put("ip", buildJsonArray { add(JsonPrimitive("geoip:private")) })
                    put("outboundTag", DIRECT_TAG)
                }
                rule {
                    put("domain", buildJsonArray { add(JsonPrimitive("geosite:private")) })
                    put("outboundTag", DIRECT_TAG)
                }
                // Whatever matched nothing rides the first outbound: the proxy.
            },
        )
    }

    private inline fun kotlinx.serialization.json.JsonArrayBuilder.rule(
        block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit,
    ) {
        add(
            buildJsonObject {
                put("type", "field")
                block()
            },
        )
    }
}
