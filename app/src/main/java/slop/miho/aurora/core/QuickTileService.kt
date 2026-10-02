package slop.miho.aurora.core

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import slop.miho.aurora.MainActivity
import slop.miho.aurora.R

/**
 * Quick Settings toggle, modelled on WireGuard's QuickTileService. It runs inside the ":core"
 * process and talks to [XrayCore] and the VPN service directly, so it keeps working when the
 * UI is long gone.
 */
class QuickTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())

    // Binder threads; tile updates must happen on the main thread.
    private val callback = object : ITunnelCallback.Stub() {
        override fun onStateChanged(state: Int, error: String?) {
            mainHandler.post { updateTile() }
        }
    }

    override fun onStartListening() {
        XrayCore.register(callback)
        updateTile()
    }

    override fun onStopListening() {
        XrayCore.unregister(callback)
    }

    override fun onClick() {
        when (XrayCore.currentStatus().state) {
            TunnelState.UP, TunnelState.CONNECTING -> AuroraVpnService.startDisconnect(this)
            TunnelState.DISCONNECTING -> Unit
            else -> {
                val needsSetup = !CoreConfigFile.has(this) || VpnService.prepare(this) != null
                if (needsSetup) {
                    // Missing config or VPN consent: hand over to the app once unlocked.
                    unlockAndRun {
                        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                            startActivityAndCollapse(
                                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE),
                            )
                        } else {
                            @Suppress("DEPRECATION")
                            startActivityAndCollapse(intent)
                        }
                    }
                } else {
                    AuroraVpnService.startConnect(this)
                }
            }
        }
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val hasConfig = CoreConfigFile.has(this)
        val up = XrayCore.currentStatus().state == TunnelState.UP
        tile.label = getString(R.string.app_name)
        // The wordmark lights up when connected and goes hollow when not. Distinct resource
        // names per state also defeat SystemUI's per-resId tile icon caching.
        tile.icon = Icon.createWithResource(
            this,
            if (up) R.drawable.ic_tile_on else R.drawable.ic_tile_off,
        )
        tile.state = when {
            !hasConfig -> Tile.STATE_UNAVAILABLE
            up -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }
}
