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
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.graphics.graphicsLayer
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
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.EdgeMarqueeText
import de.danoeh.antennapod.wearos.composable.PlusAccent
import de.danoeh.antennapod.wearos.composable.PlusBlue
import de.danoeh.antennapod.wearos.composable.PlusPagerIndicator
import de.danoeh.antennapod.wearos.composable.PlusVolumeIndicator
import de.danoeh.antennapod.wearos.composable.PollWhileStarted
import de.danoeh.antennapod.wearos.composable.RingPlayButton
import de.danoeh.antennapod.wearos.composable.RoundButton
import de.danoeh.antennapod.wearos.composable.RoundIconButton
import de.danoeh.antennapod.wearos.composable.SectionLabel
import de.danoeh.antennapod.wearos.composable.formatSpeed
import de.danoeh.antennapod.wearos.composable.isRoundScreen
import de.danoeh.antennapod.wearos.composable.rememberClockText
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.WatchPlayback
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.drop
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
private const val VOLUME_SYNC_DELAY_MILLIS = 400L
private const val LIST_TICK_PIXELS = 48f
private const val ART_RETRY_CHECK_MILLIS = 3_000L

@Composable
fun PlayerScreen(onOpen: (Class<*>) -> Unit, onSearch: (String) -> Unit) {
    val context = LocalContext.current
    val view = LocalView.current
    val round = isRoundScreen()
    val searchLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        searchQueryFrom(result.data)?.let(onSearch)
    }
    var volumeLevel by remember { mutableIntStateOf(-1) }
    var volumeMax by remember { mutableIntStateOf(0) }
    var lastStepAt by remember { mutableLongStateOf(0L) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var listTickPixels by remember { mutableFloatStateOf(0f) }
    var listAtEnd by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val state by PlusPlayerRepository.player.collectAsState()
    val art by PlusPlayerRepository.art.collectAsState()
    val jumpSeconds by PlusWearRepository.jumpSeconds.collectAsState()
    val playlists by PlusWearRepository.playlists.collectAsState()
    val volume by PlusPlayerRepository.volume.collectAsState()
    val watchActive by WatchPlayback.active.collectAsState()
    val watchOutput by WatchPlayback.output.collectAsState()
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
        val key = PlusPlayerRepository.artKey(itemId, PlusPlayerRepository.ART_LARGE)
        while (itemId > 0 && !PlusPlayerRepository.art.value.containsKey(key)) {
            PlusPlayerRepository.requestArt(context, itemId, PlusPlayerRepository.ART_LARGE)
            delay(ART_RETRY_CHECK_MILLIS)
        }
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
    LaunchedEffect(state?.volumeMax) {
        val known = state ?: return@LaunchedEffect
        if (volumeLevel < 0 && known.volumeMax > 0) {
            volumeLevel = known.volume
            volumeMax = known.volumeMax
        }
    }
    LaunchedEffect(volume?.stamp) {
        val reply = volume ?: return@LaunchedEffect
        val wait = VOLUME_SYNC_DELAY_MILLIS - (SystemClock.elapsedRealtime() - lastStepAt)
        if (wait > 0) {
            delay(wait)
        }
        volumeMax = reply.max
        volumeLevel = PlusPlayerRepository.volume.value?.level ?: reply.level
    }
    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt > 0) {
            showVolume = true
            delay(VOLUME_SHOW_MILLIS)
            showVolume = false
        }
    }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.drop(1).collect {
            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
        }
    }
    val stepVolume: (Boolean) -> Unit = { up ->
        val max = if (volumeMax > 0) volumeMax else state?.volumeMax ?: 0
        val now = SystemClock.elapsedRealtime()
        lastStepAt = now
        volumeShownAt = now
        if (max <= 0) {
            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            scope.launch { WearMessageSender.send(context, if (up) PlusWear.VOLUME_UP else PlusWear.VOLUME_DOWN) }
        } else {
            val currentLevel = if (volumeLevel >= 0) volumeLevel else state?.volume ?: 0
            val next = (currentLevel + if (up) 1 else -1).coerceIn(0, max)
            if (next != currentLevel) {
                volumeLevel = next
                volumeMax = max
                view.performHapticFeedback(
                    if (next == max || next == 0) HapticFeedbackConstants.LONG_PRESS
                    else HapticFeedbackConstants.CLOCK_TICK
                )
                scope.launch {
                    WearMessageSender.send(context, if (up) PlusWear.VOLUME_UP else PlusWear.VOLUME_DOWN)
                }
            }
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
                    scope.launch {
                        val requested = event.verticalScrollPixels
                        val consumed = listState.scrollBy(requested)
                        listTickPixels += consumed
                        if (abs(listTickPixels) >= LIST_TICK_PIXELS) {
                            listTickPixels %= LIST_TICK_PIXELS
                            view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        val hitEnd = abs(consumed) < abs(requested) - 0.5f
                        if (hitEnd && !listAtEnd) {
                            view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                        }
                        listAtEnd = hitEnd
                    }
                } else {
                    rotaryPixels += event.verticalScrollPixels
                    while (abs(rotaryPixels) >= VOLUME_STEP_PIXELS) {
                        val up = rotaryPixels > 0
                        rotaryPixels -= if (up) VOLUME_STEP_PIXELS else -VOLUME_STEP_PIXELS
                        stepVolume(up)
                    }
                }
                true
            }
            .focusRequester(focusRequester)
            .focusable()
    ) {
        val unit = maxWidth / 192f
        val textScale = maxWidth.value / 192f
        val pagePosition = pagerState.currentPage + pagerState.currentPageOffsetFraction
        val artAlpha = (PAGE_LISTS - pagePosition).coerceIn(0f, 1f)
        if (artAlpha > 0f) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = artAlpha }) {
                ArtBackground(bitmap = bitmap, dim = 0.55f + 0.15f * (1f - pagePosition).coerceIn(0f, 1f))
            }
        }
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
                    watchOutputLabel = when {
                        !watchActive -> null
                        watchOutput == WatchPlayback.OUTPUT_EARBUDS ->
                            stringResource(CommonR.string.plus_wear_watch_earbuds)
                        else -> stringResource(CommonR.string.plus_wear_watch_speaker)
                    },
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
        PlusPagerIndicator(pagerState = pagerState, listState = listState, listPage = PAGE_LISTS, round = round)
        val volumeAlpha by animateFloatAsState(if (showVolume) 1f else 0f, label = "volumeAlpha")
        if (volumeMax > 0) {
            Box(modifier = Modifier.fillMaxSize().graphicsLayer { alpha = volumeAlpha }) {
                PlusVolumeIndicator(level = volumeLevel.coerceAtLeast(0), max = volumeMax, round = round)
            }
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
        Column(
            modifier = Modifier.fillMaxSize().padding(top = unit * 12),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = rememberClockText(withAmPm = false),
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
    watchOutputLabel: String?,
    onPlayPause: () -> Unit,
    onJumpBack: () -> Unit,
    onJumpForward: () -> Unit,
    onOutput: () -> Unit,
    onQueue: () -> Unit,
    onSpeed: () -> Unit,
    onSpeedLong: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
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
                fontWeight = FontWeight.SemiBold,
                startPadding = if (round) unit * 30 else unit * 8
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
                    iconRes = if (watchOutputLabel != null) {
                        R.drawable.ic_plus_watch
                    } else {
                        CommonR.drawable.ic_phone_black
                    },
                    contentDescription = stringResource(CommonR.string.plus_wear_output),
                    iconSize = unit * 18,
                    background = if (watchOutputLabel != null) PlusBlue else Color.White.copy(alpha = 0.14f),
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
            if (watchOutputLabel != null) {
                Text(
                    text = watchOutputLabel,
                    color = PlusWatchLabel,
                    fontSize = (9 * textScale).sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

private val PlusWatchLabel = Color(0xFF4DA8E0)

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
                PillIconButton(
                    iconRes = R.drawable.ic_plus_search,
                    contentDescription = stringResource(CommonR.string.search_label),
                    onClick = onSearch
                )
                PillIconButton(
                    iconRes = R.drawable.ic_plus_library,
                    contentDescription = stringResource(CommonR.string.plus_wear_library),
                    onClick = onLibrary
                )
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
private fun PillIconButton(iconRes: Int, contentDescription: String, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 68.dp, height = 48.dp)
            .clip(RoundedCornerShape(24.dp))
            .background(MaterialTheme.colorScheme.surfaceContainer)
            .clickable(onClickLabel = contentDescription, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = contentDescription,
            modifier = Modifier.size(24.dp)
        )
    }
}
