package de.danoeh.antennapod.wearos

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaRouter2
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material3.FilledTonalButton
import androidx.wear.compose.material3.Icon
import de.danoeh.antennapod.wearos.composable.PlusBlue
import de.danoeh.antennapod.wearos.sync.WatchFiles
import de.danoeh.antennapod.wearos.sync.WatchPlayback
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import de.danoeh.antennapod.wearos.composable.PlusScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.PollWhileStarted
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlinx.coroutines.launch

class OutputActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                OutputScreen()
            }
        }
    }
}

@Composable
fun OutputScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PlusPlayerRepository.player.collectAsState()
    val watchActive by WatchPlayback.active.collectAsState()
    val watchOutput by WatchPlayback.output.collectAsState()
    val moving by WatchPlayback.moving.collectAsState()
    val scrollState = rememberScalingLazyListState()
    var phoneOutput by remember { mutableStateOf<PlusWear.PlayerState?>(null) }
    var earbuds by remember { mutableStateOf(WatchPlayback.earbudsName(context)) }
    var requested by remember { mutableStateOf(false) }

    PollWhileStarted(listOf(PlusWear.PLAYER), intervalMillis = 2000L)
    LaunchedEffect(Unit) {
        while (true) {
            earbuds = WatchPlayback.earbudsName(context)
            delay(1_000)
        }
    }
    LaunchedEffect(state, watchActive) {
        if (!watchActive && state != null) {
            phoneOutput = state
        }
    }
    LaunchedEffect(moving, watchActive) {
        if (requested && moving == null) {
            requested = false
            if (watchActive) {
                (context as? Activity)?.finish()
            }
        }
    }
    val current = phoneOutput
    val outputText = when {
        current == null -> ""
        current.outputType == PlusWear.OUTPUT_BLUETOOTH -> current.outputName.ifEmpty {
            stringResource(CommonR.string.plus_wear_bluetooth_device)
        }
        current.outputType == PlusWear.OUTPUT_WIRED -> stringResource(CommonR.string.plus_wear_wired_headphones)
        else -> stringResource(CommonR.string.plus_wear_phone_speaker)
    }
    val move = moving
    if (move != null && requested) {
        MovingScreen(move)
        return
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CommonR.string.plus_wear_output))
                }
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_phone),
                    iconRes = CommonR.drawable.ic_phone_black,
                    secondaryText = outputText.ifEmpty { null },
                    selected = !watchActive,
                    onClick = {
                        if (watchActive) {
                            WatchPlayback.giveBack(context)
                            (context as? Activity)?.finish()
                        }
                    }
                )
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_watch_speaker),
                    iconRes = R.drawable.ic_plus_watch,
                    enabled = WatchPlayback.hasSpeaker(context),
                    selected = watchActive && watchOutput == WatchPlayback.OUTPUT_SPEAKER,
                    onClick = {
                        if (!(watchActive && watchOutput == WatchPlayback.OUTPUT_SPEAKER)) {
                            requested = true
                            WatchPlayback.start(context, output = WatchPlayback.OUTPUT_SPEAKER)
                        }
                    }
                )
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_watch_earbuds),
                    iconRes = R.drawable.ic_plus_bluetooth,
                    secondaryText = earbuds?.ifEmpty { stringResource(CommonR.string.plus_wear_bluetooth_device) }
                        ?: stringResource(CommonR.string.plus_wear_connect_device),
                    selected = watchActive && watchOutput == WatchPlayback.OUTPUT_EARBUDS,
                    onClick = {
                        if (earbuds == null) {
                            openWatchOutputPicker(context)
                        } else if (!(watchActive && watchOutput == WatchPlayback.OUTPUT_EARBUDS)) {
                            requested = true
                            WatchPlayback.start(context, output = WatchPlayback.OUTPUT_EARBUDS)
                        }
                    }
                )
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_change_on_phone),
                    iconRes = R.drawable.ic_plus_open_on_phone,
                    secondaryText = stringResource(CommonR.string.plus_wear_change_on_phone_summary),
                    onClick = {
                        scope.launch { WearMessageSender.send(context, PlusWear.OUTPUT_PICKER) }
                        Toast.makeText(context, CommonR.string.plus_wear_check_phone, Toast.LENGTH_SHORT).show()
                    }
                )
            }
        }
        PlusScrollIndicator(listState = scrollState)
    }
}

private fun openWatchOutputPicker(context: Context) {
    if (Build.VERSION.SDK_INT >= 34) {
        try {
            if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) {
                return
            }
        } catch (e: RuntimeException) {
            // fall back to Bluetooth settings
        }
    }
    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        .putExtra("EXTRA_CONNECTION_ONLY", true)
        .putExtra("EXTRA_CLOSE_ON_CONNECT", true)
        .putExtra("android.bluetooth.devicepicker.extra.FILTER_TYPE", 1)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, CommonR.string.plus_wear_connect_earbuds, Toast.LENGTH_SHORT).show()
    }
}

@Composable
private fun MovingScreen(move: WatchPlayback.MoveState) {
    val percent = (move.progress.coerceIn(0f, 1f) * 100).toInt()
    val detail = when (move.source) {
        WatchFiles.SOURCE_PHONE_WIFI -> stringResource(CommonR.string.plus_wear_moving_from_phone, percent)
        WatchFiles.SOURCE_WIFI -> stringResource(CommonR.string.plus_wear_moving_wifi, percent)
        WatchFiles.SOURCE_BLUETOOTH -> stringResource(CommonR.string.plus_wear_moving_bluetooth, percent)
        else -> ""
    }
    Column(
        modifier = Modifier.fillMaxSize().background(Color.Black).padding(horizontal = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_plus_watch),
            contentDescription = null,
            tint = PlusBlue,
            modifier = Modifier.size(22.dp)
        )
        Text(
            text = stringResource(CommonR.string.plus_wear_moving_title),
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center
        )
        if (move.source != WatchFiles.SOURCE_NONE) {
            Text(
                text = stringResource(CommonR.string.plus_wear_moving_getting),
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                maxLines = 3
            )
        }
        Spacer(modifier = Modifier.height(6.dp))
        Box(
            modifier = Modifier
                .width(110.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.18f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(move.progress.coerceIn(0f, 1f))
                    .clip(RoundedCornerShape(3.dp))
                    .background(PlusBlue)
            )
        }
        if (detail.isNotEmpty()) {
            Text(text = detail, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(modifier = Modifier.height(6.dp))
        FilledTonalButton(onClick = { WatchPlayback.cancelMove() }) {
            Text(stringResource(CommonR.string.plus_wear_stay_on_phone))
        }
    }
}
