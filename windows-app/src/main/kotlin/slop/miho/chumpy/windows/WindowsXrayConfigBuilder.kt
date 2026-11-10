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
                put("tag", "http-in"); put("listen", "127.0.0.1"); put("port", 10809)
                put("protocol", "http"); put("settings", buildJsonObject {})
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
