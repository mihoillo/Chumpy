package slop.miho.chumpy.data

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * Applies the [IconVariant] choice to the launcher: the manifest carries one
 * activity-alias per variant, exactly one of them enabled, and switching the icon is
 * enabling the new alias and disabling the rest. The new one goes first — there must never
 * be a moment without a launcher entry at all.
 *
 * The choice survives updates and reboots (PackageManager remembers component states),
 * and a fresh install always starts from the manifest's default: the Chumpy icon.
 */
object LauncherIcon {
    private val aliases: Map<IconVariant, String> = mapOf(
        IconVariant.CHUMPY to "slop.miho.chumpy.LauncherChumpy",
        IconVariant.INVERTED to "slop.miho.chumpy.LauncherInverted",
        IconVariant.THEMED to "slop.miho.chumpy.LauncherThemed",
    )

    /** Which alias is currently enabled, per PackageManager — the installed truth. */
    fun currentVariant(context: Context): IconVariant? {
        val pm = context.packageManager
        return aliases.entries.firstOrNull { (variant, name) ->
            pm.getComponentEnabledSetting(ComponentName(context, name)) !=
                PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        }?.key
    }

    fun apply(context: Context, variant: IconVariant) {
        val pm = context.packageManager
        // The picked alias first, so the app never loses its launcher entry mid-swap.
        val target = ComponentName(context, aliases.getValue(variant))
        pm.setComponentEnabledSetting(
            target,
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            PackageManager.DONT_KILL_APP,
        )
        aliases.values
            .filterNot { it == aliases.getValue(variant) }
            .forEach { name ->
                pm.setComponentEnabledSetting(
                    ComponentName(context, name),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    PackageManager.DONT_KILL_APP,
                )
            }
    }
}
