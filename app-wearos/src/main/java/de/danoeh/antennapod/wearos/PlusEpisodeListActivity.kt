package de.danoeh.antennapod.wearos

import android.content.Context
import android.content.Intent
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
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class PlusEpisodeListActivity : ComponentActivity() {
    companion object {
        private const val EXTRA_PATH = "plus_path"
        private const val EXTRA_TITLE = "plus_title"

        fun intent(context: Context, path: String, title: String): Intent =
            Intent(context, PlusEpisodeListActivity::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_TITLE, title)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val path = intent.getStringExtra(EXTRA_PATH) ?: PlusWear.EPISODES_ALL
        val title = intent.getStringExtra(EXTRA_TITLE) ?: ""
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                PlusEpisodeListScreen(
                    path = path,
                    title = title,
                    onPlayed = {
                        startActivity(Intent(this, PlayerActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                    }
                )
            }
        }
    }
}

@Composable
fun PlusEpisodeListScreen(path: String, title: String, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val lists by PlusPlayerRepository.episodeLists.collectAsState()
    var timedOut by remember { mutableStateOf(false) }
    val scrollState = rememberScalingLazyListState()
    val showFeed = !path.startsWith(PlusWear.EPISODES_FEED_PREFIX)

    LaunchedEffect(path) {
        WearMessageSender.send(context, path)
        delay(10.seconds)
        if (PlusPlayerRepository.episodeLists.value[path] == null) {
            timedOut = true
        }
    }

    val rows = lists[path]
    ListScaffold(
        titleRes = CommonR.string.episodes_label,
        title = title.ifEmpty { null },
        scrollState = scrollState,
        isPhoneSupported = true,
        isTimedOut = timedOut,
        isLoading = rows == null,
        isEmpty = rows?.isEmpty() == true
    ) {
        items(rows!!) { row ->
            EpisodeRowItem(row = row, showFeed = showFeed, onClick = {
                scope.launch {
                    WearMessageSender.send(context, WearDataPaths.playPath(row.id))
                    onPlayed()
                }
            })
        }
    }
}
