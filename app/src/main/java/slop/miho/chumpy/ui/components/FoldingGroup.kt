package slop.miho.chumpy.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CornerSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asComposePath
import androidx.graphics.shapes.CornerRounding
import androidx.graphics.shapes.RoundedPolygon
import androidx.graphics.shapes.toPath
import slop.miho.chumpy.ui.wallpapers.withSurfaceOpacity

/**
 * A settings card with one fold built in — the header rows stand, the rows under them
 * unroll and roll back up — because the whole thing is one card, not a doorway group
 * with a second one hanging under it. The seam the fold rides on is the header's own
 * bottom corners: the card's outer ones while the rows are folded, the inner seam once
 * they stand under it, animating on the same clock the rows themselves unroll on.
 */
@Composable
fun FoldingGroup(
    expanded: Boolean,
    headerRows: List<@Composable () -> Unit>,
    rows: List<@Composable () -> Unit>,
) {
    val outer = MaterialTheme.shapes.extraLarge
    val inner = MaterialTheme.shapes.extraSmall
    val motion = MaterialTheme.motionScheme
    val density = LocalDensity.current
    // The corner radii come out of the theme's shapes as px against this density, back
    // into Dp — the fold animates that Dp, and a shape rebuilt around a new Dp is a new
    // shape, so the outline follows the animation frame by frame. (Dp corners ignore
    // the shape size they are handed, which is all the theme's shapes are made of.)
    fun CornerSize.dp(): Dp = with(density) { toPx(Size(1f, 1f), this).toDp() }
    val outerTop = outer.topStart.dp()
    val outerBottom = outer.bottomStart.dp()
    val innerTop = inner.topStart.dp()
    val innerBottom = inner.bottomStart.dp()
    // The seam the fold rides on is the header's last row's bottom corners: the card's
    // outer ones while the rows are folded, the inner seam once they stand under it.
    val headerBottom by animateDpAsState(
        targetValue = if (expanded) innerBottom else outerBottom,
        animationSpec = motion.defaultSpatialSpec(),
        label = "folding header bottom corner",
    )
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(outer),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // The header's own rows ride as surfaces of their own, separated by the same
        // 2dp seams the fold's rows wear — the first keeps the card's outer top, the
        // last carries the fold's animated seam, the ones between take inner corners
        // all around.
        headerRows.forEachIndexed { index, row ->
            val last = index == headerRows.lastIndex
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainer.withSurfaceOpacity(),
                shape = RoundedCornerShape(
                    topStart = if (index == 0) outerTop else innerTop,
                    topEnd = if (index == 0) outerTop else innerTop,
                    bottomStart = if (last) headerBottom else innerBottom,
                    bottomEnd = if (last) headerBottom else innerBottom,
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                row()
            }
        }
        // The fold itself: the rows unroll down from the header and roll back up under
        // it, the fade riding strong on top — the background refresh's own trade.
        AnimatedVisibility(
            visible = expanded,
            enter = expandVertically(motion.defaultSpatialSpec()) +
                fadeIn(motion.defaultEffectsSpec()),
            exit = shrinkVertically(motion.defaultSpatialSpec()) +
                fadeOut(motion.defaultEffectsSpec()),
        ) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                rows.forEachIndexed { index, row ->
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainer.withSurfaceOpacity(),
                        shape = RoundedCornerShape(
                            topStart = inner.topStart,
                            topEnd = inner.topEnd,
                            bottomStart = if (index == rows.lastIndex) outer.bottomStart else inner.bottomStart,
                            bottomEnd = if (index == rows.lastIndex) outer.bottomEnd else inner.bottomEnd,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        row()
                    }
                }
            }
        }
    }
}

/**
 * The row's triangle tile shape: an equilateral triangle, one corner pointing right — the
 * "go deeper" affordance — with corners rounded about as much as the square tiles. Shared
 * with the settings' own static [TriangleTile], the per-app row's plain pointer.
 */
internal val TriangleTilePolygon =
    RoundedPolygon(numVertices = 3, rounding = CornerRounding(0.25f)).normalized()

/**
 * The same triangle, spread over whatever bounds it is handed — focusRing's path for the
 * triangle tiles, so the ring wears the tile's own shape instead of a square around it.
 * The polygon is normalized to the unit square; toPath hands it over in those terms, and
 * the matrix spreads that square over the ring's own bounds.
 */
internal fun TriangleTileRingPath(bounds: Size): Path =
    TriangleTilePolygon.toPath().asComposePath().apply {
        transform(Matrix().apply { scale(bounds.width, bounds.height) })
    }

/**
 * The triangle tile flipping its point: down while the fold is closed, up while it is
 * open — the trip runs through the per-app row's own right-pointing pose, 180 degrees
 * on a spring a touch looser than the scheme's own, so the point swings just past its
 * mark and settles. The row the tile rides on carries the focus, never the tile —
 * [focused] lends it the row's own, so the ring lands on the fold's own handle.
 */
@Composable
fun FlipTriangleTile(
    pressed: Boolean,
    expanded: Boolean,
    focused: (() -> Boolean)? = null,
) {
    val colors = MaterialTheme.colorScheme
    val motion = MaterialTheme.motionScheme
    val resting = colors.secondaryContainer
    val container by animateColorAsState(
        targetValue = if (pressed) lerp(
            resting,
            colors.onSurface,
            0.1f,
        ) else resting,
        animationSpec = motion.defaultEffectsSpec(),
        label = "flip triangle container",
    )
    val rotation by animateFloatAsState(
        targetValue = if (expanded) -90f else 90f,
        animationSpec = spring(
            dampingRatio = 0.65f,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "flip triangle rotation",
    )
    // The ring rides inside the flip's own rotation and takes the tile's own shape: a
    // triangle pointing where the tile points, swinging with it — a standing square
    // around a turning triangle read as a bug, not a mark.
    Surface(
        shape = TriangleTilePolygon.toShape(),
        color = container,
        modifier = Modifier
            .size(48.dp)
            .graphicsLayer { rotationZ = rotation }
            .focusRing(
                focused = focused,
                path = { TriangleTileRingPath(it) },
            ),
    ) {}
}
