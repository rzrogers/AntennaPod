package de.danoeh.antennapod.wearos.composable

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.PagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlinx.coroutines.delay

private const val TRACK_SWEEP_DEGREES = 50f
private const val SQUARE_TRACK_FRACTION = 0.32f
private const val THUMB_FRACTION = 0.4f
private const val HIDE_DELAY_MILLIS = 1500L
private val INDICATOR_STROKE = 5.dp
private val VOLUME_STROKE = 8.dp
private val SEGMENT_GAP = 4.dp
private val EDGE_PADDING = 3.dp
private val TRACK_COLOR = Color.White.copy(alpha = 0.3f)

/**
 * A short track along the left or right edge of the screen, curved on round screens and straight on square ones.
 * Positions along it run from 0 (top) to 1 (bottom).
 */
private class EdgeTrack(scope: DrawScope, private val round: Boolean, private val left: Boolean, stroke: Float) {
    private val center = Offset(scope.size.width / 2f, scope.size.height / 2f)
    private val radius = min(scope.size.width, scope.size.height) / 2f - stroke / 2f
    private val lineLength = scope.size.height * SQUARE_TRACK_FRACTION
    private val lineX = if (left) stroke / 2f else scope.size.width - stroke / 2f
    private val lineTop = (scope.size.height - lineLength) / 2f

    val length: Float = if (round) radius * Math.toRadians(TRACK_SWEEP_DEGREES.toDouble()).toFloat() else lineLength

    private fun angleAt(t: Float): Float = if (left) {
        180f + TRACK_SWEEP_DEGREES / 2f - t * TRACK_SWEEP_DEGREES
    } else {
        -TRACK_SWEEP_DEGREES / 2f + t * TRACK_SWEEP_DEGREES
    }

    private fun pointAt(t: Float): Offset = if (round) {
        val radians = Math.toRadians(angleAt(t).toDouble())
        Offset(center.x + radius * cos(radians).toFloat(), center.y + radius * sin(radians).toFloat())
    } else {
        Offset(lineX, lineTop + t * lineLength)
    }

    /** Draws the part of the track from t0 to t1 with rounded ends, or as a smaller dot if it is too short. */
    fun segment(scope: DrawScope, color: Color, t0: Float, t1: Float, stroke: Float) {
        val start = t0.coerceIn(0f, 1f)
        val end = t1.coerceIn(0f, 1f)
        val segmentLength = (end - start) * length
        if (segmentLength <= 0.5f) {
            return
        }
        if (segmentLength <= stroke + 0.5f) {
            scope.drawCircle(color, radius = min(stroke, segmentLength) / 2f, center = pointAt((start + end) / 2f))
            return
        }
        val capT = (stroke / 2f) / length
        val a = start + capT
        val b = end - capT
        if (round) {
            val angleA = angleAt(a)
            val angleB = angleAt(b)
            scope.drawArc(
                color = color,
                startAngle = min(angleA, angleB),
                sweepAngle = abs(angleB - angleA),
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = Size(radius * 2f, radius * 2f),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        } else {
            scope.drawLine(color, pointAt(a), pointAt(b), strokeWidth = stroke, cap = StrokeCap.Round)
        }
    }
}

@Composable
fun PlusVolumeIndicator(level: Int, max: Int, round: Boolean) {
    val safeMax = max.coerceAtLeast(1)
    val animated by animateFloatAsState(
        targetValue = level.coerceIn(0, safeMax).toFloat(),
        animationSpec = spring(dampingRatio = 0.7f, stiffness = Spring.StiffnessMediumLow),
        label = "volume"
    )
    Canvas(modifier = Modifier.fillMaxSize().padding(EDGE_PADDING)) {
        val stroke = VOLUME_STROKE.toPx()
        val track = EdgeTrack(this, round, left = true, stroke = stroke)
        val gap = SEGMENT_GAP.toPx() / track.length
        val minPart = 2.dp.toPx() / track.length
        fun boundaryAt(step: Int): Float = when {
            step >= safeMax -> 0f
            step <= 0 -> 1f
            else -> (1f - step.toFloat() / safeMax).coerceIn(minPart + gap, 1f - minPart)
        }
        val x = animated.coerceIn(0f, safeMax.toFloat())
        val low = floor(x).toInt()
        val b = lerp(boundaryAt(low), boundaryAt(low + 1), x - low).coerceIn(0f, 1f)
        val nearEnd = min(b, 1f - b)
        val effectiveGap = gap * (nearEnd / (gap + minPart)).coerceIn(0f, 1f)
        track.segment(this, TRACK_COLOR, 0f, b - effectiveGap, stroke)
        track.segment(this, Color.White, b, 1f, stroke)
    }
}

private val PILL_LENGTH = 13.dp

private fun pageLayout(
    page: Int,
    pageCount: Int,
    listPage: Int,
    dot: Float,
    pill: Float,
    gap: Float
): List<Pair<Float, Float>> {
    val big = if (page == listPage) 1f - (pageCount - 1) * (dot + gap) else pill
    val total = big + (pageCount - 1) * (dot + gap)
    val ranges = ArrayList<Pair<Float, Float>>()
    var position = (1f - total) / 2f
    for (i in 0 until pageCount) {
        val length = if (i == page) big else dot
        ranges.add(position to position + length)
        position += length + gap
    }
    return ranges
}

private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

fun scrollProgress(listState: ScalingLazyListState): Float {
    val total = listState.layoutInfo.totalItemsCount
    if (total <= 1) {
        return 0f
    }
    val index = listState.centerItemIndex
    val itemSize = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }?.size ?: 0
    val within = if (itemSize > 0) listState.centerItemScrollOffset.toFloat() / itemSize else 0f
    val first = min(1, total - 1).toFloat()
    val last = (total - 1).toFloat()
    if (last <= first) {
        return 0f
    }
    return ((index + within - first) / (last - first)).coerceIn(0f, 1f)
}

@Composable
private fun rememberIndicatorAlpha(active: Boolean, vararg keys: Any?): Float {
    var shown by remember { mutableStateOf(true) }
    LaunchedEffect(active, *keys) {
        shown = true
        if (!active) {
            delay(HIDE_DELAY_MILLIS)
            shown = false
        }
    }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, animationSpec = tween(300), label = "indicator")
    return alpha
}

/**
 * Spotify-style combined indicator: one mark per page, the current one longer; the last page holds a list, whose
 * mark turns into a scroll track with a thumb.
 */
@Composable
fun PlusPagerIndicator(pagerState: PagerState, listState: ScalingLazyListState, listPage: Int, round: Boolean) {
    val scrolling = pagerState.isScrollInProgress || listState.isScrollInProgress
    val alpha = rememberIndicatorAlpha(scrolling, pagerState.currentPage, listState.centerItemIndex)
    if (alpha <= 0f) {
        return
    }
    val pageCount = pagerState.pageCount
    val position = (pagerState.currentPage + pagerState.currentPageOffsetFraction)
        .coerceIn(0f, (pageCount - 1).toFloat())
    val progress = scrollProgress(listState)
    Canvas(modifier = Modifier.fillMaxSize().padding(EDGE_PADDING)) {
        val stroke = INDICATOR_STROKE.toPx()
        val track = EdgeTrack(this, round, left = false, stroke = stroke)
        val dot = stroke / track.length
        val gap = SEGMENT_GAP.toPx() / track.length
        val base = floor(position).toInt().coerceAtMost(pageCount - 2).coerceAtLeast(0)
        val offset = position - base
        val pill = PILL_LENGTH.toPx() / track.length
        val from = pageLayout(base, pageCount, listPage, dot, pill, gap)
        val to = pageLayout(base + 1, pageCount, listPage, dot, pill, gap)
        val dotColor = Color.White.copy(alpha = 0.4f * alpha)
        for (i in 0 until pageCount) {
            if (i != base && i != base + 1) {
                track.segment(this, dotColor, from[i].first, from[i].second, stroke)
            }
        }
        track.segment(this, dotColor.copy(alpha = dotColor.alpha * offset), to[base].first, to[base].second, stroke)
        track.segment(this, dotColor.copy(alpha = dotColor.alpha * (1f - offset)),
            from[base + 1].first, from[base + 1].second, stroke)
        val early = min(1f, offset * 2f)
        val late = (offset * 2f - 1f).coerceIn(0f, 1f)
        val top = lerp(from[base].first, to[base + 1].first, late)
        val bottom = lerp(from[base].second, to[base + 1].second, early)
        val onList = position >= listPage - 0.5f
        if (onList) {
            track.segment(this, TRACK_COLOR.copy(alpha = TRACK_COLOR.alpha * alpha), top, bottom, stroke)
            val thumb = (bottom - top) * THUMB_FRACTION
            val thumbTop = top + (bottom - top - thumb) * progress
            track.segment(this, Color.White.copy(alpha = alpha), thumbTop, thumbTop + thumb, stroke)
        } else {
            track.segment(this, Color.White.copy(alpha = alpha), top, bottom, stroke)
        }
    }
}

/** Scroll bar for list screens, matching the pager indicator's size and look. */
@Composable
fun PlusScrollIndicator(listState: ScalingLazyListState, modifier: Modifier = Modifier) {
    val round = isRoundScreen()
    val alpha = rememberIndicatorAlpha(listState.isScrollInProgress, listState.centerItemIndex)
    if (alpha <= 0f || listState.layoutInfo.totalItemsCount <= 1) {
        return
    }
    val progress = scrollProgress(listState)
    Canvas(modifier = modifier.fillMaxSize().padding(EDGE_PADDING)) {
        val stroke = INDICATOR_STROKE.toPx()
        val track = EdgeTrack(this, round, left = false, stroke = stroke)
        track.segment(this, TRACK_COLOR.copy(alpha = TRACK_COLOR.alpha * alpha), 0f, 1f, stroke)
        val thumbTop = (1f - THUMB_FRACTION) * progress
        track.segment(this, Color.White.copy(alpha = alpha), thumbTop, thumbTop + THUMB_FRACTION, stroke)
    }
}
