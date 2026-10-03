package slop.miho.aurora.sub

import slop.miho.aurora.db.ProfileEntity
import slop.miho.aurora.db.ProfileType
import slop.miho.aurora.db.computeLinkKey

/**
 * The Clash YAML subscription dialect: a `proxies:` list of proxy maps, the format panels
 * serve to Clash-flavoured clients. Only the proxy entries matter — groups and rules are
 * the panel's own plumbing, and Aurora routes everything itself.
 *
 * The YAML is read by a deliberately small block parser (mappings, sequences, one-level
 * nesting, quoted scalars, flow lists, comments) — the subset clash proxy lists actually
 * use. Null means "not this dialect".
 */
object ClashYamlParser {
    private val PROXIES_KEY = Regex("(?m)^proxies:")

    /** The profiles found in the `proxies:` list, deduplicated; null when it is not one. */
    fun parse(body: String): List<ProfileEntity>? {
        if (!PROXIES_KEY.containsMatchIn(body)) return null
        val root = MiniYaml.parse(body) as? Map<*, *> ?: return null
        @Suppress("UNCHECKED_CAST")
        val proxies = root["proxies"] as? List<Any?> ?: return null
        val profiles = proxies.mapNotNull { entry ->
            (entry as? Map<*, *>)?.let(::proxyToProfile)
        }
        return if (profiles.isEmpty()) null else profiles.distinctBy { it.computeLinkKey() }
    }

    /** One clash proxy map as a profile draft; null when its endpoint does not hold together. */
    private fun proxyToProfile(proxy: Map<*, *>): ProfileEntity? {
        val type = when (proxy["type"]) {
            "vless" -> ProfileType.VLESS
            "vmess" -> ProfileType.VMESS
            "trojan" -> ProfileType.TROJAN
            "ss", "shadowsocks" -> ProfileType.SHADOWSOCKS
            "socks5", "socks" -> ProfileType.SOCKS
            else -> return null
        }
        val server = proxy["server"] as? String ?: return null
        val port = (proxy["port"] as? String)?.toIntOrNull() ?: return null
        if (server.isBlank() || port <= 0) return null

        var profile = ProfileEntity(id = "", subscriptionId = null, type = type).copy(
            remarks = (proxy["name"] as? String).orEmpty(),
            server = server,
            serverPort = port,
        )
        when (type) {
            ProfileType.VLESS -> profile = profile.copy(
                password = proxy["uuid"] as? String ?: return null,
                method = "none",
                flow = (proxy["flow"] as? String).orEmpty(),
            )

            ProfileType.VMESS -> profile = profile.copy(
                password = proxy["uuid"] as? String ?: return null,
                method = (proxy["cipher"] as? String) ?: "auto",
            )

            ProfileType.TROJAN -> profile = profile.copy(
                password = proxy["password"] as? String ?: return null,
            )

            ProfileType.SHADOWSOCKS -> profile = profile.copy(
                method = (proxy["cipher"] as? String).orEmpty(),
                password = (proxy["password"] as? String).orEmpty(),
            )

            ProfileType.SOCKS -> profile = profile.copy(
                username = (proxy["username"] as? String).orEmpty(),
                password = (proxy["password"] as? String).orEmpty(),
            )

            else -> return null
        }

        val reality = proxy["reality-opts"] as? Map<*, *>
        var result = profile.copy(
            network = when (val net = proxy["network"] as? String) {
                "ws", "websocket", "grpc", "httpupgrade", "kcp", "mkcp" -> net
                "h2", "http" -> "http"
                else -> "tcp"
            },
            security = when {
                reality != null -> "reality"
                proxy["tls"] == "true" -> "tls"
                else -> ""
            },
            sni = (proxy["servername"] as? String) ?: (proxy["sni"] as? String) ?: "",
            fingerprint = (proxy["client-fingerprint"] as? String).orEmpty(),
            alpn = (proxy["alpn"] as? List<*>)?.joinToString(",") { it.toString() } ?: "",
            allowInsecure = proxy["skip-cert-verify"] == "true",
            publicKey = (reality?.get("public-key") as? String).orEmpty(),
            shortId = (reality?.get("short-id") as? String).orEmpty(),
        )

        when (result.network) {
            "ws", "websocket" -> (proxy["ws-opts"] as? Map<*, *>)?.let { ws ->
                result = result.copy(
                    path = (ws["path"] as? String).orEmpty(),
                    host = hostOf(ws["headers"]),
                )
            }

            "grpc" -> (proxy["grpc-opts"] as? Map<*, *>)?.let { grpc ->
                result = result.copy(serviceName = (grpc["grpc-service-name"] as? String).orEmpty())
            }
        }
        return result
    }

    /** The Host header, which clash writes as a scalar, a list or a one-key map. */
    private fun hostOf(headers: Any?): String = when (headers) {
        null -> ""
        is Map<*, *> -> (headers["Host"] ?: headers["host"])?.let(::hostOf) ?: ""
        is List<*> -> headers.joinToString(",") { it.toString() }
        else -> headers.toString()
    }
}

/**
 * The block-YAML subset clash proxy lists use: mappings and sequences nested by
 * indentation, quoted and plain scalars, flow lists, comments. A `- key: value` item
 * opens a map whose first line is the content at the column it starts on.
 */
private object MiniYaml {
    private class Line(val indent: Int, val text: String)

    fun parse(body: String): Any? {
        val lines = body.lines().mapNotNull(::strip).filterNot { it.text == "---" || it.text == "..." }
        if (lines.isEmpty()) return null
        return block(Cursor(lines, 0), lines.first().indent)
    }

    /** Blank and comment-only lines vanish; the rest keep their indent. */
    private fun strip(raw: String): Line? {
        val indent = raw.indexOfFirst { !it.isWhitespace() }
        if (indent < 0) return null
        val text = stripComment(raw.substring(indent).trimEnd())
        if (text.isBlank()) return null
        return Line(indent, text)
    }

    /** A `#` outside quotes starts a comment — but only at line start or after a space. */
    private fun stripComment(text: String): String {
        var single = false
        var double = false
        text.forEachIndexed { i, ch ->
            when {
                ch == '\'' && !double -> single = !single
                ch == '"' && !single -> double = !double
                ch == '#' && !single && !double && (i == 0 || text[i - 1] == ' ') ->
                    return text.substring(0, i).trimEnd()
            }
        }
        return text
    }

    private class Cursor(val lines: List<Line>, var index: Int) {
        val line: Line get() = lines[index]
        fun peek(): Line? = lines.getOrNull(index)
    }

    private fun isSeqItem(text: String): Boolean = text == "-" || text.startsWith("- ")

    private fun block(cursor: Cursor, indent: Int): Any? =
        if (isSeqItem(cursor.line.text)) sequence(cursor, indent) else mapping(cursor, indent)

    private fun sequence(cursor: Cursor, indent: Int): List<Any?> {
        val items = mutableListOf<Any?>()
        while (cursor.peek()?.indent == indent && isSeqItem(cursor.line.text)) {
            val line = cursor.line
            if (line.text == "-") {
                cursor.index++
                items += cursor.peek()?.takeIf { it.indent > indent }?.let { block(cursor, it.indent) }
            } else {
                val content = line.text.removePrefix("- ")
                if (keyValue(content) != null) {
                    // The map opens mid-line, at the column the content starts on; the
                    // whole item line is spent as that opening.
                    cursor.index++
                    val virtual = Line(line.indent + line.text.length - content.length, content)
                    items += mapping(cursor, virtual.indent, virtual)
                } else {
                    cursor.index++
                    items += scalar(content)
                }
            }
        }
        return items
    }

    /** A block mapping at [indent]; [first] is a virtual opening line from a sequence item. */
    private fun mapping(cursor: Cursor, indent: Int, first: Line? = null): LinkedHashMap<String, Any?> {
        val map = LinkedHashMap<String, Any?>()
        var current = first
        while (true) {
            val line = current ?: cursor.peek()?.takeIf { it.indent == indent } ?: break
            if (current == null && isSeqItem(line.text)) break
            val entry = keyValue(line.text) ?: break
            if (current == null) cursor.index++
            current = null
            val (key, valueText) = entry
            map[key] = if (valueText.isEmpty()) {
                // A child block below, or a sequence written at the key's own indent.
                val next = cursor.peek()
                when {
                    next == null -> null
                    next.indent > indent -> block(cursor, next.indent)
                    next.indent == indent && isSeqItem(next.text) -> sequence(cursor, indent)
                    else -> null
                }
            } else {
                scalar(valueText)
            }
        }
        return map
    }

    /** `key: value` — the first colon outside quotes that ends the key. */
    private fun keyValue(text: String): Pair<String, String>? {
        var single = false
        var double = false
        text.forEachIndexed { i, ch ->
            when {
                ch == '\'' && !double -> single = !single
                ch == '"' && !single -> double = !double
                ch == ':' && !single && !double -> {
                    val rest = text.substring(i + 1)
                    if (rest.isEmpty() || rest.startsWith(" ")) {
                        return text.substring(0, i) to rest.trim()
                    }
                }
            }
        }
        return null
    }

    /** A scalar, or a `[a, b]` flow list. */
    private fun scalar(text: String): Any {
        if (text.startsWith("[") && text.endsWith("]")) {
            return text.substring(1, text.length - 1)
                .split(',')
                .map { unquote(it.trim()) }
                .filter { it.isNotEmpty() }
        }
        return unquote(text)
    }

    private fun unquote(text: String): String = when {
        text.length >= 2 && text.startsWith("'") && text.endsWith("'") -> text.substring(1, text.length - 1)
        text.length >= 2 && text.startsWith("\"") && text.endsWith("\"") -> text.substring(1, text.length - 1)
        else -> text
    }
}
