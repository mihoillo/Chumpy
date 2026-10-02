package slop.miho.adhdium

import android.app.Application
import android.os.Build
import slop.miho.adhdium.core.TunnelClient
import slop.miho.adhdium.core.TunnelCore
import java.io.File

class AdhdiumApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (currentProcessName().endsWith(":tunnel")) {
            // The ":tunnel" process can be started by the system alone (Always-on VPN), so the
            // core is initialised here rather than on the first bind. The UI process never
            // loads it.
            TunnelCore.init(this)
        } else {
            // The UI process's one tunnel link, bound for the whole process lifetime: every
            // screen can watch the tunnel state — the offline grayscale theme among them —
            // without a view model of its own. The main view model keeps driving connect and
            // disconnect over this same link.
            tunnelClient = TunnelClient(this).also { it.bind() }
        }
    }

    private fun currentProcessName(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            getProcessName()
        } else {
            File("/proc/self/cmdline").readText().trimEnd('\u0000')
        }

    companion object {
        /** The UI process's shared tunnel link; the ":tunnel" process never initializes it. */
        lateinit var tunnelClient: TunnelClient
            private set
    }
}
