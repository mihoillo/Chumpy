package slop.miho.chumpy.windows

import java.io.File
import java.util.concurrent.TimeUnit

/** Runs the native Windows core as a child process; Android libv2ray.aar is not Windows-compatible. */
internal class ManagedEngine {
    @Volatile private var process: Process? = null

    @Synchronized fun start(executable: File, config: File, tun: Boolean) {
        require(executable.isFile) { "Движок не найден: ${executable.path}" }
        require(config.isFile) { "JSON не найден: ${config.path}" }
        check(process?.isAlive != true) { "Движок уже запущен" }
        if (tun) require(isAdministrator()) { "Для TUN запустите Chumpy от имени администратора." }
        val log = File(config.parentFile, "chumpy-engine.log")
        process = ProcessBuilder(executable.absolutePath, "run", "-c", config.absolutePath)
            .directory(executable.parentFile).redirectErrorStream(true)
            .redirectOutput(ProcessBuilder.Redirect.appendTo(log)).start()
        Thread.sleep(400)
        check(process?.isAlive == true) { "Движок завершился при запуске. Проверьте ${log.absolutePath}" }
    }

    @Synchronized fun stop() {
        val p = process ?: return
        p.destroy()
        if (!p.waitFor(3, TimeUnit.SECONDS)) p.destroyForcibly()
        process = null
    }
    private fun isAdministrator(): Boolean {
        val p = ProcessBuilder("net", "session").redirectErrorStream(true).start()
        p.inputStream.close()
        return p.waitFor() == 0
    }
}
