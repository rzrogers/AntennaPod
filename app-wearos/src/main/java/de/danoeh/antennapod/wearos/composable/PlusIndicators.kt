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
import androidx.compose.ui.graphics.lerp as lerpColor
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
private val VOLUME_COLOR = Color(0xFF0078C2)

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
        track.segment(this, VOLUME_COLOR, b, 1f, stroke)
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
        val thumbColor = Color.White.copy(alpha = alpha)
        val trackColor = TRACK_COLOR.copy(alpha = TRACK_COLOR.alpha * alpha)
        if (base + 1 == listPage) {
            val barStart = listPage * (dot + gap)
            val thumbLength = (1f - barStart) * THUMB_FRACTION
            val thumbTop = barStart + (1f - barStart - thumbLength) * progress
            val thumbBottom = thumbTop + thumbLength
            for (i in 0 until base) {
                val top = lerp(from[i].first, i * (dot + gap), offset)
                track.segment(this, dotColor, top, top + dot, stroke)
            }
            val release = smoothStep((offset - 0.7f) / 0.3f)
            val ownDotTop = base * (dot + gap)
            track.segment(this, dotColor.copy(alpha = dotColor.alpha * release), ownDotTop, ownDotTop + dot, stroke)
            val stretch = easeOut(offset)
            val grow = easeOut(min(1f, offset * 1.3f))
            val markTop = lerp(from[base].first, thumbTop, release)
            val markBottom = lerp(from[base].second, thumbBottom, stretch)
            val growTop = maxOf(lerp(from[listPage].first, thumbBottom + gap, grow), markBottom + gap)
            val growBottom = lerp(from[listPage].second, 1f, grow)
            track.segment(this, lerpColor(dotColor, trackColor, grow), growTop, growBottom, stroke)
            if (thumbTop - gap > barStart) {
                track.segment(this, trackColor.copy(alpha = trackColor.alpha * release), barStart,
                    min(thumbTop, markTop) - gap, stroke)
            }
            track.segment(this, thumbColor, markTop, markBottom, stroke)
        } else {
            for (i in 0 until pageCount) {
                if (i != base && i != base + 1) {
                    track.segment(this, dotColor, from[i].first, from[i].second, stroke)
                }
            }
            track.segment(this, dotColor.copy(alpha = dotColor.alpha * offset), to[base].first, to[base].second,
                stroke)
            track.segment(this, dotColor.copy(alpha = dotColor.alpha * (1f - offset)),
                from[base + 1].first, from[base + 1].second, stroke)
            val early = min(1f, offset * 2f)
            val late = (offset * 2f - 1f).coerceIn(0f, 1f)
            val top = lerp(from[base].first, to[base + 1].first, late)
            val bottom = lerp(from[base].second, to[base + 1].second, early)
            track.segment(this, thumbColor, top, bottom, stroke)
        }
    }
}

private fun easeOut(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return 1f - (1f - x) * (1f - x)
}

private fun smoothStep(t: Float): Float {
    val x = t.coerceIn(0f, 1f)
    return x * x * (3f - 2f * x)
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
        val gap = SEGMENT_GAP.toPx() / track.length
        val trackColor = TRACK_COLOR.copy(alpha = TRACK_COLOR.alpha * alpha)
        val thumbTop = (1f - THUMB_FRACTION) * progress
        val thumbBottom = thumbTop + THUMB_FRACTION
        track.segment(this, trackColor, 0f, thumbTop - gap, stroke)
        track.segment(this, Color.White.copy(alpha = alpha), thumbTop, thumbBottom, stroke)
        track.segment(this, trackColor, thumbBottom + gap, 1f, stroke)
    }
}
