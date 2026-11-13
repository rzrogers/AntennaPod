package de.danoeh.antennapod.wearos

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.EpisodeRowItem
import de.danoeh.antennapod.wearos.composable.ListScaffold
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlusQueueActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                PlusQueueScreen(onPlayed = { finish() })
            }
        }
    }
}

@Composable
fun PlusQueueScreen(onPlayed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val queue by PlusPlayerRepository.queue.collectAsState()
    val loadedTitle by PlusWearRepository.loadedPlaylistTitle.collectAsState()
    var timedOut by remember { mutableStateOf(false) }
    val scrollState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        WearMessageSender.send(context, PlusWear.LOADED_PLAYLIST)
        WearMessageSender.send(context, PlusWear.QUEUE)
        delay(10.seconds)
        if (PlusPlayerRepository.queue.value == null) {
            timedOut = true
        }
    }

    val rows = queue
    ListScaffold(
        titleRes = CommonR.string.queue_label,
        title = loadedTitle?.ifEmpty { null },
        scrollState = scrollState,
        isPhoneSupported = true,
        isTimedOut = timedOut,
        isLoading = rows == null,
        isEmpty = rows?.isEmpty() == true
    ) {
        items(rows!!) { row ->
            EpisodeRowItem(row = row, onClick = {
                scope.launch {
                    WearMessageSender.send(context, WearDataPaths.playPath(row.id))
                    onPlayed()
                }
            })
        }
    }
}
