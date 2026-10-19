package slop.miho.chumpy

import android.app.Application
import android.os.Build
import slop.miho.chumpy.core.TunnelClient
import slop.miho.chumpy.core.XrayCore
import java.io.File

class ChumpyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        if (currentProcessName().endsWith(":core")) {
            // The ":core" process can be started by the system alone (Always-on VPN, the
            // QS tile, or a bound-only measurement), so the core is initialised here rather
            // than on the first command. The UI process never loads it.
            XrayCore.init(this)
        } else {
            // The main process owns the profile database and the subscription machinery
            // on top of it; the ":core" process never opens either.
            Graph.init(this)
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
        /** The UI process's shared tunnel link; the ":core" process never initializes it. */
        lateinit var tunnelClient: TunnelClient
            private set
    }
}
