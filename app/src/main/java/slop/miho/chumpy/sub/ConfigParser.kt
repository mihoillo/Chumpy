package slop.miho.chumpy.sub

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import slop.miho.chumpy.db.ProfileEntity
import slop.miho.chumpy.db.ProfileType
import slop.miho.chumpy.db.computeLinkKey

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
 * list inside) and the core's own flat one ([slop.miho.chumpy.xray.OutboundBuilder]'s),
 * so the app round-trips its own exports too. Null means "not this dialect".
 */
object ConfigParser {
    private val lenientJson = Json { ignoreUnknownKeys = true; isLenient = true }

    private const val TLS = "tls"
    private const val REALITY = "reality"

    /** The protocols that mark a real proxy outbound; everything else is plumbing. */
    private val PROXY_PROTOCOLS = setOf("vless", "vmess", "trojan", "shadowsocks", "socks", "hysteria")

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
        val routingRules: String,
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
                routingRules = routingRulesOf(config),
            )
        }

        // The list runs in the panel's own order — the configs as they come, a group row
        // standing where its config stands, its internals riding behind it hidden.
        //
        // The panel's own semantics, BetterExclave's: an array element IS one profile —
        // named by its "remarks" — and when that element's routing carries a surviving
        // balancer, its outbounds are the balancer's internals, not servers to show:
        // they are stored hidden (the balancer's members resolve by linkKey, and a solo
        // config naming the same server un-hides its row) and only the group row stands.
        // A server filed away hidden that a later config presents on its own is un-hidden
        // in place, named by that config — the group's tag is a label about the group,
        // not the server.
        val seenBalancers = HashSet<String>()
        val emitted = HashMap<String, Int>()
        val profiles = ArrayList<ProfileEntity>()
        for (bundle in bundles) {
            val balancers = balancersOf(bundle)
            for (balancer in balancers) {
                if (seenBalancers.add(balancer.computeLinkKey())) {
                    profiles.add(balancer.copy(routingRules = bundle.routingRules))
                }
            }
            val internal = balancers.isNotEmpty()
            // BetterExclave's naming: one proxy in the config takes the remarks alone —
            // six configs would otherwise show as six "proxy" rows — more ride the
            // remarks and the tag together.
            val singleProxy = bundle.entries.size == 1
            for ((tag, profile) in bundle.entries) {
                val key = profile.computeLinkKey()
                val index = emitted[key]
                if (index == null) {
                    val name = when {
                        singleProxy -> bundle.prefix.ifEmpty { tag }
                        bundle.prefix.isNotEmpty() && tag.isNotEmpty() -> "${bundle.prefix} $tag"
                        else -> tag
                    }
                    emitted[key] = profiles.size
                    profiles.add(
                        profile.copy(
                            remarks = name,
                            routingRules = bundle.routingRules,
                            hidden = internal,
                        )
                    )
                } else if (!internal) {
                    val filed = profiles[index]
                    if (filed.hidden) {
                        profiles[index] = filed.copy(
                            hidden = false,
                            remarks = bundle.prefix.ifEmpty { tag },
                            routingRules = bundle.routingRules,
                        )
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
            "hysteria" -> ProfileType.HYSTERIA2
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
        if (server.isBlank() || port !in 1..65535) return null

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

            ProfileType.HYSTERIA2 -> {
                val hysteria = outbound.obj("streamSettings")?.obj("hysteriaSettings") ?: return null
                if (settings.int("version") != 2 || hysteria.int("version") != 2) return null
                var obfsPassword = ""
                var portHopping = ""
                var portHoppingInterval = ""
                outbound.obj("streamSettings")?.obj("finalmask")?.array("udp")?.arrayOfObjects()?.forEach { mask ->
                    when (mask.str("type")) {
                        "salamander" -> obfsPassword = mask.obj("settings")?.str("password").orEmpty()
                        "udphop" -> {
                            portHopping = mask.obj("settings")?.str("remotePorts").orEmpty()
                            portHoppingInterval = mask.obj("settings")?.str("interval").orEmpty()
                        }
                    }
                }
                profile = profile.copy(
                    password = hysteria.str("auth") ?: return null,
                    obfsPassword = obfsPassword,
                    portHopping = portHopping,
                    portHoppingInterval = portHoppingInterval,
                )
            }

            else -> return null
        }
        val streamed = withStream(profile, outbound.obj("streamSettings"))
        return if (type == ProfileType.HYSTERIA2) streamed.copy(network = "hysteria") else streamed
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
                result = result.copy(
                    path = ws.str("path").orEmpty(),
                    host = ws.str("host") ?: ws.obj("headers")?.str("Host")
                        ?: ws.obj("headers")?.str("host").orEmpty(),
                )
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

    /**
     * The routing rules worth carrying, as a raw JSON array: the panel's own intent for
     * its servers — bypass lists, ad and QUIC blocks, torrent direct — rewritten onto
     * the session's tags at build time. BetterExclave's profileRoutingRules, the same
     * filtering: a rule must have a target (an outbound or balancer tag) and a matcher
     * (a bare target with no matcher would swallow everything), and the network matcher
     * only counts when it names one protocol — "tcp,udp" is the panel's catch-all, and
     * the fall-through is ours to aim anyway. The "outbound" alias some panels spell
     * becomes "outboundTag" so the build-side rewrite sees one key.
     */
    private fun routingRulesOf(config: JsonObject): String {
        val raw = config.obj("routing")?.array("rules")?.arrayOfObjects().orEmpty()
        val carried = raw.mapNotNull { rule ->
            val type = rule.str("type")?.trim()?.lowercase().orEmpty()
            if (type.isNotEmpty() && type != "field") return@mapNotNull null
            // The "outbound" alias some panels spell folds into "outboundTag" so the
            // build-side rewrite has one key to read.
            val outboundTarget = rule.str("outboundTag")?.trim()?.ifBlank { null }
                ?: rule.str("outbound")?.trim()?.ifBlank { null }
            val balancerTarget = rule.str("balancerTag")?.trim()?.ifBlank { null }
            if (outboundTarget == null && balancerTarget == null) return@mapNotNull null

            val matchers = HashMap<String, JsonElement>()
            matchers["type"] = JsonPrimitive("field")
            fun strings(vararg keys: String, into: String) {
                for (key in keys) {
                    val values = when (val element = rule[key]) {
                        is JsonArray -> element.filterIsInstance<JsonPrimitive>()
                            .mapNotNull { it.contentOrNull?.trim()?.ifBlank { null } }
                        is JsonPrimitive -> listOfNotNull(
                            element.contentOrNull?.trim()?.ifBlank { null }
                        )
                        else -> emptyList()
                    }
                    if (values.isNotEmpty()) {
                        matchers[into] = JsonArray(values.map { JsonPrimitive(it) })
                        return
                    }
                }
            }
            strings("domain", "domains", into = "domain")
            strings("ip", "ips", into = "ip")
            strings("source", into = "source")
            strings("protocol", into = "protocol")
            fun scalar(key: String, into: String, transform: (String) -> String? = { it }) {
                rule.str(key)?.trim()?.let { transform(it) }?.ifBlank { null }?.let { value ->
                    matchers[into] = JsonPrimitive(value)
                }
            }
            scalar("port", "port")
            scalar("sourcePort", "sourcePort")
            scalar("attrs", "attrs")
            // One protocol only; "tcp,udp" is a catch-all, not a matcher.
            scalar("network", "network") {
                when (it.lowercase()) {
                    "tcp" -> "tcp"
                    "udp" -> "udp"
                    else -> null
                }
            }
            if (matchers.size == 1) return@mapNotNull null

            if (outboundTarget != null) matchers["outboundTag"] = JsonPrimitive(outboundTarget)
            else matchers["balancerTag"] = JsonPrimitive(balancerTarget!!)
            JsonObject(matchers)
        }
        if (carried.isEmpty()) return ""
        return JsonArray(carried).toString()
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.int(key: String): Int? = str(key)?.toIntOrNull()

    private fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

    private fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

    private fun JsonArray.arrayOfObjects(): List<JsonObject> = filterIsInstance<JsonObject>()

    private fun JsonArray.strings(): List<String> =
        filterIsInstance<JsonPrimitive>().mapNotNull { it.contentOrNull }
}
