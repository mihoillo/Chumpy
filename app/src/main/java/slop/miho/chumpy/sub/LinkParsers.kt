package slop.miho.chumpy.sub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.ProfileType
import slop.miho.chumpy.sub.LinkUtils.decodeURIComponent
import slop.miho.chumpy.sub.LinkUtils.fixIllegalUrl
import slop.miho.chumpy.sub.LinkUtils.idnHost
import slop.miho.chumpy.sub.LinkUtils.ipv6Brackets
import slop.miho.chumpy.sub.LinkUtils.parsePort
import slop.miho.chumpy.sub.LinkUtils.queryParams
import java.net.URI

/**
 * Parsers for share links: vmess://, vless://, trojan://, ss://, socks://, hysteria2://,
 * wireguard:// and .conf file bodies. Ported from v2rayNG's fmt/ package (Apache-2.0).
 *
 * A parser returns a draft [ProfileEntity] with an empty id and no subscription; the
 * repository stamps identity and ownership on insert. Null means "not a link of mine".
 */
object LinkParsers {
    private const val TLS = "tls"
    private const val REALITY = "reality"
    private const val DEFAULT_VMESS_SECURITY = "auto"
    private const val DEFAULT_WG_ADDRESS = "172.16.0.2/32"
    private const val DEFAULT_WG_MTU = 1420

    /** Panels' JSON is not always quoted by the book; this one forgives. */
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Dispatches on the scheme; accepts trimmed lines with surrounding junk spaces. */
    fun parse(link: String): ProfileEntity? {
        val trimmed = link.trim()
        return when {
            trimmed.startsWith("vmess://", ignoreCase = true) -> parseVmess(trimmed)
            trimmed.startsWith("vless://", ignoreCase = true) -> parseVless(trimmed)
            trimmed.startsWith("trojan://", ignoreCase = true) -> parseTrojan(trimmed)
            trimmed.startsWith("ss://", ignoreCase = true) -> parseShadowsocks(trimmed)
            trimmed.startsWith("socks://", ignoreCase = true) ||
                trimmed.startsWith("socks5://", ignoreCase = true) -> parseSocks(trimmed)
            trimmed.startsWith("hy2://", ignoreCase = true) ||
                trimmed.startsWith("hysteria2://", ignoreCase = true) -> parseHysteria2(trimmed)
            trimmed.startsWith("wireguard://", ignoreCase = true) ||
                trimmed.startsWith("wg://", ignoreCase = true) -> parseWireguardUri(trimmed)
            else -> null
        }
    }

    /** A WireGuard .conf file body (the tunnel-migration import path). */
    fun parseWireguardConf(text: String): ProfileEntity? {
        val interfaceParams = mutableMapOf<String, String>()
        val peerParams = mutableMapOf<String, String>()
        var section: String? = null
        text.lines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            when {
                trimmed.startsWith("[Interface]", ignoreCase = true) -> section = "interface"
                trimmed.startsWith("[Peer]", ignoreCase = true) -> section = "peer"
                section != null -> {
                    val idx = trimmed.indexOf('=')
                    if (idx > 0) {
                        val params = if (section == "interface") interfaceParams else peerParams
                        params[trimmed.substring(0, idx).trim().lowercase()] = trimmed.substring(idx + 1).trim()
                    }
                }
            }
        }
        val endpoint = peerParams["endpoint"].orEmpty()
        if (endpoint.isEmpty() && interfaceParams["privatekey"].isNullOrEmpty()) return null
        val endpointHost: String
        val endpointPort: Int
        val colon = endpoint.lastIndexOf(':')
        if (colon > 0) {
            endpointHost = endpoint.substring(0, colon).removeSurrounding("[", "]")
            endpointPort = parsePort(endpoint.substring(colon + 1))
        } else {
            endpointHost = endpoint
            endpointPort = 0
        }
        return draft(ProfileType.WIREGUARD).copy(
            server = endpointHost,
            serverPort = endpointPort,
            remarks = "",
            wgSecretKey = interfaceParams["privatekey"].orEmpty(),
            wgLocalAddress = interfaceParams["address"] ?: DEFAULT_WG_ADDRESS,
            wgMtu = interfaceParams["mtu"]?.toIntOrNull() ?: DEFAULT_WG_MTU,
            publicKey = peerParams["publickey"].orEmpty(),
            wgPresharedKey = peerParams["presharedkey"].orEmpty(),
            wgReserved = peerParams["reserved"] ?: "0,0,0",
        )
    }

    private fun draft(type: ProfileType): ProfileEntity =
        ProfileEntity(id = "", subscriptionId = null, type = type)

    // ------------------------------------------------------------------ vmess

    private fun parseVmess(str: String): ProfileEntity? {
        // The modern "standard" vmess URI carries a query string; the classic QR-code form
        // is base64 of a JSON blob. kotlinx.serialization (not org.json) keeps this
        // JVM-testable; lenient because panels' JSON is not always quoted by the book.
        if (str.indexOf('?') > 0 && str.indexOf('&') > 0) return parseVmessUri(str)

        val json = LinkUtils.decode(str.removePrefix("vmess://"))
        if (json.isEmpty()) return null
        val qr = try {
            lenientJson.parseToJsonElement(json).jsonObject
        } catch (_: Exception) {
            return null
        }
        fun field(name: String): String = (qr[name] as? JsonPrimitive)?.content ?: ""
        val add = field("add")
        val id = field("id")
        val net = field("net")
        if (add.isEmpty() || id.isEmpty()) return null

        val profile = draft(ProfileType.VMESS).copy(
            remarks = field("ps"),
            server = add,
            serverPort = parsePort(field("port")),
            password = id,
            method = field("scy").ifEmpty { DEFAULT_VMESS_SECURITY },
            network = net.ifEmpty { "tcp" },
            headerType = field("type"),
            host = field("host"),
            path = field("path"),
            security = field("tls").let { if (it == TLS) it else "" },
            sni = field("sni"),
            alpn = field("alpn"),
            fingerprint = field("fp"),
            allowInsecure = field("insecure") == "1",
        )
        val withTransport = when (profile.network) {
            "kcp", "mkcp" -> profile.copy(seed = field("path"))
            "grpc" -> profile.copy(mode = field("type"), serviceName = field("path"), authority = field("host"))
            else -> profile
        }
        return withTransport
    }

    private fun parseVmessUri(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        if (uri.rawQuery.isNullOrEmpty()) return null
        val profile = draft(ProfileType.VMESS).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
            password = uri.userInfo.orEmpty(),
            method = DEFAULT_VMESS_SECURITY,
        )
        return applyQueryParams(profile, queryParams(uri))
    }

    // ------------------------------------------------------------------ vless

    private fun parseVless(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        if (uri.rawQuery.isNullOrEmpty()) return null
        val profile = draft(ProfileType.VLESS).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
            password = uri.userInfo.orEmpty(),
            method = queryParams(uri)["encryption"] ?: "none",
        )
        return applyQueryParams(profile, queryParams(uri))
    }

    // ------------------------------------------------------------------ trojan

    private fun parseTrojan(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        val profile = draft(ProfileType.TROJAN).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
            password = uri.userInfo.orEmpty(),
        )
        return if (uri.rawQuery.isNullOrEmpty()) {
            profile.copy(network = "tcp", security = TLS)
        } else {
            applyQueryParams(profile, queryParams(uri)).copy(security = queryParams(uri)["security"] ?: TLS)
        }
    }

    // ------------------------------------------------------------------ shadowsocks

    private fun parseShadowsocks(str: String): ProfileEntity? =
        parseShadowsocksSip002(str) ?: parseShadowsocksLegacy(str)

    private fun parseShadowsocksSip002(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        if (uri.idnHost.isEmpty() || uri.port <= 0) return null
        val userInfo = uri.userInfo ?: return null
        if (userInfo.isEmpty()) return null

        val parts = if (userInfo.contains(":")) {
            userInfo.split(":", limit = 2)
        } else {
            LinkUtils.decode(userInfo).split(":", limit = 2)
        }
        var profile = draft(ProfileType.SHADOWSOCKS).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
        )
        if (parts.size == 2) {
            profile = profile.copy(method = parts.first(), password = parts.last())
        }
        // The simple-obfs plugin rides along as a plain HTTP header fake.
        val plugin = queryParams(uri)["plugin"].orEmpty()
        if (plugin.contains("obfs=http")) {
            val pairs = plugin.split(";").mapNotNull { pair ->
                val idx = pair.indexOf('=')
                if (idx > 0) pair.substring(0, idx) to pair.substring(idx + 1) else null
            }.toMap()
            profile = profile.copy(network = "tcp", headerType = "http", host = pairs["obfs-host"].orEmpty())
        }
        return profile
    }

    private fun parseShadowsocksLegacy(str: String): ProfileEntity? {
        var body = str.removePrefix("ss://")
        val hashIdx = body.indexOf('#')
        var remarks = ""
        if (hashIdx > 0) {
            remarks = decodeURIComponent(body.substring(hashIdx + 1))
            body = body.substring(0, hashIdx)
        }
        // Either the whole body is base64, or only the method:password half before "@".
        val atIdx = body.indexOf('@')
        body = if (atIdx > 0) {
            LinkUtils.decode(body.substring(0, atIdx)) + body.substring(atIdx)
        } else {
            LinkUtils.decode(body)
        }
        val match = "^(.+?):(.*)@(.+?):(\\d+)/?$".toRegex().matchEntire(body) ?: return null
        return draft(ProfileType.SHADOWSOCKS).copy(
            remarks = remarks,
            server = match.groupValues[3].removeSurrounding("[", "]"),
            serverPort = parsePort(match.groupValues[4]),
            password = match.groupValues[2],
            method = match.groupValues[1].lowercase(),
        )
    }

    // ------------------------------------------------------------------ socks

    private fun parseSocks(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        if (uri.idnHost.isEmpty() || uri.port <= 0) return null
        val profile = draft(ProfileType.SOCKS).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
        )
        val userInfo = uri.userInfo.orEmpty()
        return if (userInfo.contains(":")) {
            profile.copy(username = userInfo.substringBefore(":"), password = userInfo.substringAfter(":"))
        } else {
            profile
        }
    }

    // ------------------------------------------------------------------ hysteria2

    private fun parseHysteria2(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        val profile = draft(ProfileType.HYSTERIA2).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
            password = uri.userInfo.orEmpty(),
            security = TLS,
            network = "hysteria",
        )
        if (uri.rawQuery.isNullOrEmpty()) return profile
        val query = queryParams(uri)
        return applyQueryParams(profile, query).copy(
            security = query["security"] ?: TLS,
            obfsPassword = query["obfs-password"].orEmpty(),
            portHopping = query["mport"].orEmpty(),
            portHoppingInterval = query["mportinterval"].orEmpty(),
        )
    }

    // ------------------------------------------------------------------ wireguard

    private fun parseWireguardUri(str: String): ProfileEntity? {
        val uri = parseUri(str) ?: return null
        if (uri.rawQuery.isNullOrEmpty()) return null
        val query = queryParams(uri)
        return draft(ProfileType.WIREGUARD).copy(
            remarks = fragmentOf(uri),
            server = uri.idnHost,
            serverPort = uri.port,
            wgSecretKey = uri.userInfo.orEmpty(),
            wgLocalAddress = query["address"] ?: DEFAULT_WG_ADDRESS,
            publicKey = query["publickey"].orEmpty(),
            wgPresharedKey = query["presharedkey"].orEmpty(),
            wgMtu = query["mtu"]?.toIntOrNull() ?: DEFAULT_WG_MTU,
            wgReserved = query["reserved"] ?: "0,0,0",
        )
    }

    // ------------------------------------------------------------------ shared

    /** Fills the transport/security fields shared by every scheme-with-query format. */
    private fun applyQueryParams(profile: ProfileEntity, query: Map<String, String>): ProfileEntity {
        var result = profile.copy(
            network = query["type"] ?: "tcp",
            headerType = query["headerType"].orEmpty(),
            host = query["host"].orEmpty(),
            path = query["path"].orEmpty(),
            seed = query["seed"].orEmpty(),
            mode = query["mode"].orEmpty(),
            serviceName = query["serviceName"].orEmpty(),
            authority = query["authority"].orEmpty(),
            xhttpExtra = query["extra"],
        )
        val security = query["security"]
        result = result.copy(security = if (security == TLS || security == REALITY) security else "")
        val insecureKeys = listOf("insecure", "allowInsecure", "allow_insecure")
        result = result.copy(
            allowInsecure = insecureKeys.any { query[it] == "1" },
            sni = query["sni"].orEmpty(),
            fingerprint = query["fp"].orEmpty(),
            alpn = query["alpn"].orEmpty(),
            publicKey = query["pbk"].orEmpty(),
            shortId = query["sid"].orEmpty(),
            spiderX = query["spx"].orEmpty(),
            flow = query["flow"].orEmpty(),
        )
        return result
    }

    private fun fragmentOf(uri: URI): String = decodeURIComponent(uri.fragment.orEmpty())

    private fun parseUri(str: String): URI? = try {
        URI(fixIllegalUrl(str))
    } catch (_: Exception) {
        null
    }

    /** The endpoint in host:port form, IPv6 bracketed — the shape configs want. */
    fun endpointOf(profile: ProfileEntity): String =
        "${ipv6Brackets(profile.server)}:${profile.serverPort}"
}
