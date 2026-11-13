package de.danoeh.antennapod.wearos

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
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
import androidx.wear.compose.material3.ScrollIndicator
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
    val scrollState = rememberScalingLazyListState()

    PollWhileStarted(listOf(PlusWear.PLAYER), intervalMillis = 2000L)
    val current = state
    val outputText = when {
        current == null -> ""
        current.outputType == PlusWear.OUTPUT_BLUETOOTH -> current.outputName.ifEmpty {
            stringResource(CommonR.string.plus_wear_bluetooth_device)
        }
        current.outputType == PlusWear.OUTPUT_WIRED -> stringResource(CommonR.string.plus_wear_wired_headphones)
        else -> stringResource(CommonR.string.plus_wear_phone_speaker)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CommonR.string.plus_wear_playing_on))
                }
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_phone),
                    iconRes = CommonR.drawable.ic_phone_black,
                    secondaryText = outputText.ifEmpty { null },
                    onClick = {}
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
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_this_watch),
                    iconRes = R.drawable.ic_plus_watch,
                    secondaryText = stringResource(CommonR.string.plus_wear_this_watch_summary),
                    enabled = false,
                    onClick = {}
                )
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_connect_device),
                    iconRes = R.drawable.ic_plus_bluetooth,
                    secondaryText = stringResource(CommonR.string.plus_wear_connect_device_summary),
                    enabled = false,
                    onClick = {}
                )
            }
        }
        ScrollIndicator(state = scrollState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}
