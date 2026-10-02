package slop.miho.aurora.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import slop.miho.aurora.R

/** Mozilla's COLR build of Twemoji; its GSUB turns regional-indicator pairs into flags. */
private val TwemojiFamily = FontFamily(Font(R.font.twemoji))

/**
 * The emoji flag of an ISO 3166-1 alpha-2 code ("FI" → 🇫🇮): each letter maps to its
 * regional indicator symbol, the same conversion the panel's web page does.
 */
fun countryFlagEmoji(code: String): String = buildString {
    code.forEach { letter -> appendCodePoint(REGIONAL_INDICATOR_A + (letter - 'A')) }
}

/**
 * A country's flag drawn as emoji, centered in a [size] box. [twemoji] picks the bundled
 * Twemoji font over the system's emoji. The glyph is sized in dp, not sp: it stands in for
 * an icon, so the user's font scale shouldn't swell it past its backing.
 */
@Composable
fun CountryFlag(
    code: String,
    twemoji: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    FlagEmoji(countryFlagEmoji(code), twemoji, modifier, size)
}

/**
 * Any flag emoji string — the leading flag parsed off a profile's name — drawn like
 * [CountryFlag] draws a code's flag: centered in a [size] box, Twemoji or the system's.
 */
@Composable
fun EmojiFlag(
    emoji: String,
    twemoji: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    FlagEmoji(emoji, twemoji, modifier, size)
}

/** The rainbow flag (🏳️‍🌈): the white flag, VS16 and a rainbow joined by a ZWJ. */
private const val RAINBOW_FLAG_EMOJI = "🏳️‍🌈"

/** The trans flag (🏳️‍⚧️): the white flag, VS16 and the trans symbol joined by a ZWJ. */
private const val TRANS_FLAG_EMOJI = "🏳️‍⚧️"

/**
 * The pride flags a profile's name can lead with, and the easter egg they trip: the row
 * drifts through the rainbow, the [PrideFlag.RAINBOW] accent's slow cycle in miniature.
 */
enum class PrideFlag {
    RAINBOW,
    TRANS,
}

/**
 * Splits a leading pride flag off a profile's name, [splitLeadingFlag]'s sibling for the
 * emoji panels sometimes put there instead of (or before) a country's flag. Returns the
 * pride flag and the rest of the name, trimmed; a name without one keeps a null flag.
 */
fun splitLeadingPrideFlag(name: String): Pair<PrideFlag?, String> {
    val trimmed = name.trimStart()
    return when {
        trimmed.startsWith(RAINBOW_FLAG_EMOJI) ->
            PrideFlag.RAINBOW to trimmed.substring(RAINBOW_FLAG_EMOJI.length).trimStart()

        trimmed.startsWith(TRANS_FLAG_EMOJI) ->
            PrideFlag.TRANS to trimmed.substring(TRANS_FLAG_EMOJI.length).trimStart()

        else -> null to name
    }
}

/**
 * The pride flag itself in an icon slot: the rainbow gets Twemoji's own drawable, the
 * trans one rides the system emoji (the Twemoji font has no layered glyph for it).
 */
@Composable
fun PrideFlagIcon(
    pride: PrideFlag,
    twemoji: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    when (pride) {
        PrideFlag.RAINBOW -> RainbowFlag(twemoji, modifier, size)
        PrideFlag.TRANS -> FlagEmoji(TRANS_FLAG_EMOJI, twemoji = false, modifier, size)
    }
}

/**
 * The rainbow flag, drawn like a [CountryFlag]. The system emoji take the text; Twemoji's
 * comes from a drawable of the font's own layers instead — the font has the glyph, but text
 * layout hands any VS16 sequence to the system emoji font, so the text would never reach it.
 * The drawable is sized like the glyph would be at a [size] box.
 */
@Composable
fun RainbowFlag(
    twemoji: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    if (!twemoji) {
        FlagEmoji(RAINBOW_FLAG_EMOJI, twemoji = false, modifier, size)
        return
    }
    val width = size * FLAG_GLYPH_SCALE
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Image(
            painter = painterResource(R.drawable.ic_flag_rainbow_twemoji),
            contentDescription = null,
            modifier = Modifier.size(width, width * RAINBOW_FLAG_ASPECT),
        )
    }
}

/** The Twemoji flag glyph's height over its width, in font units. */
private const val RAINBOW_FLAG_ASPECT = 370f / 512f

/**
 * Splits the flag emoji a panel put at the front of a profile's name off the name itself:
 * clients lift it into the icon slot and show the name without it. Returns the flag (all
 * leading ones — a run of regional-indicator pairs) and the rest of the name, trimmed; a
 * name without a leading flag comes back whole with a null flag.
 */
fun splitLeadingFlag(name: String): Pair<String?, String> {
    var end = 0
    val trimmed = name.trimStart()
    while (end + 1 < trimmed.length &&
        trimmed[end] == FLAG_HIGH_SURROGATE &&
        trimmed[end + 1] in FLAG_LOW_SURROGATE_START..FLAG_LOW_SURROGATE_END
    ) {
        end += 2
    }
    if (end == 0) return null to name
    return trimmed.take(end) to trimmed.substring(end).trimStart()
}

/** The regional indicators (U+1F1E6..U+1F1FF) as a UTF-16 surrogate pair. */
private const val FLAG_HIGH_SURROGATE = '\uD83C'
private const val FLAG_LOW_SURROGATE_START = '\uDDE6'
private const val FLAG_LOW_SURROGATE_END = '\uDDFF'

/** A flag [emoji] drawn as text, centered in a [size] box. */
@Composable
private fun FlagEmoji(
    emoji: String,
    twemoji: Boolean,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
) {
    val fontSize = with(LocalDensity.current) { (size * FLAG_GLYPH_SCALE).toSp() }
    Box(modifier = modifier.size(size), contentAlignment = Alignment.Center) {
        Text(
            text = emoji,
            style = TextStyle(
                fontFamily = if (twemoji) TwemojiFamily else null,
                fontSize = fontSize,
                lineHeight = fontSize,
                textAlign = TextAlign.Center,
                lineHeightStyle = LineHeightStyle(
                    alignment = LineHeightStyle.Alignment.Center,
                    trim = LineHeightStyle.Trim.Both,
                ),
            ),
            maxLines = 1,
            softWrap = false,
        )
    }
}

private const val REGIONAL_INDICATOR_A = 0x1F1E6

/** Emoji glyphs run a touch wider than their em; this keeps the flag inside the icon's box. */
private const val FLAG_GLYPH_SCALE = 0.85f
