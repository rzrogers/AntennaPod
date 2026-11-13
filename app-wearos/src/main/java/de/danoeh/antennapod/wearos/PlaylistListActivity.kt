package de.danoeh.antennapod.wearos

import android.app.Application
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material3.MaterialTheme
import androidx.wear.compose.material3.dynamicColorScheme
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearConnectionUtils
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.composable.ListItem
import de.danoeh.antennapod.wearos.composable.ListScaffold
import de.danoeh.antennapod.wearos.sync.PlusWearRepository
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class PlaylistListActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val viewModel = ViewModelProvider(this)[PlaylistListViewModel::class.java]

        setContent {
            MaterialTheme(colorScheme = dynamicColorScheme(this) ?: MaterialTheme.colorScheme) {
                val uiState by viewModel.uiState.collectAsState()
                PlaylistListScreen(uiState = uiState, onPlay = viewModel::play)
            }
        }
    }
}

data class PlaylistListUiState(
    val playlists: List<PlusWear.PlaylistRow>? = null,
    val isPhoneSupported: Boolean = true,
    val isTimedOut: Boolean = false
)

class PlaylistListViewModel(application: Application) : AndroidViewModel(application) {
    private val _uiState = MutableStateFlow(PlaylistListUiState())
    val uiState: StateFlow<PlaylistListUiState> = _uiState

    init {
        viewModelScope.launch {
            PlusWearRepository.playlists.collect { rows ->
                _uiState.update { it.copy(playlists = rows) }
            }
        }

        viewModelScope.launch {
            val isSupported = withContext(Dispatchers.IO) {
                WearConnectionUtils.isPhoneSupported(getApplication())
            }
            if (!isSupported) {
                _uiState.update { it.copy(isPhoneSupported = false) }
                return@launch
            }
            WearMessageSender.send(getApplication(), PlusWear.PLAYLISTS)
            val received = withTimeoutOrNull(10.seconds) {
                PlusWearRepository.playlists.drop(1).first()
            }
            if (received == null && PlusWearRepository.playlists.value == null) {
                _uiState.update { it.copy(isTimedOut = true) }
            }
        }
    }

    fun play(row: PlusWear.PlaylistRow) {
        viewModelScope.launch(Dispatchers.IO) {
            WearMessageSender.send(getApplication(), PlusWear.playPlaylistPath(row.id))
            delay(2.seconds)
            WearMessageSender.send(getApplication(), PlusWear.PLAYLISTS)
        }
    }
}

@Composable
fun PlaylistListScreen(uiState: PlaylistListUiState, onPlay: (PlusWear.PlaylistRow) -> Unit) {
    val scrollState = rememberScalingLazyListState()
    val playlists = uiState.playlists

    ListScaffold(
        titleRes = CommonR.string.playlists_label,
        scrollState = scrollState,
        isPhoneSupported = uiState.isPhoneSupported,
        isTimedOut = uiState.isTimedOut,
        isLoading = playlists == null,
        isEmpty = playlists?.isEmpty() == true
    ) {
        items(playlists!!) { row ->
            ListItem(
                text = row.title,
                iconRes = CommonR.drawable.ic_playlists_black,
                secondaryText = row.subtitle,
                enabled = row.playable,
                onClick = { onPlay(row) }
            )
        }
    }
}
