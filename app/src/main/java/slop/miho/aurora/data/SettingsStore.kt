package slop.miho.aurora.data

import android.app.ActivityManager
import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The light/dark choice. AMOLED is the dark theme gone darker — a pure black background and
 * deeper containers; it hides behind a long tap on the dark option.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK,
    AMOLED,
}

/**
 * How the server list draws an exit's country: Twemoji flags from the bundled font, the
 * system's own emoji flags, or the plain globe icon the list used before flags existed.
 */
enum class FlagStyle { TWEMOJI, SYSTEM, GLOBE }

/**
 * The launcher icon variant. AURORA is the flat sky-colored art on white; INVERTED swaps
 * the two; THEMED is the neutral art the system's themed icons (Android 13+) dress in the
 * monet palette — and that the app itself colors from its own accent everywhere it draws
 * (the QS tile, the picker's preview).
 */
enum class IconVariant { AURORA, INVERTED, THEMED }

/**
 * The interface accent. ADAPTIVE follows the wallpaper (Material You, Android 12+); the rest
 * are the seven rainbow hues. [hue] is the HSL hue the color scheme is built from; ADAPTIVE
 * ignores it and is hidden on devices without Material You. The WALLPAPER_* entries are
 * seeds pulled live from the picked wallpaper instead — their [hue] is a placeholder,
 * resolved at theming time by [WallpaperPalette].
 */
enum class Accent(val hue: Float) {
    ADAPTIVE(0f),

    /** A seed color pulled from the picked wallpaper; the hue is resolved at theming time. */
    WALLPAPER_1(0f),

    /** A seed color pulled from the picked wallpaper; the hue is resolved at theming time. */
    WALLPAPER_2(0f),

    /** A seed color pulled from the picked wallpaper; the hue is resolved at theming time. */
    WALLPAPER_3(0f),
    RED(4f),
    ORANGE(30f),
    YELLOW(52f),
    GREEN(145f),

    /** The light blue of #6FACED — the cyan side of blue rather than BLUE's indigo side. */
    SKY(211f),
    BLUE(217f),
    INDIGO(248f),
    VIOLET(285f),

    /** Black-and-white accent; [hue] is unused — the scheme is built from pure neutrals. */
    MONO(0f),

    /**
     * The hue slowly cycling around the whole color wheel; [hue] is unused — the theme
     * animates it. Hidden behind a long tap on the monochrome swatch.
     */
    RAINBOW(0f);

    /** Whether the hue comes from the picked wallpaper rather than this table. */
    val fromWallpaper: Boolean
        get() = this == WALLPAPER_1 || this == WALLPAPER_2 || this == WALLPAPER_3

    /** The [WallpaperPalette] seed slot this entry reads; valid only when [fromWallpaper]. */
    val seedIndex: Int
        get() = ordinal - WALLPAPER_1.ordinal
}

/** UI preferences. Nothing sensitive here, so plain SharedPreferences storage is fine. */
data class UiSettings(
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val accent: Accent = Accent.ADAPTIVE,
    val slowAnimations: Boolean = false,
    /**
     * Whether page switches take the simple slide-up instead of the expressive strip
     * scroll. Weak devices get it out of the box; everyone else starts expressive.
     */
    val simpleTransitions: Boolean = false,
    /**
     * Whether the wallpaper picture drains to grayscale while the tunnel is down: only the
     * picture loses its color — the interface over it keeps the accent — and the color
     * washes back in the moment the VPN connects.
     */
    val grayscaleOffline: Boolean = false,
    val hapticFeedback: Boolean = true,
    /** Whether the home screen's exit IP chip reads "Hidden"; flipped by tapping the chip. */
    val hideIp: Boolean = false,
    /** The [FontRegistry] id of the app-wide typeface; Inter out of the box. */
    val fontId: String = FontRegistry.INTER_ID,
    /** How the server list draws each exit's country. */
    val flagStyle: FlagStyle = FlagStyle.TWEMOJI,
    /** The launcher icon variant; see [IconVariant]. */
    val iconVariant: IconVariant = IconVariant.AURORA,
    /** How strongly the scrim dims the wallpaper on Home, 0 (none) to 1 (fully dark). */
    val wallpaperDim: Float = 0.4f,
    /** The scrim strength on every other screen, 0 (none) to 1 (fully dark). */
    val wallpaperDimScreens: Float = 0.5f,
    /** The wallpaper blur behind every screen but Home and the preview, 0 (none) to 1. */
    val wallpaperBlur: Float = 0.25f,
    /** The scrim's tone: follow the theme, or force light/dark. */
    val wallpaperDimTone: DimTone = DimTone.AUTO,
    /**
     * Whether the bare Home text — the app name and the greeting, lying right on the picture —
     * wears the dark scheme's roles instead of the theme's own. Not adaptive: the user flips
     * it themselves by tapping the text on the wallpapers preview, and it falls back to the
     * theme's colors the moment the picture is gone.
     */
    val wallpaperTextDark: Boolean = false,
    /** A slight page zoom while switching between the wallpaper screen's two pages. */
    val wallpaperPageZoom: Boolean = true,
    /**
     * How see-through the menus' surfaces are over the wallpaper: 1 (opaque) down to 0.30,
     * the picker's floor — the menus never fully dissolve into the picture.
     */
    val surfaceOpacity: Float = 1f,
    /**
     * Whether the floating bar frosts over what's behind it away from Home: the pill
     * re-draws the wallpaper and the rows behind itself, blurred, under a tint — glass. Home is exempt:
     * there the bar keeps the plain surface-opacity rule and no blur at all, dissolving
     * into the picture and back as the user moves around.
     */
    val navigationBarBlur: Boolean = false,
    /**
     * How strong the bar's frost is, 0.35 to 0.85: the higher, the denser the pill's tint
     * and the deeper the blur behind it. Applies only while the frost is on.
     */
    val navigationBarBlurIntensity: Float = 0.60f,
    /**
     * Whether navigation uses the classic edge-to-edge MD3 navigation bar instead of the
     * floating pills. Null until the user flips it: then it follows the screen — on for small
     * (narrow) windows the floating bar doesn't fit, off everywhere else.
     */
    val classicNavigationBar: Boolean? = null,
    /**
     * Whether the app lays out for a small screen — tighter margins, the classic bar by
     * default — forced on or off by a long tap on the classic bar's toggle. Null follows the
     * window's width.
     */
    val smallScreen: Boolean? = null,
    /**
     * The wallpaper's saved pan on each axis, normalized to the slack its crop leaves there
     * (-1..1): 0 centers it, the extremes press it against the screen's edges. Normalized,
     * the position survives zooming and screens of any size without uncovering the sides.
     */
    val wallpaperOffsetX: Float = 0f,
    val wallpaperOffsetY: Float = 0f,
    /**
     * The user's own zoom on the wallpaper, pinched in the position edit mode. 1 is the
     * crop's natural cover; below it the whole picture becomes visible, down to its letterbox
     * floor, and up to 4 it magnifies past the cover. The pan rides the slack the zoom
     * leaves — above the cover it can never uncover the screen's sides.
     */
    val wallpaperUserScale: Float = 1f,
)

object SettingsStore {
    private const val PREFS = "ui_settings"

    /**
     * The current settings as a shared state: seeded from disk once per process and updated
     * by every write. The fonts activity writes to it too, so a font picked there re-themes
     * the main screen the moment the user comes back.
     */
    private val settingsState = MutableStateFlow(UiSettings())

    fun settings(context: Context): StateFlow<UiSettings> {
        if (!seeded) {
            settingsState.value = read(context)
            seeded = true
        }
        return settingsState.asStateFlow()
    }

    fun update(context: Context, transform: (UiSettings) -> UiSettings) {
        val old = settings(context).value
        val updated = transform(old)
        write(context, updated)
        settingsState.value = updated
        // The launcher icon flips the moment the variant is picked — the aliases swap
        // under the launcher without a reinstall.
        if (updated.iconVariant != old.iconVariant) LauncherIcon.apply(context, updated.iconVariant)
    }

    private var seeded = false

    private fun read(context: Context): UiSettings {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return UiSettings(
            themeMode = enumEntry(prefs.getString(KEY_THEME, null), ThemeMode.SYSTEM),
            accent = enumEntry(prefs.getString(KEY_ACCENT, null), Accent.ADAPTIVE),
            // The speed setting used to be a three-way enum; a stored SLOW carries over.
            slowAnimations = if (prefs.contains(KEY_SLOW)) {
                prefs.getBoolean(KEY_SLOW, false)
            } else {
                prefs.getString(KEY_SPEED, null) == "SLOW"
            },
            simpleTransitions = prefs.getBoolean(KEY_SIMPLE_TRANSITIONS, isWeakDevice(context)),
            grayscaleOffline = prefs.getBoolean(KEY_GRAYSCALE_OFFLINE, false),
            hapticFeedback = prefs.getBoolean(KEY_HAPTIC, true),
            hideIp = prefs.getBoolean(KEY_HIDE_IP, false),
            fontId = prefs.getString(KEY_FONT, null) ?: FontRegistry.INTER_ID,
            flagStyle = enumEntry(prefs.getString(KEY_FLAG_STYLE, null), FlagStyle.TWEMOJI),
            iconVariant = enumEntry(prefs.getString(KEY_ICON_VARIANT, null), IconVariant.AURORA),
            wallpaperDim = prefs.getFloat(KEY_WALLPAPER_DIM, 0.4f),
            wallpaperDimScreens = prefs.getFloat(KEY_WALLPAPER_DIM_SCREENS, 0.5f),
            wallpaperBlur = prefs.getFloat(KEY_WALLPAPER_BLUR, 0.25f),
            wallpaperDimTone = enumEntry(prefs.getString(KEY_WALLPAPER_DIM_TONE, null), DimTone.AUTO),
            wallpaperTextDark = prefs.getBoolean(KEY_WALLPAPER_TEXT_DARK, false),
            wallpaperPageZoom = prefs.getBoolean(KEY_WALLPAPER_PAGE_ZOOM, true),
            surfaceOpacity = prefs.getFloat(KEY_SURFACE_OPACITY, 1f),
            wallpaperOffsetX = prefs.getFloat(KEY_WALLPAPER_OFFSET_X, 0f),
            wallpaperOffsetY = prefs.getFloat(KEY_WALLPAPER_OFFSET_Y, 0f),
            wallpaperUserScale = prefs.getFloat(KEY_WALLPAPER_USER_SCALE, 1f),
            navigationBarBlur = prefs.getBoolean(KEY_NAVIGATION_BAR_BLUR, false),
            navigationBarBlurIntensity = prefs.getFloat(
                KEY_NAVIGATION_BAR_BLUR_INTENSITY,
                0.60f,
            ),
            classicNavigationBar = if (prefs.contains(KEY_CLASSIC_NAVIGATION_BAR)) {
                prefs.getBoolean(KEY_CLASSIC_NAVIGATION_BAR, false)
            } else {
                null
            },
            smallScreen = if (prefs.contains(KEY_SMALL_SCREEN)) {
                prefs.getBoolean(KEY_SMALL_SCREEN, false)
            } else {
                null
            },
        )
    }

    private fun write(context: Context, settings: UiSettings) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_THEME, settings.themeMode.name)
            .putString(KEY_ACCENT, settings.accent.name)
            .putBoolean(KEY_SLOW, settings.slowAnimations)
            .remove(KEY_SPEED)
            .remove(KEY_EXPRESSIVE_TRANSITIONS)
            .putBoolean(KEY_SIMPLE_TRANSITIONS, settings.simpleTransitions)
            .putBoolean(KEY_GRAYSCALE_OFFLINE, settings.grayscaleOffline)
            .putBoolean(KEY_HAPTIC, settings.hapticFeedback)
            .putBoolean(KEY_HIDE_IP, settings.hideIp)
            .putString(KEY_FONT, settings.fontId)
            .putString(KEY_FLAG_STYLE, settings.flagStyle.name)
            .putString(KEY_ICON_VARIANT, settings.iconVariant.name)
            .putFloat(KEY_WALLPAPER_DIM, settings.wallpaperDim)
            .putFloat(KEY_WALLPAPER_DIM_SCREENS, settings.wallpaperDimScreens)
            .putFloat(KEY_WALLPAPER_BLUR, settings.wallpaperBlur)
            .putString(KEY_WALLPAPER_DIM_TONE, settings.wallpaperDimTone.name)
            .putBoolean(KEY_WALLPAPER_TEXT_DARK, settings.wallpaperTextDark)
            .putBoolean(KEY_WALLPAPER_PAGE_ZOOM, settings.wallpaperPageZoom)
            .putFloat(KEY_SURFACE_OPACITY, settings.surfaceOpacity)
            .putFloat(KEY_WALLPAPER_OFFSET_X, settings.wallpaperOffsetX)
            .putFloat(KEY_WALLPAPER_OFFSET_Y, settings.wallpaperOffsetY)
            .putFloat(KEY_WALLPAPER_USER_SCALE, settings.wallpaperUserScale)
            .putBoolean(KEY_NAVIGATION_BAR_BLUR, settings.navigationBarBlur)
            .putFloat(KEY_NAVIGATION_BAR_BLUR_INTENSITY, settings.navigationBarBlurIntensity)
            // Absent until the user picks: the screen-size default must keep applying.
            .also { editor ->
                val classic = settings.classicNavigationBar
                if (classic != null) {
                    editor.putBoolean(KEY_CLASSIC_NAVIGATION_BAR, classic)
                } else {
                    editor.remove(KEY_CLASSIC_NAVIGATION_BAR)
                }
                val small = settings.smallScreen
                if (small != null) {
                    editor.putBoolean(KEY_SMALL_SCREEN, small)
                } else {
                    editor.remove(KEY_SMALL_SCREEN)
                }
            }
            .apply()
    }

    /** Unknown stored values (renamed enums) fall back to the default instead of crashing. */
    private inline fun <reified T : Enum<T>> enumEntry(name: String?, default: T): T =
        name?.let { stored -> enumValues<T>().firstOrNull { it.name == stored } } ?: default

    /**
     * Whether the device is weak enough that the strip's continuous scroll would
     * stutter: the cutoff rides on RAM — the phones that never got past 4 GB
     * (an 821-class one) can't carry it, while anything an 855 shipped in starts
     * at 6.
     */
    private fun isWeakDevice(context: Context): Boolean {
        val info = ActivityManager.MemoryInfo()
        context.getSystemService(ActivityManager::class.java).getMemoryInfo(info)
        return info.totalMem < 6L * 1024 * 1024 * 1024
    }

    private const val KEY_THEME = "theme_mode"
    private const val KEY_ACCENT = "accent"
    private const val KEY_SLOW = "slow_animations"

    /** Legacy key of the old three-way animation speed enum, read once for the migration. */
    private const val KEY_SPEED = "animation_speed"
    /** Legacy key of the expressive-transitions toggle the simple one replaced; dropped on the next write. */
    private const val KEY_EXPRESSIVE_TRANSITIONS = "expressive_transitions"
    private const val KEY_GRAYSCALE_OFFLINE = "grayscale_offline"
    private const val KEY_HAPTIC = "haptic_feedback"
    private const val KEY_SIMPLE_TRANSITIONS = "simple_transitions"
    private const val KEY_HIDE_IP = "hide_ip"
    private const val KEY_FONT = "font"
    private const val KEY_FLAG_STYLE = "flag_style"
    private const val KEY_ICON_VARIANT = "icon_variant"
    private const val KEY_WALLPAPER_DIM = "wallpaper_dim"
    private const val KEY_WALLPAPER_DIM_SCREENS = "wallpaper_dim_screens"
    private const val KEY_WALLPAPER_BLUR = "wallpaper_blur"
    private const val KEY_WALLPAPER_DIM_TONE = "wallpaper_dim_tone"
    private const val KEY_WALLPAPER_TEXT_DARK = "wallpaper_text_dark"
    private const val KEY_WALLPAPER_PAGE_ZOOM = "wallpaper_page_zoom"
    private const val KEY_SURFACE_OPACITY = "surface_opacity"
    private const val KEY_WALLPAPER_OFFSET_X = "wallpaper_offset_x"
    private const val KEY_WALLPAPER_OFFSET_Y = "wallpaper_offset_y"
    private const val KEY_WALLPAPER_USER_SCALE = "wallpaper_user_scale"
    private const val KEY_NAVIGATION_BAR_BLUR = "navigation_bar_blur"
    private const val KEY_NAVIGATION_BAR_BLUR_INTENSITY = "navigation_bar_blur_intensity"
    private const val KEY_CLASSIC_NAVIGATION_BAR = "classic_navigation_bar"
    private const val KEY_SMALL_SCREEN = "small_screen"
}
