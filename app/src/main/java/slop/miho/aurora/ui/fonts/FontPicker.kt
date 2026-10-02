package slop.miho.aurora.ui.fonts

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import slop.miho.aurora.R
import slop.miho.aurora.data.FontRegistry
import slop.miho.aurora.ui.components.SwatchDivider
import slop.miho.aurora.ui.components.pressBounce
import slop.miho.aurora.ui.components.rememberHapticPoke
import java.io.File

/**
 * The font setting, picked right in its row: one square "Aa" tile per font, set in the font
 * itself — the system default, Inter, then the user's imports alphabetically — and the
 * stadium-shaped import button last. The supporting line names the active font, since the
 * tiles carry no names of their own.
 *
 * While the tiles fit beside the title they sit at the row's end like the theme buttons.
 * Once they would crowd the title, the title steps aside: the tiles take the row's full width
 * (scrolling sideways like the accent palette when they outgrow it) and the supporting line
 * moves under them.
 */
@Composable
fun FontPickerRow(
    selected: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var customFonts by remember { mutableStateOf(FontRegistry.customFonts(context)) }
    var importError by remember { mutableStateOf<String?>(null) }
    // The imported font a long press asked to delete, awaiting the dialog's answer.
    var pendingDelete by remember { mutableStateOf<File?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                // The copy is file I/O: off the main thread, then back for the state.
                val error = withContext(Dispatchers.IO) { FontRegistry.import(context, uri) }
                importError = error
                customFonts = withContext(Dispatchers.IO) { FontRegistry.customFonts(context) }
            }
        }
    }

    // A missing custom file falls back like the theme does — to the system default, which
    // then shows selected too.
    val active = when {
        selected == FontRegistry.SYSTEM_ID || selected == FontRegistry.INTER_ID -> selected
        customFonts.any { it.name == selected } -> selected
        else -> FontRegistry.SYSTEM_ID
    }
    val activeName = when (active) {
        FontRegistry.SYSTEM_ID -> stringResource(R.string.fonts_system)
        FontRegistry.INTER_ID -> stringResource(R.string.fonts_inter)
        else -> active.substringBeforeLast('.')
    }
    val title = stringResource(R.string.settings_fonts)

    pendingDelete?.let { file ->
        DeleteFontDialog(
            name = file.nameWithoutExtension,
            onConfirm = {
                pendingDelete = null
                // The font in use falls back to the system default before its file goes.
                if (active == file.name) onSelect(FontRegistry.SYSTEM_ID)
                importError = null
                scope.launch {
                    customFonts = withContext(Dispatchers.IO) {
                        FontRegistry.delete(context, file.name)
                        FontRegistry.customFonts(context)
                    }
                }
            },
            onDismiss = { pendingDelete = null },
        )
    }

    val tiles: @Composable () -> Unit = {
        FontTile(
            family = null,
            label = stringResource(R.string.fonts_system),
            selected = active == FontRegistry.SYSTEM_ID,
            onClick = {
                importError = null
                onSelect(FontRegistry.SYSTEM_ID)
            },
        )
        FontTile(
            family = FontRegistry.inter,
            label = stringResource(R.string.fonts_inter),
            selected = active == FontRegistry.INTER_ID,
            onClick = {
                importError = null
                onSelect(FontRegistry.INTER_ID)
            },
        )
        // The user's own fonts sit apart behind the palette's strip; with none, no strip.
        if (customFonts.isNotEmpty()) SwatchDivider()
        customFonts.forEach { file ->
            // Keyed by file, so a deletion doesn't hand one tile's press state to the next.
            key(file.name) {
                CustomFontTile(
                    file = file,
                    selected = active == file.name,
                    onClick = {
                        importError = null
                        onSelect(file.name)
                    },
                    // Long press offers to delete it (the user's own fonts only).
                    onLongClick = { pendingDelete = file },
                )
            }
        }
        AddFontButton(onClick = { picker.launch(FONT_MIME_TYPES) })
    }

    // The supporting line: the active font, or a failed import in its place until the next pick.
    val supporting: @Composable (Modifier) -> Unit = { lineModifier ->
        val error = importError
        Text(
            text = if (error != null) stringResource(R.string.fonts_import_failed, error) else activeName,
            style = MaterialTheme.typography.bodyMedium,
            color = if (error != null) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = lineModifier,
        )
    }

    BoxWithConstraints(modifier.fillMaxWidth()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val titleStyle = MaterialTheme.typography.bodyLarge
        val titleWidth = remember(title, titleStyle, density) {
            with(density) { measurer.measure(title, titleStyle).size.width.toDp() }
        }
        // Everything the inline layout needs on one line: the padding either side, the
        // title, the gap after it and the whole run of tiles.
        val inlineWidth = RowPadding * 2 + titleWidth + TitleGap + tilesWidth(customFonts.size)
        if (inlineWidth <= maxWidth) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(RowPadding),
                horizontalArrangement = Arrangement.spacedBy(TitleGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = titleStyle,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    supporting(Modifier)
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tiles()
                }
            }
        } else {
            Column(Modifier.fillMaxWidth()) {
                // The scroll runs the card's full width with the padding inside the scrolled
                // content, as in the accent palette: the tiles slide under the card's rounding
                // instead of stopping short of it.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(start = RowPadding, end = RowPadding, top = RowPadding, bottom = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(TileGap),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    tiles()
                }
                supporting(
                    Modifier.padding(start = RowPadding, end = RowPadding, bottom = RowPadding),
                )
            }
        }
    }
}

/** An imported font's tile; the family is loaded once per file, not on every recomposition. */
@Composable
private fun CustomFontTile(
    file: File,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    val family = remember(file) { FontFamily(Font(file)) }
    FontTile(
        family = family,
        label = file.nameWithoutExtension,
        selected = selected,
        onClick = onClick,
        onLongClick = onLongClick,
    )
}

/**
 * A font's square tile: "Aa" set in the font, on the same raised tonal container as the
 * settings' icon buttons, taking the accent container while selected. Pressing squashes it
 * and dips its tone; every click pops it back. [onLongClick], when given, answers a long
 * press with the same poke.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun FontTile(
    family: FontFamily?,
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the pop keys off it, so it fires on every click — the
    // press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    val resting = if (selected) colors.primary else colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "font tile container",
    )
    val content by animateColorAsState(
        targetValue = if (selected) colors.onPrimary else colors.onSecondaryContainer,
        animationSpec = motion.defaultEffectsSpec(),
        label = "font tile content",
    )
    // Surface's own onClick has no long press, so the tile clicks through combinedClickable
    // like the device action buttons do.
    Surface(
        shape = TileShape,
        color = container,
        contentColor = content,
        modifier = Modifier
            .size(TileSize)
            .pressBounce(pressed, taps)
            .clip(TileShape)
            .combinedClickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = {
                    poke()
                    taps++
                    onClick()
                },
                onLongClick = onLongClick?.let { longClick ->
                    {
                        poke()
                        taps++
                        longClick()
                    }
                },
            )
            // No name on the tile itself; screen readers still hear which font it is.
            .semantics { contentDescription = label },
    ) {
        Box(contentAlignment = Alignment.Center) {
            Text(
                text = "Aa",
                style = MaterialTheme.typography.titleMedium,
                fontFamily = family,
            )
        }
    }
}

/** Asks before an imported font goes, in the same shape as the device deletion dialog. */
@Composable
private fun DeleteFontDialog(name: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = {
            Icon(
                painter = painterResource(R.drawable.ic_delete),
                contentDescription = null,
                tint = colors.error,
            )
        },
        title = { Text(stringResource(R.string.fonts_delete_title)) },
        text = { Text(stringResource(R.string.fonts_delete_text, name)) },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
            ) {
                Text(stringResource(R.string.fonts_delete_confirm))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.fonts_cancel))
            }
        },
    )
}

/**
 * The import button: as tall as the tiles but narrower, rounded all the way into an upright
 * stadium (a rectangle with fully round ends, not an oval), on the tiles' tonal container.
 */
@Composable
private fun AddFontButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    var taps by remember { mutableIntStateOf(0) }
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(resting, colors.onSurface, 0.1f) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "add font container",
    )
    Surface(
        onClick = {
            poke()
            taps++
            onClick()
        },
        interactionSource = interactionSource,
        shape = CircleShape,
        color = container,
        contentColor = colors.onSecondaryContainer,
        modifier = Modifier
            .size(width = AddButtonWidth, height = AddButtonHeight)
            .pressBounce(pressed, taps),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                painter = painterResource(R.drawable.ic_add),
                contentDescription = stringResource(R.string.fonts_add),
                modifier = Modifier.size(24.dp),
            )
        }
    }
}

/**
 * The whole run: the two built-in tiles, the strip and [customCount] imported tiles when
 * there are any, and the import button, gaps included.
 */
private fun tilesWidth(customCount: Int): Dp {
    val fonts = 2 + customCount
    val divider = if (customCount > 0) DividerWidth + TileGap else 0.dp
    return (TileSize + TileGap) * fonts + divider + AddButtonWidth
}

private val RowPadding = 16.dp
private val TitleGap = 16.dp
private val TileSize = 48.dp
private val TileShape = RoundedCornerShape(16.dp)
private val TileGap = 12.dp
/** The palette strip's own width (see [SwatchDivider]). */
private val DividerWidth = 3.dp
private val AddButtonWidth = 36.dp
private val AddButtonHeight = TileSize

/** Font types offered by the system file picker; the catch-all covers unlabeled TTFs. */
private val FONT_MIME_TYPES = arrayOf(
    "font/ttf",
    "font/otf",
    "font/sfnt",
    "application/x-font-ttf",
    "application/x-font-otf",
    "application/vnd.ms-opentype",
    "application/octet-stream",
)
