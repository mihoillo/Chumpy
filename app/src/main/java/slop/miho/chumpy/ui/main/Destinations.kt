package slop.miho.chumpy.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import slop.miho.chumpy.R

/** Anything that can sit in one of the bottom bar's pills — the sub-screens' own pages too. */
interface PillDestination {
    @get:DrawableRes val icon: Int
    @get:StringRes val label: Int

    /** The icon's filled cut, the one the pill wears while selected. Glyphs whose filled
     *  cut is the same shape simply keep the default. */
    @get:DrawableRes val filledIcon: Int get() = icon
}

enum class MainTab(
    override val icon: Int,
    override val label: Int,
    override val filledIcon: Int = icon,
) : PillDestination {
    HOME(R.drawable.ic_home, R.string.nav_home, R.drawable.ic_home_fill),
    SERVERS(R.drawable.ic_servers, R.string.nav_servers),
    STATS(R.drawable.ic_stats, R.string.nav_stats),
}

/** Settings categories, in pill order. The interface settings come before the config ones. */
enum class SettingsCategory(
    override val icon: Int,
    override val label: Int,
    override val filledIcon: Int = icon,
) : PillDestination {
    INTERFACE(R.drawable.ic_appearance, R.string.settings_interface, R.drawable.ic_appearance_fill),
    CONNECTION(R.drawable.ic_tune, R.string.settings_connection),
    ABOUT(R.drawable.ic_info, R.string.settings_about, R.drawable.ic_info_fill),
}
