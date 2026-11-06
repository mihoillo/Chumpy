package slop.miho.chumpy.ui.components

import androidx.collection.mutableIntListOf
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.updateTransition
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuPopup
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorPosition
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.PopupProperties

/**
 * The stock dropdown menu, pinned to the card it answers to: centered on the row's
 * width, a breath of air below it — or the same breath above, when the bottom has no
 * room. Everything about it is the menu's own stock machinery — the popup's scrim and
 * dismiss, the menu tokens' colors and shadow, the settle's scale-and-fade — only the
 * stance is ours: the rows this app deals in are wide cards, and a menu hanging off a
 * card's corner would read as a stranger's, so it steps out from under the card's
 * middle instead. The shape is the app's own extraLarge too — the stock token ships
 * near-square corners, strangers to a screen of round cards.
 *
 * This material3 build splits the classic DropdownMenu in two and hides the better
 * half: [DropdownMenuPopup] is the bare popup — it composes whatever it is handed,
 * painted by nobody — while the stock surface (DropdownMenuContent) stays `internal`.
 * So the surface is laid here by hand, out of the menu's own public defaults, and
 * driven by a transition state we own: the state keeps the popup standing through
 * its own exit, and the popup falls with the animation's last frame.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CardMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardPopup(
        expanded = expanded,
        onDismissRequest = onDismissRequest,
        // Below the row first, above it when below has no room.
        aboveFirst = false,
        settle = true,
        content = content,
    )
}

/**
 * The question over the card: the same surface the menu wears, riding above the row
 * the menu hangs from — a note and nothing more. No focus, no scrim, no touch: the
 * tap belongs to the menu below, and this only says what the tap would mean.
 */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun CardCallout(
    expanded: Boolean,
    content: @Composable ColumnScope.() -> Unit,
) {
    CardPopup(
        expanded = expanded,
        onDismissRequest = {},
        // Over the card, as an ask's label belongs; below only when above has no room.
        aboveFirst = true,
        settle = false,
        properties = PopupProperties(focusable = false),
        content = content,
    )
}

/** The two popups' shared bones: the anchor, the gate, the surface, the fade. */
@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
private fun CardPopup(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    aboveFirst: Boolean,
    settle: Boolean,
    properties: PopupProperties = MenuDefaults.DefaultMenuProperties,
    content: @Composable ColumnScope.() -> Unit,
) {
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    val gap = with(density) { MenuGap.roundToPx() }
    val anchor = remember(gap, aboveFirst) {
        MenuAnchorPosition.Custom(
            // The one horizontal candidate: the row's own middle. Off-screen rows
            // (the list scrolls) let the provider clamp the candidate into the window.
            xCandidates = { mutableIntListOf(anchorBounds.center.x - menuSize.width / 2) },
            // Above-first flips the order for the callout; neither fits and the
            // provider nudges the last candidate inside the window whole.
            yCandidates = {
                val below = anchorBounds.bottom + gap
                val above = anchorBounds.top - gap - menuSize.height
                mutableIntListOf(if (aboveFirst) above else below, if (aboveFirst) below else above)
            },
        )
    }
    val positionProvider = MenuDefaults.rememberDropdownMenuPopupPositionProvider(anchor)

    // The settle: the stock menu's own scale-and-fade (1↔0.8 with the fade), pivoted
    // on the point the position provider reports. Reading the state through the
    // transition is also what carries it: currentState follows the animation, so the
    // gate below holds the popup open until the way out is fully walked. The callout
    // keeps only the fade — a note does not spring out of a card, it appears.
    val expandedState = remember { MutableTransitionState(false) }
    expandedState.targetState = expanded
    val settleIn = updateTransition(expandedState, label = "card popup")
    val alpha by settleIn.animateFloat(
        transitionSpec = { motion.defaultEffectsSpec() },
        label = "card popup alpha",
    ) { if (it) 1f else 0f }
    val scale = if (settle) {
        settleIn.animateFloat(
            transitionSpec = { motion.defaultSpatialSpec() },
            label = "card popup scale",
        ) { if (it) 1f else 0.8f }
    } else {
        null
    }

    if (expandedState.currentState || expandedState.targetState) {
        DropdownMenuPopup(
            // Always true under the gate: the popup's own expanded is only whether to
            // stand at all, and that is the gate's decision, not the caller's flag.
            expanded = true,
            onDismissRequest = onDismissRequest,
            popupPositionProvider = positionProvider,
            properties = properties,
        ) {
            // The stock surface, by hand: the menu tokens' own container color and
            // shadow over the app's card shape, the stock column's 8dp of vertical
            // breathing room — the recipe DropdownMenuContent keeps to itself in
            // this build.
            Surface(
                shape = MaterialTheme.shapes.extraLarge,
                color = MenuDefaults.containerColor,
                tonalElevation = MenuDefaults.TonalElevation,
                shadowElevation = MenuDefaults.ShadowElevation,
                modifier = Modifier.graphicsLayer {
                    scaleX = scale?.value ?: 1f
                    scaleY = scale?.value ?: 1f
                    this.alpha = alpha
                    transformOrigin = positionProvider.transformOrigin
                },
            ) {
                Column(
                    modifier = Modifier.padding(vertical = MenuVerticalPadding),
                ) {
                    content()
                }
            }
        }
    }
}

/** The breath of air between a card and its popup. */
private val MenuGap = 8.dp

/** The stock menu column's own vertical breathing room. */
private val MenuVerticalPadding = 8.dp
