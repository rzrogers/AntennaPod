package de.danoeh.antennapod.wearos

import android.content.Context
import android.os.Bundle
import android.text.format.Formatter
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.Text
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.PlusScrollIndicator
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WatchDownloads
import de.danoeh.antennapod.wearos.sync.WatchFiles
import de.danoeh.antennapod.wearos.sync.WatchStore
import de.danoeh.antennapod.wearos.sync.WearMessageSender

class WatchDownloadsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                WatchDownloadsScreen()
            }
        }
    }
}

@Composable
private fun amountLabel(minutes: Int): String = when (minutes) {
    WatchStore.AMOUNT_OFF -> stringResource(CommonR.string.plus_wear_amount_off)
    WatchStore.AMOUNT_ALL -> stringResource(CommonR.string.plus_wear_amount_all)
    60 -> stringResource(CommonR.string.plus_wear_amount_hour)
    else -> stringResource(CommonR.string.plus_wear_amount_hours, minutes / 60)
}

@Composable
private fun spanLabel(millis: Long): String {
    val minutes = (millis / 60_000L).toInt()
    return when {
        minutes < 60 -> stringResource(CommonR.string.plus_wear_minutes, minutes)
        minutes % 60 == 0 -> stringResource(CommonR.string.plus_wear_hours, minutes / 60)
        else -> stringResource(CommonR.string.plus_wear_hours_minutes, minutes / 60, minutes % 60)
    }
}

private fun storageText(context: Context): Pair<String, String> = Pair(
    Formatter.formatShortFileSize(context, WatchFiles.usedBytes(context)),
    Formatter.formatShortFileSize(context, WatchFiles.totalBytes(context))
)

@Composable
fun WatchDownloadsScreen() {
    val context = LocalContext.current
    val changed by WatchDownloads.changed.collectAsState()
    val livePlaylists by PlusWearRepository.playlists.collectAsState()
    var version by remember { mutableIntStateOf(0) }
    var selected by remember { mutableStateOf<PlusWear.PlaylistRow?>(null) }
    var useLte by remember { mutableStateOf(WatchStore.useLte(context)) }
    val listState = rememberScalingLazyListState()
    val amountState = rememberScalingLazyListState()

    LaunchedEffect(Unit) {
        WearMessageSender.send(context, PlusWear.PLAYLISTS)
        if (WearMessageSender.isPhoneNearby(context)) {
            WatchDownloads.refreshPlans(context.applicationContext)
        }
    }
    val playlists = remember(livePlaylists, changed) {
        livePlaylists?.takeIf { it.isNotEmpty() } ?: PlusWearRepository.cachedPlaylists(context)
    }
    val storage = remember(changed, version) { storageText(context) }
    val row = selected
    BackHandler(enabled = row != null) { selected = null }

    Box(modifier = Modifier.fillMaxSize()) {
        if (row == null) {
            ScalingLazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = listState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(CommonR.string.plus_wear_watch_downloads))
                    }
                }
                item {
                    Text(
                        text = stringResource(CommonR.string.plus_wear_storage_used, storage.first, storage.second),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
                items(playlists) { playlist ->
                    val minutes = remember(playlist.id, version) { WatchStore.amount(context, playlist.id) }
                    ListItem(
                        text = playlist.title,
                        secondaryText = amountLabel(minutes),
                        onClick = { selected = playlist }
                    )
                }
                item {
                    ListItem(
                        text = stringResource(CommonR.string.plus_wear_use_lte),
                        secondaryText = stringResource(
                            if (useLte) CommonR.string.plus_wear_lte_allowed else CommonR.string.plus_wear_wifi_only
                        ),
                        selected = useLte,
                        onClick = {
                            useLte = !useLte
                            WatchStore.setUseLte(context, useLte)
                            WatchDownloads.request(context.applicationContext)
                        }
                    )
                }
            }
            PlusScrollIndicator(listState = listState)
        } else {
            val current = remember(row.id, version) { WatchStore.amount(context, row.id) }
            val plan = remember(row.id, changed) { WatchStore.plan(context, row.id) }
            ScalingLazyColumn(
                modifier = Modifier.fillMaxWidth(),
                state = amountState,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    ListHeader(modifier = Modifier.fillMaxWidth()) {
                        Text(row.title)
                    }
                }
                item {
                    Text(
                        text = stringResource(CommonR.string.plus_wear_keep_on_watch),
                        style = MaterialTheme.typography.labelSmall,
                        textAlign = TextAlign.Center
                    )
                }
                items(WatchStore.AMOUNTS) { minutes ->
                    val summary = when {
                        minutes == WatchStore.AMOUNT_OFF -> null
                        plan == null -> stringResource(CommonR.string.plus_wear_amount_unknown)
                        else -> {
                            val wanted = WatchStore.wanted(plan, minutes)
                            pluralStringResource(
                                CommonR.plurals.plus_wear_amount_summary,
                                wanted.size,
                                wanted.size,
                                spanLabel(wanted.sumOf { WatchStore.timeLeft(it) })
                            )
                        }
                    }
                    ListItem(
                        text = amountLabel(minutes),
                        secondaryText = summary,
                        selected = minutes == current,
                        onClick = {
                            WatchStore.setAmount(context, row.id, minutes)
                            version++
                            selected = null
                            WatchDownloads.request(context.applicationContext)
                        }
                    )
                }
            }
            PlusScrollIndicator(listState = amountState)
        }
    }
}
