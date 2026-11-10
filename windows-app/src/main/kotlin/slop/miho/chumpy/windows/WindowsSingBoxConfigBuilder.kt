package slop.miho.chumpy.windows

import kotlinx.serialization.json.*
import java.io.File

/** sing-box Windows TUN configuration generated from the same imported profile model. */
internal object WindowsSingBoxConfigBuilder {
    fun write(profile: ProfileEntity, directory: File): File {
        require(profile.type != ProfileType.BALANCER) { "Выберите конкретный сервер, не балансировщик." }
        require(profile.network !in setOf("kcp", "mkcp", "xhttp", "splithttp")) {
            "Транспорт ${profile.network} пока не поддерживается Windows sing-box-портом."
        }
        val outbound = buildJsonObject {
            put("type", when (profile.type) {
                ProfileType.VMESS -> "vmess"
                ProfileType.VLESS -> "vless"
                ProfileType.TROJAN -> "trojan"
                ProfileType.SHADOWSOCKS -> "shadowsocks"
                ProfileType.SOCKS -> "socks"
                ProfileType.WIREGUARD -> "wireguard"
                ProfileType.HYSTERIA2 -> "hysteria2"
                ProfileType.BALANCER -> error("Balancer is not a server")
            })
            put("tag", "proxy")
            put("server", profile.server)
            put("server_port", profile.serverPort)
            when (profile.type) {
                ProfileType.VMESS -> {
                    put("uuid", profile.password)
                    put("security", profile.method.ifEmpty { "auto" })
                    put("alter_id", 0)
                    putTls(profile)
                    putTransport(profile)
                }
                ProfileType.VLESS -> {
                    put("uuid", profile.password)
                    if (profile.flow.isNotBlank()) put("flow", profile.flow)
                    putTls(profile)
                    putTransport(profile)
                }
                ProfileType.TROJAN -> {
                    put("password", profile.password)
                    putTls(profile)
                    putTransport(profile)
                }
                ProfileType.SHADOWSOCKS -> {
                    put("method", profile.method)
                    put("password", profile.password)
                }
                ProfileType.SOCKS -> {
                    if (profile.username.isNotBlank()) put("username", profile.username)
                    if (profile.password.isNotBlank()) put("password", profile.password)
                }
                ProfileType.HYSTERIA2 -> {
                    put("password", profile.password)
                    if (profile.obfsPassword.isNotBlank()) {
                        put("obfs", buildJsonObject { put("type", "salamander"); put("password", profile.obfsPassword) })
                    }
                    put("tls", buildJsonObject {
                        put("enabled", true)
                        if (profile.sni.isNotBlank()) put("server_name", profile.sni)
                        put("insecure", profile.allowInsecure)
                        if (profile.fingerprint.isNotBlank()) put("utls", buildJsonObject { put("enabled", true); put("fingerprint", profile.fingerprint) })
                        put("alpn", buildJsonArray { add("h3") })
                    })
                }
                ProfileType.WIREGUARD -> {
                    put("private_key", profile.wgSecretKey)
                    put("local_address", buildJsonArray { profile.wgLocalAddress.split(',').map(String::trim).filter(String::isNotBlank).forEach(::add) })
                    put("peer_public_key", profile.publicKey)
                    if (profile.wgPresharedKey.isNotBlank()) put("pre_shared_key", profile.wgPresharedKey)
                    put("mtu", if (profile.wgMtu > 0) profile.wgMtu else 1420)
                    val reserved = profile.wgReserved.split(',').mapNotNull { it.trim().toIntOrNull() }
                    if (reserved.size == 3) put("reserved", buildJsonArray { reserved.forEach(::add) })
                }
                ProfileType.BALANCER -> Unit
            }
        }
        val config = buildJsonObject {
            put("log", buildJsonObject { put("level", "warn") })
            put("dns", buildJsonObject {
                put("servers", buildJsonArray {
                    add(buildJsonObject { put("tag", "dns-remote"); put("address", "https://1.1.1.1/dns-query"); put("detour", "proxy") })
                    add(buildJsonObject { put("tag", "dns-direct"); put("address", "local") })
                })
                put("final", "dns-remote")
            })
            put("inbounds", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tun"); put("tag", "tun-in")
                    put("interface_name", "Chumpy")
                    put("address", buildJsonArray { add("172.19.0.1/30") })
                    put("mtu", 1500); put("auto_route", true); put("strict_route", true)
                    put("stack", "mixed"); put("sniff", true)
                })
            })
            put("outbounds", buildJsonArray {
                add(outbound)
                add(buildJsonObject { put("type", "direct"); put("tag", "direct") })
                add(buildJsonObject { put("type", "block"); put("tag", "block") })
            })
            put("route", buildJsonObject {
                put("auto_detect_interface", true)
                put("final", "proxy")
                put("rules", buildJsonArray {
                    add(buildJsonObject { put("ip_is_private", true); put("outbound", "direct") })
                    add(buildJsonObject { put("protocol", "dns"); put("action", "hijack-dns") })
                })
            })
        }
        directory.mkdirs()
        return File(directory, "sing-box-tun.json").apply { writeText(config.toString()) }
    }

    private fun JsonObjectBuilder.putTls(profile: ProfileEntity) {
        if (profile.security.isBlank() && profile.type != ProfileType.TROJAN) return
        put("tls", buildJsonObject {
            put("enabled", true)
            if (profile.sni.isNotBlank()) put("server_name", profile.sni)
            put("insecure", profile.allowInsecure)
            if (profile.alpn.isNotBlank()) put("alpn", buildJsonArray { profile.alpn.split(',').map(String::trim).filter(String::isNotBlank).forEach(::add) })
            if (profile.fingerprint.isNotBlank()) put("utls", buildJsonObject { put("enabled", true); put("fingerprint", profile.fingerprint) })
            if (profile.security == "reality") put("reality", buildJsonObject {
                put("enabled", true); put("public_key", profile.publicKey)
                if (profile.shortId.isNotBlank()) put("short_id", profile.shortId)
            })
        })
    }

    private fun JsonObjectBuilder.putTransport(profile: ProfileEntity) {
        when (profile.network.lowercase()) {
            "ws", "websocket" -> put("transport", buildJsonObject {
                put("type", "ws"); put("path", profile.path)
                if (profile.host.isNotBlank()) put("headers", buildJsonObject { put("Host", profile.host) })
            })
            "grpc" -> put("transport", buildJsonObject {
                put("type", "grpc"); put("service_name", profile.serviceName)
            })
            "httpupgrade" -> put("transport", buildJsonObject {
                put("type", "httpupgrade"); put("path", profile.path)
                if (profile.host.isNotBlank()) put("host", profile.host)
            })
            "tcp", "raw", "" -> Unit
            else -> error("Неизвестный транспорт: ${profile.network}")
        }
    }
}
