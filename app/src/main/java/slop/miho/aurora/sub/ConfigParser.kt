package slop.miho.aurora.sub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.db.computeLinkKey

/**
 * The raw-config subscription dialect: instead of share links, the panel answers with
 * whole Xray client configs — one object, or an array of them — each carrying inbounds,
 * routing and its own outbounds. The profiles live in the outbounds: every proxy outbound
 * is one server, everything else (freedom, blackhole, dns, selectors) is plumbing.
 *
 * An array usually holds one config per server — named by the config's "remarks" — plus
 * a few aggregator configs whose outbounds repeat the same servers under "auto-"/"bypass-"
 * group labels. Place in the array decides place in the list; the name, though, always
 * comes from the server's own config when it has one, so no server lands under a group
 * label or twice under two names.
 *
 * Both settings dialects are read: the classic nested one (vnext/servers with a users
 * list inside) and the core's own flat one ([slop.miho.aurora.xray.OutboundBuilder]'s),
 * so the app round-trips its own exports too. Null means "not this dialect".
 */
object ConfigParser {
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TLS = "tls"
    private const val REALITY = "reality"

    /** The protocols that mark a real proxy outbound; everything else is plumbing. */
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks")

    /** The strategies this core runs, normalized to the names it switches on. */
    private val STRATEGIES = setOf("random", "roundrobin", "leastping", "leastload")
    private const val STRATEGY_RANDOM = "random"

    /**
     * One config's reading: the outbounds parsed as (tag, profile) pairs — the tags are
     * what a routing balancer's selectors match against — plus the balancers its routing
     * carries, kept as raw objects until they are turned into drafts.
     */
    private class Bundle(
        val prefix: String,
        val entries: List<Pair<String, ProfileEntity>>,
        val balancerSpecs: List<JsonObject>,
    )

    /** The profiles found in a raw-config body, deduplicated; null when it is not one. */
    fun parse(body: String): List<ProfileEntity>? {
        val trimmed = body.trim()
        if (!trimmed.startsWith("[") && !trimmed.startsWith("{")) return null
        val root = try {
            lenientJson.parseToJsonElement(trimmed)
        } catch (_: Exception) {
            return null
        }
        val configs = when (root) {
            is JsonArray -> root.filterIsInstance<JsonObject>().ifEmpty { return null }
            is JsonObject -> listOf(root)
            else -> return null
        }
        if (configs.none { it.array("outbounds") != null }) return null

        // One config's reading, tag and profile together.
        val bundles = configs.map { config ->
            val proxies = proxyOutbounds(config)
            Bundle(
                prefix = config.str("remarks")?.trim()?.ifBlank { null }.orEmpty(),
                entries = proxies.mapNotNull { outbound ->
                    outboundToProfile(outbound)?.let { outbound.str("tag").orEmpty() to it }
                },
                balancerSpecs = config.obj("routing")?.array("balancers")?.arrayOfObjects().orEmpty(),
            )
        }

        // The list runs in the panel's own order — the configs as they come, one entry
        // each: a single config hands its named server over right where it stands, an
        // aggregator's balancers stand where the config stands with its members riding
        // behind them. A server the panel names twice — once alone, once inside an
        // aggregator — keeps its first place; the name, though, always comes from the
        // server's own config: the aggregator's copy carries the group's tag ("auto-…",
        // "proxy-2"), a label about the group, not the server.
        val soloNames = HashMap<String, String>()
        for (bundle in bundles) {
            if (bundle.entries.size == 1) {
                val (tag, profile) = bundle.entries.first()
                soloNames.putIfAbsent(profile.computeLinkKey(), bundle.prefix.ifEmpty { tag })
            }
        }
        val seen = HashSet<String>()
        val seenBalancers = HashSet<String>()
        val profiles = ArrayList<ProfileEntity>()
        for (bundle in bundles) {
            if (bundle.entries.size == 1) {
                val (tag, profile) = bundle.entries.first()
                val key = profile.computeLinkKey()
                if (seen.add(key)) {
                    val named = profile.copy(remarks = soloNames[key] ?: bundle.prefix.ifEmpty { tag })
                    profiles.add(named)
                }
            } else if (bundle.entries.size > 1) {
                for (balancer in balancersOf(bundle)) {
                    if (seenBalancers.add(balancer.computeLinkKey())) profiles.add(balancer)
                }
                for ((_, profile) in bundle.entries) {
                    val key = profile.computeLinkKey()
                    if (seen.add(key)) {
                        profiles.add(profile.copy(remarks = soloNames[key] ?: profile.remarks))
                    }
                }
            }
        }
        return if (profiles.isEmpty()) null else profiles
    }

    /**
     * The balancers one config's routing carries, as profile drafts: a balancer is not
     * a server but a routing pick over the config's own outbounds — the core chooses
     * among them by strategy at connect time. Members are matched the way Xray matches
     * them (selector prefixes against outbound tags) and carried as linkKeys, so the
     * rows they resolve to survive subscription refreshes. A balancer under two members
     * is not worth an entry and is dropped — unless its fallbackTag can carry it, a
     * degraded balancer still beating none, BetterExclave's own call.
     */
    private fun balancersOf(bundle: Bundle): List<ProfileEntity> {
        if (bundle.entries.isEmpty() || bundle.balancerSpecs.isEmpty()) return emptyList()
        val multiple = bundle.balancerSpecs.size >= 2
        return bundle.balancerSpecs.mapNotNull { spec ->
            val selector = spec.array("selector")?.strings().orEmpty()
            val fallbackTag = spec.str("fallbackTag")?.trim().orEmpty()
            if (selector.isEmpty() && fallbackTag.isEmpty()) return@mapNotNull null
            val members = ArrayList<Pair<String, ProfileEntity>>()
            for ((tag, profile) in bundle.entries) {
                if (selector.any { it.isNotEmpty() && tag.startsWith(it) } &&
                    members.none { it.second === profile }
                ) {
                    members.add(tag to profile)
                }
            }
            // The fallback is not a candidate the core ever picks — it is where a
            // dead balancer lands — so it joins the members only to keep a lone one
            // alive.
            if (members.size < 2 && fallbackTag.isNotEmpty()) {
                for ((tag, profile) in bundle.entries) {
                    if (tag == fallbackTag && members.none { it.second === profile }) {
                        members.add(tag to profile)
                    }
                }
            }
            if (members.size < 2) return@mapNotNull null

            val tagName = spec.str("tag")?.trim().orEmpty()
            val shortTag = tagName
                .removeSuffix("_Balancer")
                .removeSuffix("Balancer")
                .takeIf { it.isNotEmpty() } ?: tagName.ifEmpty { "Balancer" }
            val name = when {
                bundle.prefix.isNotEmpty() && multiple -> "${bundle.prefix} ($shortTag)"
                bundle.prefix.isNotEmpty() -> bundle.prefix
                else -> shortTag
            }
            ProfileEntity(id = "", subscriptionId = null, type = ProfileType.BALANCER).copy(
                remarks = name,
                balancerStrategy = normalizeStrategy(spec.obj("strategy")?.str("type")),
                balancerMembers = members.joinToString("\n") { it.second.computeLinkKey() },
            )
        }
    }

    /**
     * The strategies this core runs, normalized to the names it switches on; anything
     * a panel spells some other way falls back to random, which always works.
     */
    private fun normalizeStrategy(type: String?): String {
        val normalized = type?.trim()?.lowercase().orEmpty()
        return if (normalized in STRATEGIES) normalized else STRATEGY_RANDOM
    }

    private fun proxyOutbounds(config: JsonObject): List<JsonObject> =
        config.array("outbounds")?.arrayOfObjects().orEmpty().filter { it.str("protocol") in PROXY_PROTOCOLS }

    /** One outbound as a profile draft; null when its endpoint does not hold together. */
    private fun outboundToProfile(outbound: JsonObject): ProfileEntity? {
        val type = when (outbound.str("protocol")) {
            "vless" -> ProfileType.VLESS
            "vmess" -> ProfileType.VMESS
            "trojan" -> ProfileType.TROJAN
            "shadowsocks" -> ProfileType.SHADOWSOCKS
            "socks" -> ProfileType.SOCKS
            else -> return null
        }
        val settings = outbound.obj("settings") ?: JsonObject(emptyMap())
        // The nested dialect nests the endpoint in vnext/servers with a users list
        // inside; the flat one (our own) keeps address/port/id directly in settings.
        val endpoint = settings.array("vnext")?.arrayOfObjects()?.firstOrNull()
            ?: settings.array("servers")?.arrayOfObjects()?.firstOrNull()
        val user = endpoint?.array("users")?.arrayOfObjects()?.firstOrNull()

        val server = endpoint?.str("address") ?: settings.str("address") ?: return null
        val port = endpoint?.int("port") ?: settings.int("port") ?: return null
        if (server.isBlank() || port <= 0) return null

        var profile = ProfileEntity(id = "", subscriptionId = null, type = type).copy(
            remarks = outbound.str("tag").orEmpty(),
            server = server,
            serverPort = port,
        )
        when (type) {
            ProfileType.VLESS -> profile = profile.copy(
                password = user?.str("id") ?: settings.str("id") ?: return null,
                method = user?.str("encryption") ?: settings.str("encryption") ?: "none",
                flow = user?.str("flow") ?: settings.str("flow").orEmpty(),
            )

            ProfileType.VMESS -> profile = profile.copy(
                password = user?.str("id") ?: settings.str("id") ?: return null,
                method = user?.str("security") ?: settings.str("security") ?: "auto",
                flow = user?.str("flow") ?: settings.str("flow").orEmpty(),
            )

            ProfileType.TROJAN -> profile = profile.copy(
                password = endpoint?.str("password") ?: settings.str("password") ?: return null,
                flow = endpoint?.str("flow") ?: settings.str("flow").orEmpty(),
            )

            ProfileType.SHADOWSOCKS -> profile = profile.copy(
                method = endpoint?.str("method") ?: settings.str("method").orEmpty(),
                password = endpoint?.str("password") ?: settings.str("password").orEmpty(),
            )

            ProfileType.SOCKS -> profile = profile.copy(
                username = user?.str("user") ?: settings.str("user").orEmpty(),
                password = user?.str("pass") ?: settings.str("pass").orEmpty(),
            )

            else -> return null
        }
        return withStream(profile, outbound.obj("streamSettings"))
    }

    /**
     * The streamSettings object folded into the transport and security fields links
     * carry — the reading mirror of OutboundBuilder's writing.
     */
    private fun withStream(profile: ProfileEntity, stream: JsonObject?): ProfileEntity {
        if (stream == null) return profile
        var result = profile.copy(
            network = stream.str("network")?.takeIf { it.isNotBlank() } ?: "tcp",
        )

        // Security: the settings object that rides under it tells the details.
        val security = when (val declared = stream.str("security")) {
            TLS, REALITY -> declared
            else -> ""
        }
        result = result.copy(security = security)
        val secure = if (security == REALITY) stream.obj("realitySettings") else stream.obj("tlsSettings")
        if (secure != null) {
            result = result.copy(
                sni = secure.str("serverName").orEmpty(),
                fingerprint = secure.str("fingerprint").orEmpty(),
                alpn = secure.array("alpn")?.strings()?.joinToString(",").orEmpty(),
                allowInsecure = security == TLS && secure.str("allowInsecure") == "true",
            )
            if (security == REALITY) {
                result = result.copy(
                    publicKey = secure.str("publicKey").orEmpty(),
                    shortId = secure.str("shortId").orEmpty(),
                    spiderX = secure.str("spiderX").orEmpty(),
                )
            }
        }

        when (result.network) {
            "tcp", "raw" -> stream.obj("tcpSettings")?.obj("header")?.let { header ->
                if (header.str("type") == "http") {
                    val request = header.obj("request")
                    result = result.copy(
                        headerType = "http",
                        host = request?.obj("headers")?.array("Host")?.strings()?.joinToString(",").orEmpty(),
                        path = request?.array("path")?.strings()?.firstOrNull() ?: "",
                    )
                }
            }

            "kcp", "mkcp" -> stream.obj("kcpSettings")?.let { kcp ->
                result = result.copy(
                    headerType = kcp.obj("header")?.str("type").orEmpty(),
                    seed = kcp.str("seed").orEmpty(),
                )
            }

            "ws", "websocket" -> stream.obj("wsSettings")?.let { ws ->
                result = result.copy(path = ws.str("path").orEmpty(), host = ws.str("host").orEmpty())
            }

            "httpupgrade" -> stream.obj("httpupgradeSettings")?.let { up ->
                result = result.copy(path = up.str("path").orEmpty(), host = up.str("host").orEmpty())
            }

            "grpc" -> stream.obj("grpcSettings")?.let { grpc ->
                result = result.copy(
                    serviceName = grpc.str("serviceName").orEmpty(),
                    authority = grpc.str("authority").orEmpty(),
                    mode = if (grpc.str("multiMode") == "true") "multi" else "",
                )
            }

            "xhttp", "splithttp" -> stream.obj("xhttpSettings")?.let { xhttp ->
                result = result.copy(
                    host = xhttp.str("host").orEmpty(),
                    path = xhttp.str("path").orEmpty(),
                    mode = xhttp.str("mode").orEmpty(),
                    xhttpExtra = xhttp.obj("extra")?.toString(),
                )
            }
        }
        return result
    }

    // ------------------------------------------------------------------ JSON plumbing

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.arrayOfObjects(): List<JsonObject> = filterIsInstance<JsonObject>()

    private fun JsonArray.strings(): List<String> =
        filterIsInstance<JsonPrimitive>().mapNotNull { it.contentOrNull }
}
