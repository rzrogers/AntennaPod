package de.danoeh.antennapod.wearos

import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.FilledTonalIconButton
import androidx.wear.compose.material3.Icon
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.PlusChip
import de.danoeh.antennapod.wearos.composable.PlusSecondaryText
import de.danoeh.antennapod.wearos.composable.PollWhileStarted
import de.danoeh.antennapod.wearos.composable.formatShortDuration
import de.danoeh.antennapod.wearos.composable.formatSpeed
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

class SpeedSleepActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                SpeedSleepScreen()
            }
        }
    }
}

private val SLEEP_CHOICES = listOf(PlusWear.SLEEP_OFF, 15, 30, 60, PlusWear.SLEEP_END_OF_EPISODE)

private fun sleepLabel(context: Context, minutes: Int): String = when (minutes) {
    PlusWear.SLEEP_OFF -> context.getString(CommonR.string.plus_wear_sleep_off)
    PlusWear.SLEEP_END_OF_EPISODE -> context.getString(CommonR.string.plus_wear_end_of_episode)
    else -> formatShortDuration(context, minutes * 60_000L)
}

@Composable
fun SpeedSleepScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by PlusPlayerRepository.player.collectAsState()
    var chosenSpeed by remember { mutableStateOf<Float?>(null) }
    val scrollState = rememberScalingLazyListState()

    PollWhileStarted(listOf(PlusWear.PLAYER))
    val phoneSpeed = state?.speed ?: 1f
    LaunchedEffect(phoneSpeed) {
        val chosen = chosenSpeed
        if (chosen != null && abs(chosen - phoneSpeed) < 0.001f) {
            chosenSpeed = null
        }
    }
    val speed = chosenSpeed ?: phoneSpeed
    val presets = state?.speeds?.toList() ?: emptyList()

    fun setSpeed(newSpeed: Float) {
        val rounded = ((newSpeed.coerceIn(0.5f, 4f) * 20f).roundToInt()) / 20f
        chosenSpeed = rounded
        scope.launch { WearMessageSender.send(context, PlusWear.speedPath(rounded)) }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CommonR.string.plus_wear_speed))
                }
            }
            item {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilledTonalIconButton(onClick = { setSpeed(speed - 0.05f) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus_minus),
                            contentDescription = stringResource(CommonR.string.plus_wear_slower)
                        )
                    }
                    Text(
                        text = formatSpeed(speed),
                        modifier = Modifier.width(64.dp),
                        textAlign = TextAlign.Center,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    FilledTonalIconButton(onClick = { setSpeed(speed + 0.05f) }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_plus_add),
                            contentDescription = stringResource(CommonR.string.plus_wear_faster)
                        )
                    }
                }
            }
            items(presets.chunked(3)) { chunk ->
                Row(
                    modifier = Modifier.padding(vertical = 2.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    for (preset in chunk) {
                        val selected = abs(preset - speed) < 0.001f
                        Box(
                            modifier = Modifier
                                .size(width = 48.dp, height = 32.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(if (selected) MaterialTheme.colorScheme.primary else PlusChip)
                                .clickable { setSpeed(preset) },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = formatSpeed(preset),
                                color = if (selected) MaterialTheme.colorScheme.onPrimary else Color.White,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CommonR.string.sleep_timer_label))
                }
            }
            val current = state
            if (current != null && current.sleepMillis >= 0) {
                item {
                    Text(
                        text = if (current.sleepEpisode) {
                            stringResource(CommonR.string.plus_wear_sleep_ends_episode)
                        } else {
                            stringResource(CommonR.string.plus_wear_sleep_stops_in,
                                formatShortDuration(context, current.sleepMillis + 59_999L))
                        },
                        color = PlusSecondaryText,
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )
                }
            }
            items(SLEEP_CHOICES) { minutes ->
                ListItem(
                    text = sleepLabel(context, minutes),
                    onClick = {
                        scope.launch { WearMessageSender.send(context, PlusWear.sleepPath(minutes)) }
                    }
                )
            }
        }
        ScrollIndicator(state = scrollState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}
