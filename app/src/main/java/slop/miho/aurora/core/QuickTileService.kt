package slop.miho.aurora.core

import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Icon
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import slop.miho.aurora.MainActivity
import slop.miho.aurora.R
import slop.miho.aurora.data.IconVariant
import slop.miho.aurora.data.SettingsStore
import slop.miho.aurora.ui.theme.themedIconColor

/**
 * Quick Settings toggle, modelled on WireGuard's QuickTileService. It runs inside the ":core"
 * process and talks to [XrayCore] and the VPN service directly, so it keeps working when the
 * UI is long gone.
 *
 * The glyph follows the picked launcher icon variant: the sky art, its white inversion, or
 * the "themed" one — the app's own accent, live, so flipping the accent re-tints the tile
 * the next time it shows. The settings flow is watched while the tile is on screen, so a
 * variant picked in the UI lands here without a re-add.
 */
class QuickTileService : TileService() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Binder threads; tile updates must happen on the main thread.
    private val callback = object : ITunnelCallback.Stub() {
        override fun onStateChanged(state: Int, error: String?) {
            mainHandler.post { updateTile() }
        }
    }

    override fun onStartListening() {
        XrayCore.register(callback)
        // The icon variant (and, for the themed one, the accent) can flip in the UI while
        // the tile sits on screen: follow the settings live.
        scope.launch {
            SettingsStore.settings(this@QuickTileService).collect { updateTile() }
        }
        updateTile()
    }

    override fun onStopListening() {
        XrayCore.unregister(callback)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
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
        // The server the tile's tap answers with, told under the label wherever the
        // platform carries a subtitle at all — the picked profile's name without its flag
        // emoji, "unknown server" when the name went missing, nothing when there is no
        // config to connect.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = if (hasConfig) {
                CoreConfigFile.readProfileName(this)?.let(::stripFlag)
                    ?: getString(R.string.notif_profile_unknown)
            } else {
                null
            }
        }
        // Distinct resources per state also defeat SystemUI's per-resId tile icon caching;
        // while down the shield's glyph stays crossed out in the familiar dim gray.
        tile.icon = when {
            !up -> Icon.createWithResource(this, R.drawable.ic_tile_off)
            else -> variantIcon(SettingsStore.settings(this).value.iconVariant)
        }
        tile.state = when {
            !hasConfig -> Tile.STATE_UNAVAILABLE
            up -> Tile.STATE_ACTIVE
            else -> Tile.STATE_INACTIVE
        }
        tile.updateTile()
    }

    /** The connected glyph in the picked variant's color. */
    private fun variantIcon(variant: IconVariant): Icon = when (variant) {
        IconVariant.AURORA -> Icon.createWithResource(this, R.drawable.ic_tile_on)
        IconVariant.INVERTED -> Icon.createWithResource(this, R.drawable.ic_tile_inverted)
        // The app's own accent, tinted onto the white glyph at runtime — the live scheme,
        // not a color baked into a resource.
        IconVariant.THEMED -> themedIcon(themedIconColor(this, SettingsStore.settings(this).value))
    }

    /** The white tile glyph tinted [color]; cached, since the tile re-renders often. */
    private fun themedIcon(color: Int): Icon {
        cache?.let { (cachedColor, icon) -> if (cachedColor == color) return icon }
        // The glyph is the connected-state vector in white: tinting it with the accent
        // recolors the whole drawing while the art's own alphas stay.
        val drawable = getDrawable(R.drawable.ic_tile_inverted)!!.mutate()
        drawable.setTint(color)
        val size = 512
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return Icon.createWithBitmap(bitmap).also { cache = color to it }
    }

    private var cache: Pair<Int, Icon>? = null
}
