package de.danoeh.antennapod.wearos

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import de.danoeh.antennapod.wearos.composable.PlusScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem

class LibraryActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                LibraryScreen(onOpen = { path ->
                    val intent = when (path) {
                        WATCH_DOWNLOADS -> Intent(this, WatchDownloadsActivity::class.java)
                        PlusWear.PLAYLISTS -> Intent(this, PlaylistListActivity::class.java)
                        WearDataPaths.QUEUE -> Intent(this, PlusQueueActivity::class.java)
                        WearDataPaths.SUBSCRIPTIONS -> Intent(this, FeedListActivity::class.java)
                        WearDataPaths.DOWNLOADS -> PlusEpisodeListActivity.intent(this, PlusWear.EPISODES_DOWNLOADED,
                            getString(NavigationNames.getTitleResForPath(path)))
                        else -> PlusEpisodeListActivity.intent(this, PlusWear.EPISODES_ALL,
                            getString(NavigationNames.getTitleResForPath(path)))
                    }
                    startActivity(intent)
                })
            }
        }
    }
}

private const val WATCH_DOWNLOADS = "watch_downloads"

private val LIBRARY_PATHS = listOf(
    PlusWear.PLAYLISTS,
    WearDataPaths.QUEUE,
    WearDataPaths.SUBSCRIPTIONS,
    WearDataPaths.EPISODES,
    WearDataPaths.DOWNLOADS
)

@Composable
fun LibraryScreen(onOpen: (String) -> Unit) {
    val scrollState = rememberScalingLazyListState()
    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(CommonR.string.plus_wear_library))
                }
            }
            items(LIBRARY_PATHS) { path ->
                ListItem(
                    text = stringResource(NavigationNames.getTitleResForPath(path)),
                    iconRes = NavigationNames.getIconResForPath(path),
                    onClick = { onOpen(path) }
                )
            }
            item {
                ListItem(
                    text = stringResource(CommonR.string.plus_wear_watch_downloads),
                    iconRes = R.drawable.ic_plus_watch,
                    onClick = { onOpen(WATCH_DOWNLOADS) }
                )
            }
        }
        PlusScrollIndicator(listState = scrollState)
    }
}
