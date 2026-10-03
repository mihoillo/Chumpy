package slop.miho.aurora.ui.main

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import slop.miho.aurora.R
import slop.miho.aurora.data.DailyTraffic
import slop.miho.aurora.ui.LossBucket
import slop.miho.aurora.ui.StatsUiState
import slop.miho.aurora.ui.TrafficSample
import slop.miho.aurora.ui.components.InfoChip
import slop.miho.aurora.ui.wallpapers.ReportBarCoverage
import slop.miho.aurora.ui.wallpapers.withSurfaceOpacity
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.time.format.TextStyle
import java.util.Date
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/** The graph window the view model trims the history to; the charts assume the same span.
 *  Kept in step with MainViewModel's own window constant. */
private const val STATS_WINDOW_MILLIS = 15 * 60_000L

/** The graphs' point granularity, same as the view model's bucket constants: a bucket's
 *  point sits at its time midpoint on both charts. */
private const val BUCKET_MILLIS = 15_000L

/**
 * The stats screen: the week's daily bars first (they persist across runs, so the card is
 * full even when the live graphs are not), then the two live graphs over the fifteen-minute
 * window — the traffic (download filled, upload lined over it) from the Xray core's own
 * outbound counters — every read answers the bytes since the previous one, the core
 * resets its counters on each read — sampled once a second, and the packet loss as probe
 * buckets (a live delay measurement through the tunnel every fifteen seconds). The points on both graphs are 15-second averages — rare dots,
 * live updates — and both feeds wait a few seconds after the connect for the handshake storm
 * to settle. In the background the feeds keep running at a slower stride, so the history stays
 * warm across trips away. Loss over the last two minutes above 5% raises a red warning chip at
 * the top, on home as well; disconnecting freezes the graphs in place, and a chip row then
 * appears with when they froze and how long the connection lasted. The daily totals persist
 * across runs — everything else is memory only.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun StatsScreen(
    state: StatsUiState,
    tunnelUp: Boolean,
    modifier: Modifier = Modifier,
) {
    val scroll = rememberScrollState()
    val contentUnderBar by remember {
        derivedStateOf { scroll.canScrollForward || scroll.canScrollBackward }
    }
    val groupsReachBar by rememberContentUnderBar(scroll)
    ReportBarCoverage(contentUnderBar, underlaid = groupsReachBar)
    Column(
        modifier = modifier
            .fillMaxSize()
            .screenPadding()
            .verticalScroll(scroll)
            .padding(
                top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding() + 24.dp,
                bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() +
                    FloatingBottomBarReservedHeight,
            ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        // Nothing above the graphs while connected and healthy: the cards speak for
        // themselves. The loss warning chip breaks that silence — over 5% lost in the last
        // two minutes is worth a red flag even mid-connection — and the freeze chips arrive
        // once the tunnel is down and the graphs have stopped.
        val motion = MaterialTheme.motionScheme
        AnimatedVisibility(
            visible = state.recentLossPercent != null,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val colors = MaterialTheme.colorScheme
                state.recentLossPercent?.let { loss ->
                    InfoChip(
                        value = stringResource(R.string.stats_loss_warning, loss.roundToInt()),
                        icon = R.drawable.ic_scan_delete,
                        container = colors.errorContainer.withSurfaceOpacity(),
                        content = colors.onErrorContainer,
                        animateValue = true,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = !tunnelUp && state.frozenAtMillis != null,
            enter = fadeIn(motion.defaultEffectsSpec()),
            exit = fadeOut(motion.fastEffectsSpec()),
        ) {
            FrozenStatsHeader(state = state)
        }
        WeekChartCard(week = state.week)
        TrafficChartCard(samples = state.samples, tunnelUp = tunnelUp)
        LossChartCard(buckets = state.buckets, tunnelUp = tunnelUp)
    }
}

/**
 * The disconnected state's only chips: when the graphs froze ("Stats from 14:32") and how
 * long the connection behind them lasted ("9h"). Centered like the other screens' notices.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrozenStatsHeader(state: StatsUiState) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        InfoChip(
            value = stringResource(
                R.string.stats_frozen_from,
                SimpleDateFormat("HH:mm", Locale.US).format(Date(state.frozenAtMillis ?: 0L)),
            ),
            icon = R.drawable.ic_clock,
        )
        state.connectedMillis?.let { duration ->
            InfoChip(value = formatDuration(duration))
        }
    }
}

/** How long a connection was up, in the chip row's compact form: 42s, 9h, 2d 3h. */
private fun formatDuration(millis: Long): String {
    val totalMinutes = millis / 60_000
    val hours = totalMinutes / 60
    val days = hours / 24
    return when {
        totalMinutes < 1 -> "${millis / 1000}s"
        hours < 1 -> "${totalMinutes}m"
        days < 1 -> "${hours}h"
        else -> "${days}d ${hours % 24}h"
    }
}

/**
 * The traffic graph's card: a legend with the live 15-second-average rates over the chart,
 * the window and the peak under it. Download fills from the accent down to the baseline;
 * upload rides over it as a line in the tertiary hue.
 */
@Composable
private fun TrafficChartCard(samples: List<TrafficSample>, tunnelUp: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.withSurfaceOpacity(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ChartHeader(
                icon = R.drawable.ic_download,
                title = stringResource(R.string.stats_traffic),
            )
            val current = samples.lastOrNull()
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LegendSwatch(
                    color = colors.primary,
                    label = stringResource(R.string.stats_down),
                    value = current?.let { formatRate(it.downBps) },
                )
                LegendSwatch(
                    color = colors.tertiary,
                    label = stringResource(R.string.stats_up),
                    value = current?.let { formatRate(it.upBps) },
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(160.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (samples.size < 2) {
                    ChartPlaceholder(tunnelUp = tunnelUp)
                } else {
                    TrafficChart(samples = samples)
                }
            }
            val peak = maxOf(
                samples.maxOfOrNull { it.downBps } ?: 0L,
                samples.maxOfOrNull { it.upBps } ?: 0L,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = stringResource(R.string.stats_window),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.stats_peak, formatRate(peak)),
                    style = MaterialTheme.typography.labelMedium,
                    color = colors.onSurfaceVariant,
                )
            }
        }
    }
}

/** The download curve and its fill, the upload line over it, and the quiet grid under all. */
@Composable
private fun TrafficChart(samples: List<TrafficSample>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val grid = colors.outlineVariant.copy(alpha = 0.45f)
    val minuteGrid = colors.outlineVariant.copy(alpha = 0.22f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val chartW = size.width
        val chartH = size.height
        val xMax = samples.last().atMillis + BUCKET_MILLIS / 2
        val xMin = xMax - STATS_WINDOW_MILLIS
        val span = (xMax - xMin).coerceAtLeast(1L)
        val maxRate = maxOf(
            samples.maxOf { it.downBps },
            samples.maxOf { it.upBps },
        )
        val scale = niceCeil(maxRate * 1.15).coerceAtLeast(1.0)
        // A bucket's point sits at its midpoint: the average speaks for the whole span.
        fun x(at: Long) = chartW * (at + BUCKET_MILLIS / 2 - xMin) / span
        fun y(rate: Long) = (chartH * (1.0 - rate / scale)).toFloat()

        // The quiet grid: a line at the top, middle and baseline, and a fainter one every
        // five minutes of the window sliding in from the right edge.
        for (i in 0..2) {
            val gy = chartH * i / 2f
            drawLine(grid, Offset(0f, gy), Offset(chartW, gy), strokeWidth = 1.dp.toPx())
        }
        val gridStepMillis = 5 * 60_000L
        val firstGrid = (xMin / gridStepMillis + 1) * gridStepMillis
        for (gridTime in firstGrid..xMax step gridStepMillis) {
            val mx = x(gridTime)
            drawLine(
                minuteGrid,
                Offset(mx, 0f),
                Offset(mx, chartH),
                strokeWidth = 1.dp.toPx(),
            )
        }

        val downPoints = samples.map { Offset(x(it.atMillis), y(it.downBps)) }
        val upPoints = samples.map { Offset(x(it.atMillis), y(it.upBps)) }

        // Download's fill, fading toward the baseline.
        val fill = Path().apply {
            smoothThrough(downPoints)
            lineTo(downPoints.last().x, chartH)
            lineTo(downPoints.first().x, chartH)
            close()
        }
        drawPath(
            fill,
            Brush.verticalGradient(
                0f to colors.primary.copy(alpha = 0.28f),
                1f to colors.primary.copy(alpha = 0.04f),
            ),
        )
        // The lines themselves.
        drawPath(
            Path().apply { smoothThrough(downPoints) },
            colors.primary,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )
        drawPath(
            Path().apply { smoothThrough(upPoints) },
            colors.tertiary,
            style = androidx.compose.ui.graphics.drawscope.Stroke(width = 2.dp.toPx()),
        )
    }
}

/**
 * The packet loss card: the probes folded into 15-second buckets — a quiet tick for every
 * bucket whose handshakes all completed, an error-red column whose height is the bucket's
 * lost fraction — with the window's loss in the header. The strip stays alive even while
 * perfectly healthy: the ticks are what prove the probing itself is running.
 */
@Composable
private fun LossChartCard(buckets: List<LossBucket>, tunnelUp: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.withSurfaceOpacity(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val loss = lossPercent(buckets)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartHeader(
                    icon = R.drawable.ic_scan_delete,
                    title = stringResource(R.string.stats_loss),
                )
                Text(
                    text = if (buckets.isEmpty()) {
                        stringResource(R.string.home_value_pending)
                    } else {
                        stringResource(R.string.stats_loss_percent, loss.roundToInt())
                    },
                    style = MaterialTheme.typography.labelLarge,
                    color = if (loss > 0f) colors.error else colors.onSurfaceVariant,
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(72.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (buckets.isEmpty()) {
                    ChartPlaceholder(tunnelUp = tunnelUp)
                } else {
                    LossChart(buckets = buckets)
                }
            }
            Text(
                text = stringResource(R.string.stats_probes_caption),
                style = MaterialTheme.typography.labelMedium,
                color = colors.onSurfaceVariant,
            )
        }
    }
}

/** The bucket strip: a fully-healthy bucket a short quiet tick, a losing one a red column
 *  scaled to the fraction of its probes that never completed. */
@Composable
private fun LossChart(buckets: List<LossBucket>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val baseline = colors.outlineVariant.copy(alpha = 0.6f)
    val tick = colors.onSurfaceVariant.copy(alpha = 0.35f)
    Canvas(modifier = modifier.fillMaxSize()) {
        val chartW = size.width
        val chartH = size.height
        val xMax = buckets.last().atMillis + BUCKET_MILLIS / 2
        val xMin = xMax - STATS_WINDOW_MILLIS
        val span = (xMax - xMin).coerceAtLeast(1L)

        drawLine(baseline, Offset(0f, chartH - 1), Offset(chartW, chartH - 1), strokeWidth = 1.dp.toPx())
        // One slot per bucket, the bar centered on the bucket's midpoint and taking a bit
        // over half the slot's width.
        val slot = chartW * BUCKET_MILLIS / STATS_WINDOW_MILLIS
        val barWidth = (slot * 0.55f).coerceAtLeast(3.dp.toPx())
        val corner = CornerRadius(barWidth / 2)
        buckets.forEach { bucket ->
            val cx = chartW * (bucket.atMillis + BUCKET_MILLIS / 2 - xMin) / span
            val px = (cx - barWidth / 2).coerceIn(0f, chartW - barWidth)
            if (bucket.lost > 0) {
                val fraction = bucket.lost.toFloat() / bucket.sent.coerceAtLeast(1)
                val height = maxOf(4.dp.toPx(), (chartH - 2.dp.toPx()) * fraction)
                drawRoundRect(
                    color = colors.error,
                    topLeft = Offset(px, chartH - 1f - height),
                    size = androidx.compose.ui.geometry.Size(barWidth, height),
                    cornerRadius = corner,
                )
            } else {
                drawRoundRect(
                    color = tick,
                    topLeft = Offset(px, chartH - 5.dp.toPx()),
                    size = androidx.compose.ui.geometry.Size(barWidth, 4.dp.toPx()),
                    cornerRadius = corner,
                )
            }
        }
    }
}

/**
 * The week's traffic as seven stacked bars — download in the accent, upload in the tertiary
 * hue over it — one column a day, the last one today. The daily totals persist across runs,
 * so this card is full even when the live graphs are not.
 */
@Composable
private fun WeekChartCard(week: List<DailyTraffic>, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    Surface(
        shape = MaterialTheme.shapes.extraLarge,
        color = colors.surfaceContainer.withSurfaceOpacity(),
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ChartHeader(
                    icon = R.drawable.ic_stats,
                    title = stringResource(R.string.stats_week),
                )
                val total = week.sumOf { it.rx + it.tx }
                if (total > 0) {
                    Text(
                        text = stringResource(R.string.stats_week_total, formatBytes(total)),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
            val today = remember { LocalDate.now() }
            val days = remember(week, today) {
                (6 downTo 0).map { offset ->
                    val day = today.minusDays(offset.toLong())
                    val bucket = week.firstOrNull { it.epochDay == day.toEpochDay() }
                    DayTraffic(day, bucket?.rx ?: 0L, bucket?.tx ?: 0L)
                }
            }
            if (days.all { it.rx + it.tx == 0L }) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.stats_no_data),
                        style = MaterialTheme.typography.labelLarge,
                        color = colors.onSurfaceVariant,
                    )
                }
            } else {
                val maxTotal = days.maxOf { it.rx + it.tx }.coerceAtLeast(1L)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    days.forEach { day ->
                        DayColumn(
                            rx = day.rx,
                            tx = day.tx,
                            maxTotal = maxTotal,
                            label = day.date.dayOfWeek.getDisplayName(TextStyle.NARROW, Locale.US),
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            }
        }
    }
}

/** One day of the week chart: the stacked bar and its weekday letter below. */
private data class DayTraffic(val date: LocalDate, val rx: Long, val tx: Long)

@Composable
private fun RowScope.DayColumn(
    rx: Long,
    tx: Long,
    maxTotal: Long,
    label: String,
    modifier: Modifier = Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(112.dp),
            contentAlignment = Alignment.BottomCenter,
        ) {
            // The stack shares one rounding: the top block's top corners, the bottom
            // block's bottom ones; a day with no upload rounds the download instead.
            Column(
                modifier = Modifier.fillMaxWidth(0.58f),
                verticalArrangement = Arrangement.spacedBy(1.dp),
            ) {
                if (tx > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp * (tx.toFloat() / maxTotal))
                            .clip(RoundedCornerShape(topStart = 4.dp, topEnd = 4.dp))
                            .background(colors.tertiary),
                    )
                }
                if (rx > 0) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(112.dp * (rx.toFloat() / maxTotal))
                            .clip(RoundedCornerShape(bottomStart = 4.dp, bottomEnd = 4.dp))
                            .background(colors.primary),
                    )
                }
            }
        }
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = colors.onSurfaceVariant,
        )
    }
}

/** A card's heading: the small icon-and-label rows the settings' sections use. */
@Composable
private fun ChartHeader(@DrawableRes icon: Int, title: String) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            painter = painterResource(icon),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(20.dp),
        )
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** One series in the traffic legend: a color dash, its name, and its live rate. */
@Composable
private fun LegendSwatch(color: Color, label: String, value: String?) {
    Row(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .width(14.dp)
                .height(3.dp)
                .clip(CircleShape)
                .background(color),
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        value?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

/** What the charts say before there is anything to draw. */
@Composable
private fun ChartPlaceholder(tunnelUp: Boolean) {
    Text(
        text = stringResource(
            if (tunnelUp) R.string.stats_waiting else R.string.stats_no_data,
        ),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** The window's loss as a percentage of the probes that completed or were lost. */
private fun lossPercent(buckets: List<LossBucket>): Float {
    val sent = buckets.sumOf { it.sent }
    if (sent == 0) return 0f
    return buckets.sumOf { it.lost } * 100f / sent
}

/**
 * The next "nice" number at or above the value (1, 2, 5 × 10ⁿ): the chart's top line lands
 * on a round figure instead of 1.73 MB/s.
 */
private fun niceCeil(value: Double): Double {
    if (value <= 0.0) return 1.0
    val exponent = kotlin.math.floor(ln(value) / ln(10.0)).toInt()
    val fraction = value / 10.0.pow(exponent)
    val nice = when {
        fraction <= 1.0 -> 1.0
        fraction <= 2.0 -> 2.0
        fraction <= 5.0 -> 5.0
        else -> 10.0
    }
    return nice * 10.0.pow(exponent)
}

/** Bytes as people read them: 730 B, 12.4 KB, 3.1 MB, 1.2 GB. */
private fun formatBytes(bytes: Long): String {
    val value = bytes.toDouble()
    return when {
        bytes >= 999_999_999 -> trimDecimal(value / 1e9) + " GB"
        bytes >= 999_999 -> trimDecimal(value / 1e6) + " MB"
        bytes >= 999 -> trimDecimal(value / 1e3) + " KB"
        else -> "$bytes B"
    }
}

/** One decimal below ten, none above it: 3.1 MB, 12 MB. Pinned to the dot, whatever the
 *  device's locale says — the UI is English-only. */
private fun trimDecimal(value: Double): String =
    if (value < 10.0) {
        String.format(java.util.Locale.US, "%.1f", value)
    } else {
        ceil(value).toInt().toString()
    }

private fun formatRate(bytesPerSec: Long): String = formatBytes(bytesPerSec) + "/s"

/** A smooth line through the points: cubics bending at each half-step between neighbors. */
private fun Path.smoothThrough(points: List<Offset>) {
    if (points.isEmpty()) return
    moveTo(points.first().x, points.first().y)
    for (i in 1 until points.size) {
        val previous = points[i - 1]
        val current = points[i]
        val midX = (previous.x + current.x) / 2f
        cubicTo(midX, previous.y, midX, current.y, current.x, current.y)
    }
}
