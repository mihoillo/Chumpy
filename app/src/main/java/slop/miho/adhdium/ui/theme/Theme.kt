package slop.miho.adhdium.ui.theme

import android.content.Context
import android.os.Build
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import slop.miho.adhdium.data.Accent
import slop.miho.adhdium.data.FontRegistry
import slop.miho.adhdium.data.ThemeMode
import slop.miho.adhdium.data.UiSettings
import slop.miho.adhdium.data.WallpaperPalette
import java.io.File

/**
 * The app's UI settings, exposed app-wide so any control can honor them — the haptic gate
 * and the slow-motion factor live outside the settings screen. Provided by [AdhdiumTheme];
 * changes re-compose everything beneath, which a theme change does anyway.
 */
val LocalUiSettings = staticCompositionLocalOf { UiSettings() }

/**
 * The theme the app is actually in — its own toggle, which may disagree with the system's
 * dark mode. The color scheme already answers to it implicitly; the system bars' icon color
 * needs it spelled out (the stock edge-to-edge setup only listens to the system one).
 */
val LocalDarkTheme = staticCompositionLocalOf { false }

@Composable
fun AdhdiumTheme(
    settings: UiSettings,
    content: @Composable () -> Unit,
) {
    val darkTheme = when (settings.themeMode) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK, ThemeMode.AMOLED -> true
    }
    val context = LocalContext.current
    // The wallpaper seed accents re-theme with the picture; the flow seeds itself on first
    // read, so the palette is ready wherever the theme is first composed.
    val monetHues by WallpaperPalette.hues(context).collectAsStateWithLifecycle()
    val motion = scaledMotionScheme(settings.slowAnimations)
    // Only the rainbow accent runs the clock; every other accent leaves it at rest.
    val rainbowHue = if (settings.accent == Accent.RAINBOW) rainbowHue() else 0f
    val colorScheme = themeColorScheme(settings, monetHues, rainbowHue, darkTheme, context)
    CompositionLocalProvider(
        LocalUiSettings provides settings,
        LocalDarkTheme provides darkTheme,
        LocalRainbowHue provides rainbowHue,
    ) {
        MaterialExpressiveTheme(
            colorScheme = colorScheme,
            motionScheme = motion,
            // Null for the system font: the theme's untouched stock typography.
            typography = appFontFamily(settings.fontId)?.let { Typography(fontFamily = it) },
            content = content,
        )
    }
}

/**
 * The color scheme the app would wear in the given theme with the active accent — the
 * reference for anything that must read over a dimmed wallpaper regardless of the theme the
 * user is in: bare-on-wallpaper text picks the dark scheme's roles, and both scrim tones
 * are the schemes' own surfaces — never a flat black or white.
 */
@Composable
fun backdropScheme(darkTheme: Boolean): ColorScheme {
    val settings = LocalUiSettings.current
    val context = LocalContext.current
    val monetHues by WallpaperPalette.hues(context).collectAsStateWithLifecycle()
    return themeColorScheme(settings, monetHues, LocalRainbowHue.current, darkTheme, context)
}

/** The rainbow accent's current hue, provided by [AdhdiumTheme] for [backdropScheme]. */
private val LocalRainbowHue = staticCompositionLocalOf { 0f }

/** One full trip of the rainbow accent around the color wheel. */
private const val RAINBOW_CYCLE_MS = 12_000

/**
 * The rainbow accent's hue, cycling forever around the wheel. It steps in whole degrees:
 * a new scheme recomposes the whole app, so the theme follows 30 steps a second rather than
 * every frame — a degree of hue is too small a jump to see.
 */
@Composable
private fun rainbowHue(): Float {
    val transition = rememberInfiniteTransition(label = "rainbow")
    val hue = transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(RAINBOW_CYCLE_MS, easing = LinearEasing)),
        label = "rainbow hue",
    )
    val step by remember { derivedStateOf { hue.value.toInt() % 360 } }
    return step.toFloat()
}

/**
 * The scheme for [settings]' accent in the given theme; AMOLED darkens whatever the accent
 * built when it is the dark side that is asked for.
 */
private fun themeColorScheme(
    settings: UiSettings,
    monetHues: List<Float>?,
    rainbowHue: Float,
    darkTheme: Boolean,
    context: Context,
): ColorScheme {
    val scheme = when {
        settings.accent == Accent.ADAPTIVE && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        settings.accent == Accent.MONO -> monoColorScheme(darkTheme)
        settings.accent == Accent.RAINBOW ->
            (if (darkTheme) darkColorScheme() else lightColorScheme()).withHue(rainbowHue)
        else -> accentColorScheme(settings.accent, monetHues, darkTheme)
    }
    return if (darkTheme && settings.themeMode == ThemeMode.AMOLED) scheme.amoled() else scheme
}

/**
 * The AMOLED take on a dark scheme: the background and the lowest surfaces go pure black,
 * the containers above them sink halfway there — still tinted by the accent, still stacked
 * in the same order, just deeper.
 */
private fun ColorScheme.amoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = lerp(surfaceContainerLow, Color.Black, 0.5f),
    surfaceContainer = lerp(surfaceContainer, Color.Black, 0.5f),
    surfaceContainerHigh = lerp(surfaceContainerHigh, Color.Black, 0.5f),
    surfaceContainerHighest = lerp(surfaceContainerHighest, Color.Black, 0.5f),
    surfaceBright = lerp(surfaceBright, Color.Black, 0.5f),
    surfaceVariant = lerp(surfaceVariant, Color.Black, 0.3f),
)

/**
 * The family of the selected font, or null for the system default. Custom fonts load
 * straight from their imported file; a font id whose file is gone falls back to the system
 * font rather than crashing the theme.
 */
@Composable
private fun appFontFamily(fontId: String): FontFamily? = when {
    fontId == FontRegistry.SYSTEM_ID -> null
    fontId == FontRegistry.INTER_ID -> FontRegistry.inter
    else -> {
        val context = LocalContext.current
        val file: File? = remember(fontId) { FontRegistry.customFontFile(context, fontId) }
        file?.let { remember(file) { FontFamily(Font(it)) } }
    }
}

/**
 * A full color scheme for a fixed accent hue, built from the baseline M3 scheme by re-hueing
 * every tone. Re-hueing keeps each color's saturation and lightness, so contrast pairs stay
 * contrast pairs — an approximation of a seeded tonal palette without a color library. The
 * wallpaper seeds take their hue from [monetHues]; without a wallpaper behind them — or on
 * devices without Material You — the sky scheme is the default.
 */
private fun accentColorScheme(accent: Accent, monetHues: List<Float>?, darkTheme: Boolean): ColorScheme {
    val hue = when {
        accent == Accent.ADAPTIVE -> Accent.SKY.hue
        accent.fromWallpaper -> monetHues?.getOrNull(accent.seedIndex) ?: Accent.SKY.hue
        else -> accent.hue
    }
    val base = if (darkTheme) darkColorScheme() else lightColorScheme()
    return base.withHue(hue)
}

/**
 * Every role except the error family and scrim, passed through [transform]. The error colors
 * stay red regardless of the accent, and scrim is a plain alpha black.
 */
private fun ColorScheme.mapped(transform: (Color) -> Color): ColorScheme = copy(
    primary = transform(primary),
    onPrimary = transform(onPrimary),
    primaryContainer = transform(primaryContainer),
    onPrimaryContainer = transform(onPrimaryContainer),
    inversePrimary = transform(inversePrimary),
    secondary = transform(secondary),
    onSecondary = transform(onSecondary),
    secondaryContainer = transform(secondaryContainer),
    onSecondaryContainer = transform(onSecondaryContainer),
    tertiary = transform(tertiary),
    onTertiary = transform(onTertiary),
    tertiaryContainer = transform(tertiaryContainer),
    onTertiaryContainer = transform(onTertiaryContainer),
    background = transform(background),
    onBackground = transform(onBackground),
    surface = transform(surface),
    onSurface = transform(onSurface),
    surfaceVariant = transform(surfaceVariant),
    onSurfaceVariant = transform(onSurfaceVariant),
    surfaceTint = transform(surfaceTint),
    inverseSurface = transform(inverseSurface),
    inverseOnSurface = transform(inverseOnSurface),
    outline = transform(outline),
    outlineVariant = transform(outlineVariant),
    surfaceBright = transform(surfaceBright),
    surfaceDim = transform(surfaceDim),
    surfaceContainer = transform(surfaceContainer),
    surfaceContainerHigh = transform(surfaceContainerHigh),
    surfaceContainerHighest = transform(surfaceContainerHighest),
    surfaceContainerLow = transform(surfaceContainerLow),
    surfaceContainerLowest = transform(surfaceContainerLowest),
)

private fun ColorScheme.withHue(hue: Float): ColorScheme = mapped { it.reHued(hue) }

/**
 * The black-and-white accent: every role desaturated to a neutral gray, with the accent
 * itself the theme's opposite — the dark scheme's ink in light theme, the light scheme's
 * paper in dark — instead of a raw black or white, so the accent still belongs to the
 * surface it sits on and reads against it. The primary container keeps the theme's own
 * polarity (dark on light, light on dark) so selected states stay distinct from the
 * neutral surfaces — a tonal container would vanish among them.
 */
private fun monoColorScheme(darkTheme: Boolean): ColorScheme {
    val base = (if (darkTheme) darkColorScheme() else lightColorScheme()).mapped { it.desaturated() }
    // The opposite theme's background is the accent: ink on paper, paper on ink — still
    // each scheme's own tone, not pure white or black.
    val opposite = (if (darkTheme) lightColorScheme() else darkColorScheme()).background
    return base.copy(
        primary = opposite,
        onPrimary = base.background,
        primaryContainer = if (darkTheme) Color(0xFFE6E1E9) else Color(0xFF49454F),
        onPrimaryContainer = if (darkTheme) Color(0xFF1D1B20) else Color.White,
    )
}

/** Re-hue in HSL, keeping saturation and lightness; grays are left untouched. */
private fun Color.reHued(hue: Float): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    if (hsl[1] < 0.02f) return this
    hsl[0] = hue
    return Color(ColorUtils.HSLToColor(hsl))
}

/** Drop the saturation, keeping hue and lightness — the neutral version of the color. */
private fun Color.desaturated(): Color {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(toArgb(), hsl)
    hsl[1] = 0f
    return Color(ColorUtils.HSLToColor(hsl))
}

/**
 * The expressive MD3 motion scheme, optionally slowed: off is the untouched built-in scheme,
 * on rebuilds the same springs with halved stiffness, stretching every animated component
 * app-wide.
 */
private fun scaledMotionScheme(slow: Boolean): MotionScheme =
    if (!slow) {
        MotionScheme.expressive()
    } else {
        ScaledExpressiveMotionScheme(0.5f)
    }

/**
 * Expressive motion token values (material3's internal ExpressiveMotionTokens, v0_103),
 * replicated here because the tokens themselves are not public API.
 */
private class ScaledExpressiveMotionScheme(private val factor: Float) : MotionScheme {
    private val defaultSpatialSpec = spring<Any>(
        dampingRatio = 0.8f,
        stiffness = 380f * factor,
    )
    private val fastSpatialSpec = spring<Any>(
        dampingRatio = 0.6f,
        stiffness = 800f * factor,
    )
    private val slowSpatialSpec = spring<Any>(
        dampingRatio = 0.8f,
        stiffness = 200f * factor,
    )
    private val defaultEffectsSpec = spring<Any>(
        dampingRatio = 1f,
        stiffness = 1600f * factor,
    )
    private val fastEffectsSpec = spring<Any>(
        dampingRatio = 1f,
        stiffness = 3800f * factor,
    )
    private val slowEffectsSpec = spring<Any>(
        dampingRatio = 1f,
        stiffness = 800f * factor,
    )

    @Suppress("UNCHECKED_CAST")
    override fun <T> defaultSpatialSpec(): FiniteAnimationSpec<T> =
        defaultSpatialSpec as FiniteAnimationSpec<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T> fastSpatialSpec(): FiniteAnimationSpec<T> =
        fastSpatialSpec as FiniteAnimationSpec<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T> slowSpatialSpec(): FiniteAnimationSpec<T> =
        slowSpatialSpec as FiniteAnimationSpec<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T> defaultEffectsSpec(): FiniteAnimationSpec<T> =
        defaultEffectsSpec as FiniteAnimationSpec<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T> fastEffectsSpec(): FiniteAnimationSpec<T> =
        fastEffectsSpec as FiniteAnimationSpec<T>

    @Suppress("UNCHECKED_CAST")
    override fun <T> slowEffectsSpec(): FiniteAnimationSpec<T> =
        slowEffectsSpec as FiniteAnimationSpec<T>
}
