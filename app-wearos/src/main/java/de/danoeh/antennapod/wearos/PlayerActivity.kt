package de.danoeh.antennapod.wearos

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListState
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.MarqueeText
import de.danoeh.antennapod.wearos.composable.PlusAccent
import de.danoeh.antennapod.wearos.composable.PollWhileStarted
import de.danoeh.antennapod.wearos.composable.RingPlayButton
import de.danoeh.antennapod.wearos.composable.RoundButton
import de.danoeh.antennapod.wearos.composable.RoundIconButton
import de.danoeh.antennapod.wearos.composable.SectionLabel
import de.danoeh.antennapod.wearos.composable.formatSpeed
import de.danoeh.antennapod.wearos.composable.rememberClockText
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlayerActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                PlayerScreen(
                    onOpen = { target -> startActivity(Intent(this, target)) }
                )
            }
        }
    }
}

private const val PAGE_SIMPLE = 0
private const val PAGE_PLAYER = 1
private const val PAGE_LISTS = 2
private const val VOLUME_STEP_PIXELS = 60f
private const val VOLUME_SHOW_MILLIS = 1500L

@Composable
fun PlayerScreen(onOpen: (Class<*>) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PlusPlayerRepository.player.collectAsState()
    val art by PlusPlayerRepository.art.collectAsState()
    val jumpSeconds by PlusWearRepository.jumpSeconds.collectAsState()
    val playlists by PlusWearRepository.playlists.collectAsState()
    val volume by PlusPlayerRepository.volume.collectAsState()
    val pagerState = rememberPagerState(initialPage = PAGE_PLAYER, pageCount = { 3 })
    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    var rotaryPixels by remember { mutableFloatStateOf(0f) }
    var showVolume by remember { mutableStateOf(false) }

    PollWhileStarted(listOf(PlusWear.PLAYER))
    LaunchedEffect(Unit) {
        WearMessageSender.send(context, PlusWear.JUMP_SECONDS)
        WearMessageSender.send(context, PlusWear.PLAYLISTS)
    }
    val itemId = state?.itemId ?: 0L
    LaunchedEffect(itemId) {
        PlusPlayerRepository.requestArt(context, itemId, PlusPlayerRepository.ART_LARGE)
    }
    LaunchedEffect(pagerState.isScrollInProgress) {
        if (!pagerState.isScrollInProgress) {
            try {
                focusRequester.requestFocus()
            } catch (e: IllegalStateException) {
                // not attached yet
            }
        }
    }
    LaunchedEffect(volume?.stamp) {
        val stamp = volume?.stamp ?: return@LaunchedEffect
        if (SystemClock.elapsedRealtime() - stamp < VOLUME_SHOW_MILLIS) {
            showVolume = true
            delay(VOLUME_SHOW_MILLIS)
            showVolume = false
        }
    }

    val current = state
    val playing = current?.playing == true
    val progress = if (current != null && current.duration > 0) {
        current.position.toFloat() / current.duration.toFloat()
    } else {
        0f
    }
    val title = current?.title?.ifEmpty { null } ?: stringResource(CommonR.string.no_media_playing_label)
    val feedTitle = current?.feedTitle ?: ""
    val bitmap = art[PlusPlayerRepository.artKey(itemId, PlusPlayerRepository.ART_LARGE)]
    val onPlayPause: () -> Unit = {
        scope.launch {
            WearMessageSender.send(context, if (playing) WearDataPaths.PAUSE else PlusWear.PLAY)
        }
        PlusPlayerRepository.setPlaying(!playing)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .onRotaryScrollEvent { event ->
                if (pagerState.currentPage == PAGE_LISTS) {
                    scope.launch { listState.scrollBy(event.verticalScrollPixels) }
                } else {
                    rotaryPixels += event.verticalScrollPixels
                    while (abs(rotaryPixels) >= VOLUME_STEP_PIXELS) {
                        val up = rotaryPixels > 0
                        rotaryPixels -= if (up) VOLUME_STEP_PIXELS else -VOLUME_STEP_PIXELS
                        scope.launch {
                            WearMessageSender.send(context, if (up) PlusWear.VOLUME_UP else PlusWear.VOLUME_DOWN)
                        }
                    }
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val unit = maxWidth / 192f
        val textScale = maxWidth.value / 192f
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            when (page) {
                PAGE_SIMPLE -> SimplePage(
                    unit = unit,
                    textScale = textScale,
                    title = title,
                    feedTitle = feedTitle,
                    bitmap = bitmap,
                    progress = progress,
                    playing = playing,
                    onPlayPause = onPlayPause
                )
                PAGE_PLAYER -> MainPage(
                    unit = unit,
                    textScale = textScale,
                    title = title,
                    feedTitle = feedTitle,
                    bitmap = bitmap,
                    progress = progress,
                    playing = playing,
                    speed = current?.speed ?: 1f,
                    jumpSeconds = jumpSeconds,
                    onPlayPause = onPlayPause,
                    onJumpBack = { scope.launch { WearMessageSender.send(context, WearDataPaths.SKIP_BACKWARD) } },
                    onJumpForward = { scope.launch { WearMessageSender.send(context, WearDataPaths.SKIP_FORWARD) } },
                    onOutput = { onOpen(OutputActivity::class.java) },
                    onQueue = { onOpen(PlusQueueActivity::class.java) },
                    onSpeed = { scope.launch { WearMessageSender.send(context, PlusWear.SPEED_NEXT) } },
                    onSpeedLong = { onOpen(SpeedSleepActivity::class.java) }
                )
                else -> ListsPage(
                    listState = listState,
                    playlists = playlists,
                    onSearch = { onOpen(SearchActivity::class.java) },
                    onLibrary = { onOpen(LibraryActivity::class.java) },
                    onPlaylist = { row ->
                        scope.launch {
                            WearMessageSender.send(context, PlusWear.playPlaylistPath(row.id))
                            pagerState.animateScrollToPage(PAGE_PLAYER)
                            delay(2_000)
                            WearMessageSender.send(context, PlusWear.PLAYLISTS)
                        }
                    }
                )
            }
        }
        PageDots(pagerState = pagerState, modifier = Modifier.align(Alignment.CenterEnd))
        val level = volume
        if (showVolume && level != null && level.max > 0) {
            VolumeArc(fraction = level.level.toFloat() / level.max.toFloat())
        }
    }
}

@Composable
private fun ArtBackground(bitmap: ImageBitmap?, dim: Float) {
    Box(modifier = Modifier.fillMaxSize()) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
        }
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
    }
}

@Composable
private fun SimplePage(
    unit: Dp,
    textScale: Float,
    title: String,
    feedTitle: String,
    bitmap: ImageBitmap?,
    progress: Float,
    playing: Boolean,
    onPlayPause: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ArtBackground(bitmap = bitmap, dim = 0.7f)
        Column(
            modifier = Modifier.fillMaxSize().padding(top = unit * 12),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = rememberClockText(),
                color = PlusAccent,
                fontSize = (30 * textScale).sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(unit * 8))
            RingPlayButton(size = unit * 72, progress = progress, playing = playing, onClick = onPlayPause)
            Spacer(modifier = Modifier.height(unit * 8))
            MarqueeText(
                text = title,
                fontSize = 16 * textScale,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = unit * 24)
            )
            MarqueeText(
                text = feedTitle,
                fontSize = 13 * textScale,
                color = PlusAccent,
                modifier = Modifier.padding(horizontal = unit * 40)
            )
        }
    }
}

@Composable
private fun MainPage(
    unit: Dp,
    textScale: Float,
    title: String,
    feedTitle: String,
    bitmap: ImageBitmap?,
    progress: Float,
    playing: Boolean,
    speed: Float,
    jumpSeconds: IntArray,
    onPlayPause: () -> Unit,
    onJumpBack: () -> Unit,
    onJumpForward: () -> Unit,
    onOutput: () -> Unit,
    onQueue: () -> Unit,
    onSpeed: () -> Unit,
    onSpeedLong: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        ArtBackground(bitmap = bitmap, dim = 0.55f)
        Column(
            modifier = Modifier.fillMaxSize().padding(top = unit * 6),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = rememberClockText(),
                color = Color.White,
                fontSize = (12 * textScale).sp,
                fontWeight = FontWeight.Medium
            )
            MarqueeText(
                text = title,
                fontSize = 14 * textScale,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = unit * 22)
            )
            MarqueeText(
                text = feedTitle,
                fontSize = 12 * textScale,
                color = PlusAccent,
                modifier = Modifier.padding(horizontal = unit * 34)
            )
            Spacer(modifier = Modifier.height(unit * 6))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(unit * 4)
            ) {
                RoundIconButton(
                    size = unit * 42,
                    iconRes = PlusJumpIcons.back(jumpSeconds[0]),
                    contentDescription = stringResource(CommonR.string.rewind_label),
                    iconSize = unit * 34,
                    background = Color.Transparent,
                    onClick = onJumpBack
                )
                RingPlayButton(size = unit * 66, progress = progress, playing = playing, onClick = onPlayPause)
                RoundIconButton(
                    size = unit * 42,
                    iconRes = PlusJumpIcons.forward(jumpSeconds[1]),
                    contentDescription = stringResource(CommonR.string.fast_forward_label),
                    iconSize = unit * 34,
                    background = Color.Transparent,
                    onClick = onJumpForward
                )
            }
            Spacer(modifier = Modifier.height(unit * 6))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(unit * 10)
            ) {
                RoundIconButton(
                    size = unit * 34,
                    iconRes = CommonR.drawable.ic_phone_black,
                    contentDescription = stringResource(CommonR.string.plus_wear_output),
                    iconSize = unit * 18,
                    onClick = onOutput
                )
                RoundIconButton(
                    size = unit * 34,
                    iconRes = NavigationNames.getIconResForPath(WearDataPaths.QUEUE),
                    contentDescription = stringResource(CommonR.string.queue_label),
                    iconSize = unit * 18,
                    onClick = onQueue
                )
                RoundButton(
                    size = unit * 34,
                    contentDescription = stringResource(CommonR.string.plus_wear_speed),
                    onClick = onSpeed,
                    onLongClick = onSpeedLong
                ) {
                    Text(
                        text = formatSpeed(speed),
                        color = Color.White,
                        fontSize = (10 * textScale).sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1
                    )
                }
            }
        }
    }
}

@Composable
private fun ListsPage(
    listState: ScalingLazyListState,
    playlists: List<PlusWear.PlaylistRow>?,
    onSearch: () -> Unit,
    onLibrary: () -> Unit,
    onPlaylist: (PlusWear.PlaylistRow) -> Unit
) {
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = listState,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)
            ) {
                FilledTonalButton(onClick = onSearch, modifier = Modifier.width(64.dp)) {
                    Icon(
                        painter = painterResource(CommonR.drawable.ic_search),
                        contentDescription = stringResource(CommonR.string.search_label),
                        modifier = Modifier.size(22.dp)
                    )
                }
                FilledTonalButton(onClick = onLibrary, modifier = Modifier.width(64.dp)) {
                    Icon(
                        painter = painterResource(R.drawable.ic_plus_library),
                        contentDescription = stringResource(CommonR.string.plus_wear_library),
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
        item {
            SectionLabel(text = stringResource(CommonR.string.playlists_label))
        }
        if (playlists == null) {
            item { CircularProgressIndicator() }
        } else if (playlists.isEmpty()) {
            item {
                Text(
                    text = stringResource(CommonR.string.wearos_no_items),
                    textAlign = TextAlign.Center
                )
            }
        } else {
            items(playlists) { row ->
                ListItem(
                    text = row.title,
                    iconRes = CommonR.drawable.ic_playlists_black,
                    secondaryText = row.subtitle,
                    enabled = row.playable,
                    onClick = { onPlaylist(row) }
                )
            }
        }
    }
}

@Composable
private fun PageDots(pagerState: PagerState, modifier: Modifier) {
    Column(
        modifier = modifier.padding(end = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        for (page in 0 until 3) {
            val selected = pagerState.currentPage == page
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(if (selected) 10.dp else 4.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(if (selected) Color.White else Color.White.copy(alpha = 0.4f))
            )
        }
    }
}

@Composable
private fun VolumeArc(fraction: Float) {
    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        val stroke = 4.dp.toPx()
        val arcSize = Size(size.width - stroke, size.height - stroke)
        val topLeft = Offset(stroke / 2f, stroke / 2f)
        drawArc(
            color = Color.White.copy(alpha = 0.25f),
            startAngle = 140f,
            sweepAngle = 80f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = stroke, cap = StrokeCap.Round)
        )
        val sweep = 80f * fraction.coerceIn(0f, 1f)
        if (sweep > 0f) {
            drawArc(
                color = Color.White,
                startAngle = 220f - sweep,
                sweepAngle = sweep,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
    }
}
