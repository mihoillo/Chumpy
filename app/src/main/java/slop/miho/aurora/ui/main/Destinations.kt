package slop.miho.aurora.ui.main

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import slop.miho.aurora.R

/**
 * The one-shot a pill's icon plays when it becomes the selected one — on a tap, and on the
 * pill's first appearance too: the selected category greets the settings unfolding over it,
 * the selected tab greets the tabs returning from settings. The classic bar never plays
 * these; its icons stay still, as they always have.
 */
enum class NavSelectMotion { NONE, SPIN, TURN, WIGGLE, JUMP, PULSE, SQUASH }

/** Anything that can sit in one of the bottom bar's pills — the sub-screens' own pages too. */
interface PillDestination {
    @get:DrawableRes val icon: Int
    @get:StringRes val label: Int

    /** The icon the classic bar shows instead: the pre-Symbols look it has always worn. */
    @get:DrawableRes val classicIcon: Int get() = icon

    val selectMotion: NavSelectMotion get() = NavSelectMotion.NONE
}

enum class MainTab(
    override val icon: Int,
    override val label: Int,
    override val classicIcon: Int = icon,
    override val selectMotion: NavSelectMotion = NavSelectMotion.NONE,
) : PillDestination {
    // The house takes a breath, the globe gives itself a spin, the bars jump.
    HOME(R.drawable.ic_home, R.string.nav_home, R.drawable.ic_home_classic, NavSelectMotion.PULSE),
    SERVERS(R.drawable.ic_servers, R.string.nav_servers, R.drawable.ic_servers_classic, NavSelectMotion.SPIN),
    STATS(R.drawable.ic_stats, R.string.nav_stats, R.drawable.ic_stats_classic, NavSelectMotion.JUMP),
}

/** Settings categories, in pill order. The interface settings come before the config ones. */
enum class SettingsCategory(
    override val icon: Int,
    override val label: Int,
    override val classicIcon: Int = icon,
    override val selectMotion: NavSelectMotion = NavSelectMotion.NONE,
) : PillDestination {
    // The sun turns where the gear just stood (never a whole revolution), the sliders
    // squash like a pressed one, the info dot gives itself a wiggle.
    INTERFACE(R.drawable.ic_appearance, R.string.settings_interface, R.drawable.ic_appearance_classic, NavSelectMotion.TURN),
    CONNECTION(R.drawable.ic_tune, R.string.settings_connection, R.drawable.ic_tune_classic, NavSelectMotion.SQUASH),
    ABOUT(R.drawable.ic_info, R.string.settings_about, R.drawable.ic_info_classic, NavSelectMotion.WIGGLE),
}
