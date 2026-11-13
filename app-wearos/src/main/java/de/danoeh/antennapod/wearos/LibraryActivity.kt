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
import androidx.wear.compose.material3.ScrollIndicator
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
                        PlusWear.PLAYLISTS -> Intent(this, PlaylistListActivity::class.java)
                        WearDataPaths.QUEUE -> Intent(this, PlusQueueActivity::class.java)
                        WearDataPaths.SUBSCRIPTIONS -> Intent(this, FeedListActivity::class.java)
                        else -> Intent(this, EpisodeListActivity::class.java).apply {
                            putExtra(EpisodeListActivity.EXTRA_PATH, path)
                        }
                    }
                    startActivity(intent)
                })
            }
        }
    }
}

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
        }
        ScrollIndicator(state = scrollState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}
