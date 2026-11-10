package slop.miho.chumpy.windows

import java.io.File

/** Saves and restores the current user's exact Internet Settings proxy values. */
internal class WindowsProxyController(private val dataDir: File) {
    private val key = "HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings"
    private val backup = File(dataDir, "proxy-backup.properties")

    fun enable(address: String) {
        if (!backup.exists()) {
            val enabled = query("ProxyEnable") ?: "0"
            val server = query("ProxyServer").orEmpty()
            backup.writeText("ProxyEnable=$enabled\nProxyServer=$server\n")
        }
        run("reg", "add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", "1", "/f")
        run("reg", "add", key, "/v", "ProxyServer", "/t", "REG_SZ", "/d", address, "/f")
        refresh()
    }
    fun restore() {
        if (!backup.isFile) return
        val values = backup.readLines().associate { val p = it.split("=", limit = 2); p[0] to p.getOrElse(1) { "" } }
        run("reg", "add", key, "/v", "ProxyEnable", "/t", "REG_DWORD", "/d", (values["ProxyEnable"]?.toIntOrNull() ?: 0).toString(), "/f")
        val server = values["ProxyServer"].orEmpty()
        if (server.isBlank()) run("reg", "delete", key, "/v", "ProxyServer", "/f", true)
        else run("reg", "add", key, "/v", "ProxyServer", "/t", "REG_SZ", "/d", server, "/f")
        backup.delete()
        refresh()
    }
    private fun query(name: String): String? {
        val p = ProcessBuilder("reg", "query", key, "/v", name).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText(); p.waitFor()
        val line = output.lineSequence().firstOrNull { it.contains(name) } ?: return null
        return line.trim().split(Regex("\\s+")).lastOrNull()
    }
    private fun run(vararg args: String, ignoreFailure: Boolean = false) {
        val p = ProcessBuilder(*args).redirectErrorStream(true).start()
        val output = p.inputStream.bufferedReader().readText(); val code = p.waitFor()
        check(ignoreFailure || code == 0) { "Команда Windows завершилась с кодом $code: $output" }
    }
    private fun refresh() { run("rundll32.exe", "inetcpl.cpl,ClearMyTracksByProcess", "8") }
}
