package de.danoeh.antennapod.wearos.composable

import android.content.Context
import android.text.format.DateFormat
import androidx.annotation.DrawableRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.ui.common.DateFormatter
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import java.text.DecimalFormat
import java.util.Date
import kotlinx.coroutines.delay

val PlusAccent = Color(0xFFE7E3A0)
val PlusHighlight = Color(0xFF0B3A5C)
val PlusChip = Color(0xFF2B2D31)
val PlusSecondaryText = Color(0xFFB4B9C4)

@Composable
fun PollWhileStarted(paths: List<String>, intervalMillis: Long = 1000L) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner, paths) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                for (path in paths) {
                    WearMessageSender.send(context, path)
                }
                delay(intervalMillis)
            }
        }
    }
}

@Composable
fun rememberClockText(): String {
    val context = LocalContext.current
    var text by remember { mutableStateOf(DateFormat.getTimeFormat(context).format(Date())) }
    LaunchedEffect(Unit) {
        while (true) {
            text = DateFormat.getTimeFormat(context).format(Date())
            delay(5_000)
        }
    }
    return text
}

fun formatSpeed(speed: Float): String = DecimalFormat("0.##").format(speed) + "×"

fun formatShortDuration(context: Context, millis: Long): String {
    val totalMinutes = (millis / 60_000L).toInt()
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    return when {
        hours == 0 -> context.getString(CommonR.string.plus_wear_minutes, minutes)
        minutes == 0 -> context.getString(CommonR.string.plus_wear_hours, hours)
        else -> context.getString(CommonR.string.plus_wear_hours_minutes, hours, minutes)
    }
}

fun episodeInfo(context: Context, row: PlusWear.EpisodeRow): String {
    val length = if (row.position > 0 && row.duration > row.position) {
        context.getString(CommonR.string.plus_wear_left,
            formatShortDuration(context, (row.duration - row.position).toLong()))
    } else if (row.duration > 0) {
        formatShortDuration(context, row.duration.toLong())
    } else {
        ""
    }
    val date = if (row.pubDate > 0) DateFormatter.formatAbbrev(context, Date(row.pubDate)) else ""
    return listOf(length, date).filter { it.isNotEmpty() }.joinToString(" · ")
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MarqueeText(
    text: String,
    fontSize: Float,
    color: Color = Color.White,
    fontWeight: FontWeight = FontWeight.Normal,
    modifier: Modifier = Modifier
) {
    Text(
        text = text,
        modifier = modifier.basicMarquee(),
        color = color,
        fontSize = fontSize.sp,
        fontWeight = fontWeight,
        maxLines = 1
    )
}

@Composable
fun RingPlayButton(
    size: Dp,
    progress: Float,
    playing: Boolean,
    onClick: () -> Unit
) {
    Box(modifier = Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val stroke = this.size.width * 0.05f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            drawArc(
                color = Color.White.copy(alpha = 0.25f),
                startAngle = 0f,
                sweepAngle = 360f,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke)
            )
            if (progress > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = -90f,
                    sweepAngle = 360f * progress.coerceIn(0f, 1f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        }
        val label = stringResource(if (playing) CommonR.string.pause_label else CommonR.string.play_label)
        Box(
            modifier = Modifier
                .size(size * 0.78f)
                .clip(CircleShape)
                .background(Color.White)
                .clickable(onClickLabel = label, onClick = onClick),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                painter = painterResource(
                    if (playing) CommonR.drawable.ic_pause_black else CommonR.drawable.ic_play_48dp_black
                ),
                contentDescription = label,
                tint = Color(0xFF1B1B1F),
                modifier = Modifier.size(size * 0.56f)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun RoundButton(
    size: Dp,
    contentDescription: String,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    background: Color = Color.White.copy(alpha = 0.14f),
    content: @Composable () -> Unit
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(background)
            .combinedClickable(
                onClickLabel = contentDescription,
                onLongClick = onLongClick,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        content()
    }
}

@Composable
fun RoundIconButton(
    size: Dp,
    @DrawableRes iconRes: Int,
    contentDescription: String,
    iconSize: Dp,
    background: Color = Color.White.copy(alpha = 0.14f),
    onClick: () -> Unit
) {
    RoundButton(size = size, contentDescription = contentDescription, onClick = onClick, background = background) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            tint = Color.White,
            modifier = Modifier.size(iconSize)
        )
    }
}

@Composable
fun EpisodeRowItem(row: PlusWear.EpisodeRow, onClick: () -> Unit) {
    val context = LocalContext.current
    val art by PlusPlayerRepository.art.collectAsState()
    LaunchedEffect(row.id) {
        PlusPlayerRepository.requestArt(context, row.id, PlusPlayerRepository.ART_SMALL)
    }
    val bitmap = art[PlusPlayerRepository.artKey(row.id, PlusPlayerRepository.ART_SMALL)]
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(26.dp))
            .background(if (row.current) PlusHighlight else PlusChip)
            .clickable(onClick = onClick)
            .padding(start = 8.dp, end = 12.dp, top = 6.dp, bottom = 6.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
                    .background(Color(0xFF3D4047))
            ) {
                if (bitmap != null) {
                    Image(
                        bitmap = bitmap,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                MarqueeText(text = row.title, fontSize = 13f, fontWeight = FontWeight.SemiBold)
                Text(
                    text = row.feedTitle,
                    color = PlusAccent,
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = episodeInfo(context, row),
                    color = PlusSecondaryText,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
fun SectionLabel(text: String) {
    Text(
        text = text,
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 2.dp),
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        textAlign = TextAlign.Center
    )
}
