package de.danoeh.antennapod.wearos.sync

import android.content.Context
import androidx.wear.tiles.TileService
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.wearos.PlaylistTileService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

object PlusWearRepository {
    private const val PREFS = "plus_wear"
    private const val KEY_PLAYLISTS = "playlists"

    private val _playlists = MutableStateFlow<List<PlusWear.PlaylistRow>?>(null)
    val playlists: StateFlow<List<PlusWear.PlaylistRow>?> = _playlists

    private val _loadedPlaylistTitle = MutableStateFlow<String?>(null)
    val loadedPlaylistTitle: StateFlow<String?> = _loadedPlaylistTitle

    private val _jumpSeconds = MutableStateFlow(intArrayOf(10, 30))
    val jumpSeconds: StateFlow<IntArray> = _jumpSeconds

    fun handle(context: Context, path: String, data: ByteArray): Boolean {
        if (!PlusWear.isPlusPath(path)) {
            return false
        }
        if (PlusPlayerRepository.handle(path, data)) {
            return true
        }
        when (path) {
            PlusWear.PLAYLISTS -> {
                _playlists.value = PlusWear.playlistsFromBytes(data)
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString(KEY_PLAYLISTS, String(data, Charsets.UTF_8))
                    .apply()
                TileService.getUpdater(context).requestUpdate(PlaylistTileService::class.java)
            }
            PlusWear.LOADED_PLAYLIST -> _loadedPlaylistTitle.value = PlusWear.titleFromBytes(data)
            PlusWear.JUMP_SECONDS -> _jumpSeconds.value = PlusWear.jumpSecondsFromBytes(data)
        }
        return true
    }

    fun cachedPlaylists(context: Context): List<PlusWear.PlaylistRow> {
        _playlists.value?.let { return it }
        val json = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PLAYLISTS, null)
            ?: return emptyList()
        return PlusWear.playlistsFromBytes(json.toByteArray(Charsets.UTF_8))
    }
}
