package slop.miho.chumpy.sub

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.InetAddress
import java.util.Locale

internal object ClashRouting {
    private val domainText = Regex("[A-Za-z0-9_.-]+")
    private val siteCode = Regex("[A-Za-z0-9_-]+(?:@[A-Za-z0-9_-]+)?")
    private val ipv6Text = Regex("[0-9A-Fa-f:.]+")
    private val portText = Regex("[0-9]{1,5}(?:-[0-9]{1,5})?")

    fun parse(config: Map<*, *>, proxyNames: Set<String>): String {
        val mode = config["mode"] as? String
        if (mode != null && !mode.equals("rule", ignoreCase = true)) return ""
        val entries = config["rules"] as? List<*> ?: return ""
        val groups = (config["proxy-groups"] as? List<*>).orEmpty()
            .mapNotNull { (it as? Map<*, *>)?.get("name") as? String }.toSet()
        val proxyTargets = proxyNames + groups + "PROXY"
        val rules = buildList {
            for (entry in entries) {
                val parts = (entry as? String)?.split(',')?.map(String::trim) ?: break
                val type = parts.first().uppercase(Locale.ROOT)
                val catchAll = type == "MATCH"
                val target = parts.getOrNull(if (catchAll) 1 else 2) ?: break
                val outbound = when (target) {
                    "DIRECT" -> "direct"
                    "REJECT", "REJECT-DROP" -> "block"
                    in proxyTargets -> "proxy"
                    else -> break
                }
                val expected = if (catchAll) 2 else 3
                if (parts.size != expected &&
                    !(type in setOf("IP-CIDR", "IP-CIDR6", "GEOIP") &&
                        parts.size == expected + 1 && parts.last() == "no-resolve")) break

                // A later rule must not overtake an unsupported earlier rule.
                val (field, value) = matcher(type, if (catchAll) "" else parts[1]) ?: break
                add(buildJsonObject {
                    put("type", "field")
                    put("outboundTag", outbound)
                    if (field in setOf("domain", "ip", "source")) {
                        put(field, JsonArray(listOf(JsonPrimitive(value))))
                    } else put(field, value)
                })
                if (catchAll) break
            }
        }
        return if (rules.isEmpty()) "" else JsonArray(rules).toString()
    }

    private fun matcher(type: String, value: String): Pair<String, String>? = when (type) {
        "DOMAIN" -> value.takeIf(domainText::matches)?.let { "domain" to "full:$it" }
        "DOMAIN-SUFFIX" -> value.takeIf(domainText::matches)?.let { "domain" to "domain:$it" }
        "DOMAIN-KEYWORD" -> value.takeIf(domainText::matches)?.let { "domain" to "keyword:$it" }
        "GEOSITE" -> value.takeIf(siteCode::matches)?.let { "domain" to "geosite:$it" }
        "GEOIP", "SRC-GEOIP" -> value.takeIf { it.equals("ru", true) || it.equals("private", true) }
            ?.let { (if (type == "GEOIP") "ip" else "source") to "geoip:$it" }
        "IP-CIDR", "IP-CIDR6", "SRC-IP-CIDR" -> value.takeIf(::validCidr)
            ?.let { (if (type == "SRC-IP-CIDR") "source" else "ip") to it }
        "DST-PORT", "SRC-PORT" -> value.takeIf(::validPort)
            ?.let { (if (type == "DST-PORT") "port" else "sourcePort") to it }
        "NETWORK" -> value.lowercase(Locale.ROOT).takeIf { it == "tcp" || it == "udp" }?.let { "network" to it }
        "MATCH" -> "network" to "tcp,udp"
        else -> null
    }

    private fun validCidr(value: String): Boolean {
        val address = value.substringBefore('/')
        val prefix = value.substringAfter('/', "").toIntOrNull() ?: return false
        if (':' in address) {
            return prefix in 0..128 && ipv6Text.matches(address) &&
                runCatching { InetAddress.getByName(address) }.isSuccess
        }
        val octets = address.split('.')
        return prefix in 0..32 && octets.size == 4 && octets.all {
            it.length in 1..3 && (it.length == 1 || it[0] != '0') &&
                it.all { char -> char in '0'..'9' } && it.toInt() <= 255
        }
    }

    private fun validPort(value: String): Boolean {
        if (!portText.matches(value)) return false
        val start = value.substringBefore('-').toInt()
        val end = value.substringAfter('-', start.toString()).toInt()
        return start in 1..65535 && end in start..65535
    }
}
