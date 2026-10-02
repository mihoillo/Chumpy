package slop.miho.aurora.xray

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.sub.LinkUtils

/**
 * Renders a [ProfileEntity] as one Xray outbound, in the current core's flat settings
 * dialect ({@code address}/{@code port}/… directly in settings — the form Xray-core's own
 * config loader accepts and v2rayNG emits). Modeled on v2rayNG's CoreOutboundBuilder.
 */
object OutboundBuilder {
    private const val TLS = "tls"
    private const val REALITY = "reality"

    /** The endpoint host the config's address field wants, IPv6 brackets included. */
    private fun host(profile: ProfileEntity): String = LinkUtils.ipv6Brackets(profile.server)

    fun build(profile: ProfileEntity, tag: String = "proxy"): JsonObject = buildJsonObject {
        put("tag", tag)
        put("protocol", protocolName(profile.type))
        put("settings", settings(profile))
        if (profile.type != ProfileType.WIREGUARD) {
            streamSettings(profile)?.let { put("streamSettings", it) }
        }
        put("mux", buildJsonObject { put("enabled", false) })
    }

    fun protocolName(type: ProfileType): String = when (type) {
        ProfileType.VMESS -> "vmess"
        ProfileType.VLESS -> "vless"
        ProfileType.TROJAN -> "trojan"
        ProfileType.SHADOWSOCKS -> "shadowsocks"
        ProfileType.SOCKS -> "socks"
        ProfileType.WIREGUARD -> "wireguard"
        ProfileType.HYSTERIA2 -> "hysteria"
    }

    private fun settings(profile: ProfileEntity): JsonObject = buildJsonObject {
        when (profile.type) {
            ProfileType.VMESS -> {
                put("address", host(profile))
                put("port", profile.serverPort)
                put("id", profile.password)
                put("security", profile.method.ifEmpty { "auto" })
                put("level", 8)
            }

            ProfileType.VLESS -> {
                put("address", host(profile))
                put("port", profile.serverPort)
                put("id", profile.password)
                put("encryption", profile.method.ifEmpty { "none" })
                if (profile.flow.isNotEmpty()) put("flow", profile.flow)
                put("level", 8)
            }

            ProfileType.TROJAN -> {
                put("address", host(profile))
                put("port", profile.serverPort)
                put("password", profile.password)
                if (profile.flow.isNotEmpty()) put("flow", profile.flow)
                put("level", 8)
            }

            ProfileType.SHADOWSOCKS -> {
                put("address", host(profile))
                put("port", profile.serverPort)
                put("method", profile.method)
                put("password", profile.password)
                put("level", 8)
            }

            ProfileType.SOCKS -> {
                put("address", host(profile))
                put("port", profile.serverPort)
                if (profile.username.isNotEmpty()) put("user", profile.username)
                if (profile.password.isNotEmpty()) put("pass", profile.password)
                put("level", 8)
            }

            ProfileType.HYSTERIA2 -> {
                put("version", 2)
                put("address", host(profile))
                put("port", profile.serverPort)
            }

            ProfileType.WIREGUARD -> wireguardSettings(profile)
        }
    }

    private fun wireguardSettings(profile: ProfileEntity): JsonObject = buildJsonObject {
        put("secretKey", profile.wgSecretKey)
        put(
            "address",
            JsonArray(
                profile.wgLocalAddress.split(',').map { it.trim() }
                    .filter { it.isNotEmpty() }.map(::JsonPrimitive),
            ),
        )
        put(
            "peers",
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("publicKey", profile.publicKey)
                        if (profile.wgPresharedKey.isNotEmpty()) put("preSharedKey", profile.wgPresharedKey)
                        put("endpoint", "${host(profile)}:${profile.serverPort}")
                        put("keepAlive", 0)
                    },
                )
            },
        )
        put("mtu", if (profile.wgMtu > 0) profile.wgMtu else 1420)
        reservedOf(profile)?.let { put("reserved", it) }
        put("remoteDNS", JsonArray(listOf(JsonPrimitive("1.1.1.1"), JsonPrimitive("8.8.8.8"))))
    }

    /** The WireGuard client's reserved bytes, "1,2,3"-style, as a JSON array. */
    private fun reservedOf(profile: ProfileEntity): JsonArray? {
        val bytes = profile.wgReserved.split(',').mapNotNull { it.trim().toIntOrNull() }
        return if (bytes.size == 3) JsonArray(bytes.map(::JsonPrimitive)) else null
    }

    // ------------------------------------------------------------------ stream

    /**
     * The streamSettings object: transport + security. Null only when there is nothing to
     * say (plain TCP without TLS), in which case the whole section is omitted. The
     * "security" field itself matters as much as the settings object under it: the core's
     * loader rejects a vless or trojan outbound whose security type reads empty (plain
     * text to a public address), no matter what tlsSettings/realitySettings sit next to it.
     */
    private fun streamSettings(profile: ProfileEntity): JsonObject? {
        var json = buildJsonObject { put("network", profile.network.ifEmpty { "tcp" }) }

        when (profile.security) {
            TLS -> json = json.with("security" to JsonPrimitive(TLS)).with("tlsSettings" to tlsSettings(profile))
            REALITY -> json = json.with("security" to JsonPrimitive(REALITY)).with("realitySettings" to realitySettings(profile))
        }

        transportSettings(profile)?.let { (name, settings) -> json = json.with(name to settings) }

        if (profile.network == "hysteria") {
            json = json.with(
                "hysteriaSettings" to buildJsonObject {
                    put("version", 2)
                    put("auth", profile.password)
                },
            )
            finalMask(profile)?.let { json = json.with("finalmask" to it) }
        }

        return if (json.size > 1) json else null
    }

    private fun tlsSettings(profile: ProfileEntity): JsonObject = buildJsonObject {
        if (profile.sni.isNotEmpty()) put("serverName", profile.sni)
        if (profile.allowInsecure) put("allowInsecure", true)
        if (profile.fingerprint.isNotEmpty()) put("fingerprint", profile.fingerprint)
        // Hysteria2 rides on HTTP/3; everything else keeps the link's own list.
        val alpn = profile.alpn.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val effective = if (alpn.isNotEmpty()) alpn else if (profile.network == "hysteria") listOf("h3") else emptyList()
        if (effective.isNotEmpty()) put("alpn", JsonArray(effective.map(::JsonPrimitive)))
    }

    private fun realitySettings(profile: ProfileEntity): JsonObject = buildJsonObject {
        if (profile.sni.isNotEmpty()) put("serverName", profile.sni)
        if (profile.fingerprint.isNotEmpty()) put("fingerprint", profile.fingerprint)
        put("publicKey", profile.publicKey)
        if (profile.shortId.isNotEmpty()) put("shortId", profile.shortId)
        if (profile.spiderX.isNotEmpty()) put("spiderX", profile.spiderX)
        put("show", false)
    }

    /** transport-name → settings-object, for the transports that need a settings object. */
    private fun transportSettings(profile: ProfileEntity): Pair<String, JsonObject>? = when (profile.network) {
        "tcp", "raw" -> {
            if (profile.headerType == "http") {
                "tcpSettings" to buildJsonObject {
                    put(
                        "header",
                        buildJsonObject {
                            put("type", "http")
                            put(
                                "request",
                                buildJsonObject {
                                    val hostHeader = profile.host.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                                    if (hostHeader.isNotEmpty()) {
                                        put("headers", buildJsonObject { put("Host", JsonArray(hostHeader.map(::JsonPrimitive))) })
                                    }
                                    put("path", JsonArray(listOf(JsonPrimitive(profile.path.ifEmpty { "/" }))))
                                },
                            )
                        },
                    )
                }
            } else {
                null
            }
        }

        "kcp", "mkcp" -> "kcpSettings" to buildJsonObject {
            put("header", buildJsonObject { put("type", profile.headerType.ifEmpty { "none" }) })
            if (profile.seed.isNotEmpty()) put("seed", profile.seed)
        }

        "ws", "websocket" -> "wsSettings" to buildJsonObject {
            put("path", profile.path)
            if (profile.host.isNotEmpty()) put("host", profile.host)
        }

        "httpupgrade" -> "httpupgradeSettings" to buildJsonObject {
            put("path", profile.path)
            if (profile.host.isNotEmpty()) put("host", profile.host)
        }

        "grpc" -> "grpcSettings" to buildJsonObject {
            put("serviceName", profile.serviceName)
            if (profile.authority.isNotEmpty()) put("authority", profile.authority)
            if (profile.mode == "multi") put("multiMode", true)
        }

        "xhttp", "splithttp" -> "xhttpSettings" to buildJsonObject {
            if (profile.host.isNotEmpty()) put("host", profile.host)
            put("path", profile.path)
            put("mode", profile.mode.ifEmpty { "auto" })
            rawJson(profile.xhttpExtra)?.let { put("extra", it) }
        }

        else -> null
    }

    /**
     * The Hysteria2 masking block: salamander obfuscation and UDP port hopping, the two
     * things hy2 links actually carry.
     */
    private fun finalMask(profile: ProfileEntity): JsonObject? {
        val udp = buildJsonArray {
            if (profile.obfsPassword.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", "salamander")
                        put("settings", buildJsonObject { put("password", profile.obfsPassword) })
                    },
                )
            }
            if (profile.portHopping.isNotEmpty()) {
                add(
                    buildJsonObject {
                        put("type", "udphop")
                        put(
                            "settings",
                            buildJsonObject {
                                put("mode", "intervalremote")
                                put("interval", profile.portHoppingInterval.ifEmpty { "30" })
                                put("remotePorts", profile.portHopping)
                            },
                        )
                    },
                )
            }
        }
        if (udp.isEmpty()) return null
        return buildJsonObject { put("udp", udp) }
    }

    /** Pass-through of a raw JSON blob (XHTTP extra); null when it is not valid JSON. */
    private fun rawJson(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) return null
        return try {
            Json.parseToJsonElement(raw)
        } catch (_: Exception) {
            null
        }
    }

    /** A copy of the object with extra keys: JsonObject is immutable, so additions rebuild it. */
    private fun JsonObject.with(vararg pairs: Pair<String, JsonElement>): JsonObject =
        JsonObject(toMap() + pairs)
}
