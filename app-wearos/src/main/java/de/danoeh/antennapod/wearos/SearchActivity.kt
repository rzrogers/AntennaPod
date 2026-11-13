package de.danoeh.antennapod.wearos

import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.CircularProgressIndicator
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.ScrollIndicator
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.input.RemoteInputIntentHelper
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.EpisodeRowItem
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.SectionLabel
import de.danoeh.antennapod.wearos.sync.PlusPlayerRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlinx.coroutines.launch

class SearchActivity : ComponentActivity() {
    companion object {
        const val EXTRA_QUERY = "query"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val initialQuery = intent.getStringExtra(EXTRA_QUERY)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                SearchScreen(
                    initialQuery = initialQuery,
                    onOpenFeed = { feedId ->
                        startActivity(Intent(this, EpisodeListActivity::class.java).apply {
                            putExtra(EpisodeListActivity.EXTRA_PATH, WearDataPaths.feedEpisodesPath(feedId))
                        })
                    },
                    onPlayed = {
                        startActivity(Intent(this, PlayerActivity::class.java)
                            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
                        finish()
                    }
                )
            }
        }
    }
}

private const val SEARCH_KEY = "query"
private const val EXTRA_DISALLOW_EMOJI = "android.support.wearable.input.extra.DISALLOW_EMOJI"
private const val EXTRA_INPUT_ACTION_TYPE = "android.support.wearable.input.extra.INPUT_ACTION_TYPE"

fun searchInputIntent(context: Context): Intent {
    val intent = RemoteInputIntentHelper.createActionRemoteInputIntent()
    val extras = Bundle().apply {
        putBoolean(EXTRA_DISALLOW_EMOJI, true)
        putInt(EXTRA_INPUT_ACTION_TYPE, EditorInfo.IME_ACTION_SEARCH)
    }
    val remoteInput = RemoteInput.Builder(SEARCH_KEY)
        .setLabel(context.getString(CommonR.string.plus_wear_search_prompt))
        .addExtras(extras)
        .build()
    RemoteInputIntentHelper.putRemoteInputsExtra(intent, listOf(remoteInput))
    return intent
}

fun searchQueryFrom(data: Intent?): String? {
    if (data == null) {
        return null
    }
    val text = RemoteInput.getResultsFromIntent(data)?.getCharSequence(SEARCH_KEY)?.toString()?.trim()
    return if (text.isNullOrEmpty()) null else text
}

@Composable
fun SearchScreen(initialQuery: String?, onOpenFeed: (Long) -> Unit, onPlayed: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val results by PlusPlayerRepository.search.collectAsState()
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var started by rememberSaveable { mutableStateOf(false) }
    val scrollState = rememberScalingLazyListState()

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = searchQueryFrom(result.data)
        if (text != null) {
            query = text
            PlusPlayerRepository.clearSearch()
            scope.launch { WearMessageSender.send(context, PlusWear.searchPath(text)) }
        }
    }
    val askForQuery: () -> Unit = { launcher.launch(searchInputIntent(context)) }
    LaunchedEffect(Unit) {
        if (!started) {
            started = true
            PlusPlayerRepository.clearSearch()
            if (initialQuery != null) {
                query = initialQuery
                WearMessageSender.send(context, PlusWear.searchPath(initialQuery))
            } else {
                askForQuery()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ScalingLazyColumn(
            modifier = Modifier.fillMaxWidth(),
            state = scrollState,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                ListHeader(modifier = Modifier.fillMaxWidth()) {
                    Text(query ?: stringResource(CommonR.string.search_label))
                }
            }
            item {
                ListItem(
                    text = stringResource(
                        if (query == null) CommonR.string.search_label else CommonR.string.plus_wear_search_again
                    ),
                    iconRes = R.drawable.ic_plus_search,
                    onClick = askForQuery
                )
            }
            val found = results
            if (query != null && found == null) {
                item { CircularProgressIndicator() }
            } else if (found != null) {
                if (found.feeds.isEmpty() && found.episodes.isEmpty()) {
                    item {
                        Text(text = stringResource(CommonR.string.wearos_no_items), textAlign = TextAlign.Center)
                    }
                }
                if (found.feeds.isNotEmpty()) {
                    item { SectionLabel(text = stringResource(CommonR.string.plus_wear_shows)) }
                    items(found.feeds) { feed ->
                        ListItem(
                            text = feed.title,
                            iconRes = CommonR.drawable.ic_subscriptions_black,
                            onClick = { onOpenFeed(feed.id) }
                        )
                    }
                }
                if (found.episodes.isNotEmpty()) {
                    item { SectionLabel(text = stringResource(CommonR.string.episodes_label)) }
                    items(found.episodes) { row ->
                        EpisodeRowItem(row = row, onClick = {
                            scope.launch {
                                WearMessageSender.send(context, WearDataPaths.playPath(row.id))
                                onPlayed()
                            }
                        })
                    }
                }
            }
        }
        ScrollIndicator(state = scrollState, modifier = Modifier.align(Alignment.CenterEnd))
    }
}
