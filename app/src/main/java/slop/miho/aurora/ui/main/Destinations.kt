package slop.miho.aurora.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import slop.miho.aurora.R

/** Anything that can sit in one of the bottom bar's pills — the sub-screens' own pages too. */
interface PillDestination {
    @get:DrawableRes val icon: Int
    @get:StringRes val label: Int
}

enum class MainTab(override val icon: Int, override val label: Int) : PillDestination {
    HOME(R.drawable.ic_home, R.string.nav_home),
    SERVERS(R.drawable.ic_servers, R.string.nav_servers),
    DEVICES(R.drawable.ic_devices, R.string.nav_devices),
    STATS(R.drawable.ic_stats, R.string.nav_stats),
}

/** Settings categories, in pill order. The interface settings come before the config ones. */
enum class SettingsCategory(override val icon: Int, override val label: Int) : PillDestination {
    INTERFACE(R.drawable.ic_appearance, R.string.settings_interface),
    CONNECTION(R.drawable.ic_tune, R.string.settings_connection),
    ABOUT(R.drawable.ic_info, R.string.settings_about),
}
