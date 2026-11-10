package slop.miho.chumpy.windows

import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

internal object WindowsXrayConfigBuilder {
    fun write(profile: ProfileEntity, directory: File): File {
        require(profile.type != ProfileType.BALANCER) { "Выберите конкретный сервер, не балансировщик." }
        val config = buildJsonObject {
            put("log", buildJsonObject { put("loglevel", "warning") })
            put("inbounds", buildJsonArray { add(buildJsonObject {
                put("tag", "socks-in"); put("listen", "127.0.0.1"); put("port", 10808)
                put("protocol", "socks"); put("settings", buildJsonObject { put("auth", "noauth"); put("udp", true) })
            }) })
            put("outbounds", buildJsonArray {
                add(OutboundBuilder.build(profile, "proxy"))
                add(buildJsonObject { put("tag", "direct"); put("protocol", "freedom"); put("settings", buildJsonObject {}) })
                add(buildJsonObject { put("tag", "block"); put("protocol", "blackhole"); put("settings", buildJsonObject {}) })
            })
            put("routing", buildJsonObject { put("domainStrategy", "AsIs"); put("rules", buildJsonArray {}) })
        }
        directory.mkdirs()
        return File(directory, "xray-windows.json").apply { writeText(config.toString()) }
    }
}
