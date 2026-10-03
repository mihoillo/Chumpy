package slop.miho.aurora.ui.components

import androidx.compose.animation.Crossfade
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import slop.miho.aurora.R

/**
 * The subscription URL's own field-and-tile pair, the welcome screen's way in: the bare
 * field — no outline and no fill of its own, whatever backdrop it sits on is the outline —
 * and the 48dp tile in the switch's seat. The tile is a paste while the field is empty,
 * the clipboard going in on tap, and the arrow to go once it holds something. The
 * welcome screen pins it to the bottom of the page; the servers screen's add row trades
 * its invitation face for it. [onClose], when given, adds the compact card's tight pair:
 * the update button's own stadium — a cross, the way back — riding right against the
 * tile the way the reload rides against the database tile.
 */
@Composable
fun SubmitFieldRow(
    value: String,
    onValueChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    /** Takes the focus on entering the composition — the add row's opening move. */
    autofocus: Boolean = false,
    /** Folds the row back — draws the cross beside the submit tile when set. */
    onClose: (() -> Unit)? = null,
) {
    val clipboard = LocalClipboardManager.current
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val closeInteraction = remember { MutableInteractionSource() }
    val closePressed by closeInteraction.collectIsPressedAsState()
    val poke = rememberHapticPoke()
    // Bumped in the click handler: the tile's pop keys off it, so it fires on every
    // click — the press state alone can go unnoticed on a quick tap.
    var taps by remember { mutableIntStateOf(0) }
    var closeTaps by remember { mutableIntStateOf(0) }
    val ready = value.isNotBlank()
    // The tile trades the paste for the arrow the moment the field fills or empties —
    // the trade bounces too, the same pop as a click. Keyed off the flip and merged
    // into the taps counter, so a click that also flips the state bounces once.
    var flips by remember { mutableIntStateOf(0) }
    var wasReady by remember { mutableStateOf(ready) }
    LaunchedEffect(ready) {
        if (ready != wasReady) {
            wasReady = ready
            flips++
        }
    }
    val focusRequester = remember { FocusRequester() }
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // The name rides as a placeholder — it holds the empty field's center and
            // steps aside for the text, instead of floating above it.
            placeholder = { Text(stringResource(R.string.subscription_url_field)) },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Done,
            ),
            keyboardActions = KeyboardActions(onDone = { onSubmit() }),
            // No outline and no fill of its own: the backdrop is the field's.
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color.Transparent,
                unfocusedBorderColor = Color.Transparent,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier
                .weight(1f)
                .focusRequester(focusRequester),
        )
        val colors = MaterialTheme.colorScheme
        val motion = MaterialTheme.motionScheme
        // The tile with the close tight against it — the pair reads as one control, the
        // compact card's database tile and reload the same way.
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = if (ready) colors.primary else colors.secondaryContainer,
                contentColor = if (ready) colors.onPrimary else colors.onSecondaryContainer,
                onClick = {
                    poke()
                    taps++
                    if (ready) {
                        onSubmit()
                    } else {
                        // Nothing to carry yet — the tile's own job: put the clipboard in.
                        clipboard.getText()?.text?.trim()?.let { onValueChange(it) }
                    }
                },
                modifier = Modifier
                    .size(48.dp)
                    .pressBounce(pressed, taps + flips),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Crossfade(
                        targetState = ready,
                        animationSpec = motion.defaultEffectsSpec(),
                        label = "submit tile icon",
                    ) { hasText ->
                        Icon(
                            painter = painterResource(
                                if (hasText) R.drawable.ic_arrow_forward else R.drawable.ic_content_paste,
                            ),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
            if (onClose != null) {
                // The cross: the update button's own anatomy — the stadium, the pressed
                // tint, the bounce — the way back from the row's working face.
                val resting = colors.secondaryContainer
                val closeContainer by animateColorAsState(
                    targetValue = if (closePressed) lerp(resting, colors.onSurface, 0.1f) else resting,
                    animationSpec = motion.defaultEffectsSpec(),
                    label = "submit close container",
                )
                Surface(
                    onClick = {
                        poke()
                        closeTaps++
                        onClose()
                    },
                    interactionSource = closeInteraction,
                    shape = CircleShape,
                    color = closeContainer,
                    contentColor = colors.onSecondaryContainer,
                    modifier = Modifier
                        .size(width = 36.dp, height = 48.dp)
                        .pressBounce(closePressed, closeTaps),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            painter = painterResource(R.drawable.ic_close),
                            contentDescription = stringResource(R.string.subscription_cancel),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
        }
    }
    LaunchedEffect(autofocus) {
        if (autofocus) focusRequester.requestFocus()
    }
}
