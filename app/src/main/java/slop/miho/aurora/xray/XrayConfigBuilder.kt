package slop.miho.aurora.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType

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
    GLOBAL(null),
    BYPASS_RU("ru"),
    BYPASS_CN("cn"),
    BYPASS_IR("ir"),
    BYPASS_AF("af"),
    BYPASS_ID("id"),
    BYPASS_TR("tr"),
    BYPASS_BR("br"),
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

    /** The tag of the balancer over the members, when the session runs one. */
    private const val BALANCER_TAG = "balancer"

    /** How often the observatory re-probes a balancer's members. */
    private const val OBSERVATORY_PROBE_INTERVAL = "1m"

    /**
     * The strategies the core's balancer switch knows; the two observing ones need the
     * root-level observatory, which the builder emits with them.
     */
    private val OBSERVING_STRATEGIES = setOf("leastping", "leastload")

    /** The parser for the panel's carried routing rules; malformed ones are skipped. */
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /**
     * The document the VPN service hands to startLoop alongside the TUN descriptor.
     *
     * A BALANCER profile carries no endpoint of its own: its [members] — the resolved
     * member rows — ride along instead, each its own tagged outbound, with the balancer
     * over them and the routing's fall-through aimed at it. The first member keeps the
     * plain proxy tag: it is the default outbound a dead balancer falls back to, and the
     * tag the stats have always counted.
     */
    fun buildFull(
        profile: ProfileEntity,
        mode: RoutingMode,
        members: List<ProfileEntity> = emptyList(),
        testUrl: String = DEFAULT_TEST_URL,
    ): String {
        val balancerMembers = if (profile.type == ProfileType.BALANCER) members else emptyList()
        val strategy = profile.balancerStrategy.ifEmpty { "random" }
        return buildJsonObject {
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
                if (balancerMembers.isEmpty()) {
                    add(OutboundBuilder.build(profile, PROXY_TAG))
                } else {
                    balancerMembers.forEachIndexed { index, member ->
                        add(OutboundBuilder.build(member, memberTag(index)))
                    }
                }
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
        put(
            "routing",
            routing(
                mode,
                balanced = balancerMembers.isNotEmpty(),
                strategy = strategy,
                profileRules = profile.routingRules,
            )
        )
        if (strategy in OBSERVING_STRATEGIES && balancerMembers.isNotEmpty()) {
            // The observing strategies need a probe source: one root-level observatory
            // over the members, probing the delay-test URL the user's own settings
            // carry, on the clock of OBSERVATORY_PROBE_INTERVAL.
            put(
                "observatory",
                buildJsonObject {
                    put("subjectSelector", buildJsonArray { add(JsonPrimitive(PROXY_TAG)) })
                    put("probeURL", testUrl)
                    put("probeInterval", OBSERVATORY_PROBE_INTERVAL)
                    put("enableConcurrency", true)
                },
            )
        }
        put(
            "dns",
            buildJsonObject {
                put("hosts", buildJsonObject {})
                put(
                    "servers",
                    buildJsonArray {
                        // The first server is the one the wireguard outbound asks for its
                        // peer endpoint — and the only resolver that knows private-zone
                        // names (the system's). It must be "localhost": "local" is not a
                        // special address to the core, the endpoint lookup then answers
                        // "record not found", and the handshake never starts — a tunnel
                        // that is up while every packet dies. The public servers behind
                        // it catch what the system cannot resolve at all.
                        add(JsonPrimitive("localhost"))
                        add(JsonPrimitive("1.1.1.1"))
                        add(JsonPrimitive("8.8.8.8"))
                    },
                )
            },
        )
        }.toString()
    }

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

    private fun routing(
        mode: RoutingMode,
        balanced: Boolean,
        strategy: String,
        profileRules: String,
    ) = buildJsonObject {
        put("domainStrategy", "AsIs")
        if (balanced) {
            put(
                "balancers",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("tag", BALANCER_TAG)
                            // Every member tag carries the proxy prefix — the first is
                            // the plain tag itself — so the one selector covers them all.
                            put("selector", buildJsonArray { add(JsonPrimitive(PROXY_TAG)) })
                            put("strategy", buildJsonObject { put("type", strategy) })
                        },
                    )
                },
            )
        }
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
                // The panel's own rules, next to ours: bypass lists, ad and QUIC blocks,
                // torrent direct — the routing the config the profile came from carried,
                // the balancer's whole point in the panel's terms. Targets rewritten onto
                // this session's tags; the fall-through stays ours.
                panelRules(profileRules, balanced).forEach { add(it) }
                if (balanced) {
                    // The catch-all rides the balancer: whatever the bypass rules let
                    // through, the core picks the member for — at random, by ping or by
                    // load, as the panel asked.
                    rule {
                        put("network", "tcp,udp")
                        put("balancerTag", BALANCER_TAG)
                    }
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

    /**
     * The panel's routing rules rewritten onto this session's tags, BetterExclave's
     * rewriteProfileRouteTarget: direct-ish targets go to the session's freedom, block-ish
     * to its blackhole, and everything the panel aimed at a proxy or balancer tag funnels
     * to the one outbound that exists here — the balancer when the session runs one, the
     * proxy otherwise. A rule that carries none of these (a tag the panel invented) lands
     * on the same funnel rather than pointing at nothing.
     */
    private fun panelRules(profileRules: String, balanced: Boolean): List<JsonObject> {
        if (profileRules.isBlank()) return emptyList()
        val rules = try {
            (lenientJson.parseToJsonElement(profileRules) as? JsonArray)
                ?.filterIsInstance<JsonObject>()
        } catch (_: Exception) {
            null
        } ?: return emptyList()
        return rules.map { rule ->
            val target = rule.string("outboundTag") ?: rule.string("balancerTag") ?: ""
            val rewritten = rule.toMutableMap()
            rewritten.remove("outboundTag")
            rewritten.remove("balancerTag")
            when (target.trim().lowercase()) {
                "direct", "freedom", "bypass" -> rewritten["outboundTag"] = JsonPrimitive(DIRECT_TAG)
                "block", "blackhole" -> rewritten["outboundTag"] = JsonPrimitive(BLOCK_TAG)
                else -> if (balanced) {
                    rewritten["balancerTag"] = JsonPrimitive(BALANCER_TAG)
                } else {
                    rewritten["outboundTag"] = JsonPrimitive(PROXY_TAG)
                }
            }
            JsonObject(rewritten)
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull

    /**
     * The member tags: the first member is the plain proxy tag — the default outbound a
     * dead balancer falls back to — and the rest ride the proxy prefix, so the one
     * balancer selector covers them all.
     */
    private fun memberTag(index: Int): String = if (index == 0) PROXY_TAG else "$PROXY_TAG-${index + 1}"
}
