package slop.miho.chumpy.sub

import kotlinx.serialization.json.*
import java.security.MessageDigest

internal object GeodataSources {
    private val json = Json { ignoreUnknownKeys = true }
    private val filename = Regex("[A-Za-z0-9][A-Za-z0-9_.-]{0,79}\\.dat")

    fun validFilename(name: String): Boolean = filename.matches(name) && !name.contains("..")

    fun fromHappHeader(header: String): String = runCatching {
        val value = header.trim()
        val prefix = listOf("happ://routing/onadd/", "happ://routing/add/").firstOrNull { value.startsWith(it, ignoreCase = true) }
            ?: return ""
        require(value.length <= 256 * 1024)
        val decoded = LinkUtils.decodeBase64(LinkUtils.decodeURIComponent(value.substring(prefix.length))) ?: return ""
        val config = json.parseToJsonElement(decoded) as? JsonObject ?: return ""
        val sources = linkedMapOf<String, JsonElement>()
        listOf("Geoipurl" to "geoip.dat", "Geositeurl" to "geosite.dat").forEach { (key, file) ->
            val url = (config[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.trim().orEmpty()
            if (url.isNotEmpty()) sources[file] = JsonPrimitive(url)
        }
        if (sources.isEmpty()) "" else JsonObject(sources).toString()
    }.getOrDefault("")

    fun merge(defaults: String, overrides: String): String {
        val sources = parse(defaults) + parse(overrides)
        return if (sources.isEmpty()) "" else JsonObject(sources.mapValues { JsonPrimitive(it.value) }).toString()
    }

    fun fromConfig(config: JsonObject): String {
        val sources = linkedMapOf<String, JsonElement>()
        (config["geodata"] as? JsonObject)?.get("assets")?.let { assets ->
            (assets as? JsonArray)?.filterIsInstance<JsonObject>()?.forEach { entry ->
                val name = (entry["file"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                val url = (entry["url"] as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (validFilename(name) && url.isNotBlank()) sources[name] = JsonPrimitive(url)
            }
        }
        (config["geox-url"] as? JsonObject)?.let { urls ->
            listOf("geoip", "geosite").forEach { key ->
                (urls[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }?.let { sources.putIfAbsent("$key.dat", JsonPrimitive(it)) }
            }
        }
        return if (sources.isEmpty()) "" else JsonObject(sources).toString()
    }

    fun parse(sources: String): Map<String, String> = if (sources.isBlank()) emptyMap() else {
        val entries = json.parseToJsonElement(sources) as? JsonObject ?: error("Invalid geodata sources")
        entries.mapValues { (_, value) -> (value as? JsonPrimitive)?.contentOrNull ?: error("Invalid geodata URL") }
            .also { require(it.keys.all(::validFilename)) { "Invalid geodata filename" } }
    }

    fun filenameFor(url: String): String = "provider-" +
        MessageDigest.getInstance("SHA-256").digest(url.toByteArray()).joinToString("") { "%02x".format(it) } + ".dat"

    fun rewrite(rule: JsonObject, sources: String): JsonObject {
        val files = parse(sources)
        if (files.isEmpty()) return rule
        return JsonObject(rule.mapValues { (key, value) ->
            if (key !in setOf("domain", "ip", "source")) value else {
                fun remap(element: JsonElement): JsonElement {
                    val text = (element as? JsonPrimitive)?.contentOrNull ?: return element
                    val name: String
                    val category: String
                    when {
                        text.startsWith("geosite:") -> { name = "geosite.dat"; category = text.removePrefix("geosite:") }
                        text.startsWith("geoip:") -> { name = "geoip.dat"; category = text.removePrefix("geoip:") }
                        text.startsWith("ext:") -> { name = text.removePrefix("ext:").substringBefore(':'); category = text.substringAfter("ext:$name:", "") }
                        else -> return element
                    }
                    return files[name]?.let { JsonPrimitive("ext:${filenameFor(it)}:$category") } ?: element
                }
                if (value is JsonArray) JsonArray(value.map(::remap)) else remap(value)
            }
        })
    }
}
