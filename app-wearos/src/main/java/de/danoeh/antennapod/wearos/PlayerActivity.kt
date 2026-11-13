package de.danoeh.antennapod.wearos

import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalView
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
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.EdgeMarqueeText
import de.danoeh.antennapod.wearos.composable.PlusAccent
import de.danoeh.antennapod.wearos.composable.PollWhileStarted
import de.danoeh.antennapod.wearos.composable.RingPlayButton
import de.danoeh.antennapod.wearos.composable.RoundButton
import de.danoeh.antennapod.wearos.composable.RoundIconButton
import de.danoeh.antennapod.wearos.composable.SectionLabel
import de.danoeh.antennapod.wearos.composable.formatSpeed
import de.danoeh.antennapod.wearos.composable.isRoundScreen
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
                    onOpen = { target -> startActivity(Intent(this, target)) },
                    onSearch = { query ->
                        startActivity(Intent(this, SearchActivity::class.java)
                            .putExtra(SearchActivity.EXTRA_QUERY, query))
                    }
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
private const val VOLUME_ARC_SWEEP = 50f
private const val VOLUME_ARC_GAP = 4f

@Composable
fun PlayerScreen(onOpen: (Class<*>) -> Unit, onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val round = isRoundScreen()
    val searchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        searchQueryFrom(result.data)?.let(onSearch)
    }
    var lastVolumeLevel by remember { mutableIntStateOf(-1) }
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
        val level = volume ?: return@LaunchedEffect
        if (level.max > 0 && level.level >= level.max && lastVolumeLevel in 0 until level.max) {
            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        }
        lastVolumeLevel = level.level
        val stamp = level.stamp
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
                        val known = PlusPlayerRepository.volume.value
                        if (up && known != null && known.max > 0 && known.level >= known.max) {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                        } else {
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
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
                    round = round,
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
                    round = round,
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
                    onSearch = { searchLauncher.launch(searchInputIntent(context)) },
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
        if (pagerState.currentPage == PAGE_LISTS && !pagerState.isScrollInProgress) {
            ScrollIndicator(state = listState, modifier = Modifier.align(Alignment.CenterEnd))
        } else {
            PageDots(pagerState = pagerState, modifier = Modifier.align(Alignment.CenterEnd))
        }
        val level = volume
        if (showVolume && level != null && level.max > 0) {
            VolumeIndicator(fraction = level.level.toFloat() / level.max.toFloat(), round = round)
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
    round: Boolean,
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
            EdgeMarqueeText(
                text = title,
                fontSize = 16 * textScale,
                fitPadding = if (round) unit * 24 else unit * 8,
                fontWeight = FontWeight.Bold
            )
            EdgeMarqueeText(
                text = feedTitle,
                fontSize = 13 * textScale,
                fitPadding = if (round) unit * 40 else unit * 8,
                color = PlusAccent
            )
        }
    }
}

@Composable
private fun MainPage(
    unit: Dp,
    round: Boolean,
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
            EdgeMarqueeText(
                text = title,
                fontSize = 14 * textScale,
                fitPadding = if (round) unit * 22 else unit * 8,
                fontWeight = FontWeight.SemiBold
            )
            EdgeMarqueeText(
                text = feedTitle,
                fontSize = 12 * textScale,
                fitPadding = if (round) unit * 24 else unit * 8,
                color = PlusAccent
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
                        painter = painterResource(R.drawable.ic_plus_search),
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
private fun VolumeIndicator(fraction: Float, round: Boolean) {
    val filled = fraction.coerceIn(0f, 1f)
    Canvas(modifier = Modifier.fillMaxSize().padding(4.dp)) {
        val stroke = 6.dp.toPx()
        if (round) {
            val arcSize = Size(size.width - stroke, size.height - stroke)
            val topLeft = Offset(stroke / 2f, stroke / 2f)
            val bottom = 180f - VOLUME_ARC_SWEEP / 2f
            val sweep = VOLUME_ARC_SWEEP * filled
            val gap = if (sweep > 0f && sweep < VOLUME_ARC_SWEEP) VOLUME_ARC_GAP else 0f
            if (sweep + gap < VOLUME_ARC_SWEEP) {
                drawArc(
                    color = Color.White.copy(alpha = 0.3f),
                    startAngle = bottom + sweep + gap,
                    sweepAngle = VOLUME_ARC_SWEEP - sweep - gap,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
            if (sweep > 0f) {
                drawArc(
                    color = Color.White,
                    startAngle = bottom,
                    sweepAngle = sweep,
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(width = stroke, cap = StrokeCap.Round)
                )
            }
        } else {
            val length = size.height * 0.3f
            val x = stroke / 2f
            val top = (size.height - length) / 2f
            val bottom = top + length
            val fillTop = bottom - length * filled
            drawLine(
                color = Color.White.copy(alpha = 0.3f),
                start = Offset(x, top),
                end = Offset(x, bottom),
                strokeWidth = stroke,
                cap = StrokeCap.Round
            )
            if (filled > 0f) {
                drawLine(
                    color = Color.White,
                    start = Offset(x, bottom),
                    end = Offset(x, fillTop),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }
        }
    }
}
