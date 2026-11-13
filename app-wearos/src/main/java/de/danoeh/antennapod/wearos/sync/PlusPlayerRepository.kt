package de.danoeh.antennapod.wearos.sync

import android.content.Context
import android.graphics.BitmapFactory
import android.os.SystemClock
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

data class VolumeLevel(val level: Int, val max: Int, val stamp: Long)

object PlusPlayerRepository {
    const val ART_LARGE = 400
    const val ART_SMALL = 96
    private const val MAX_ART = 60
    private const val RETRY_ART_MILLIS = 30_000L

    private val _player = MutableStateFlow<PlusWear.PlayerState?>(null)
    val player: StateFlow<PlusWear.PlayerState?> = _player

    private val _queue = MutableStateFlow<List<PlusWear.EpisodeRow>?>(null)
    val queue: StateFlow<List<PlusWear.EpisodeRow>?> = _queue

    private val _search = MutableStateFlow<PlusWear.SearchResults?>(null)
    val search: StateFlow<PlusWear.SearchResults?> = _search

    private val _volume = MutableStateFlow<VolumeLevel?>(null)
    val volume: StateFlow<VolumeLevel?> = _volume

    private val _art = MutableStateFlow<Map<String, ImageBitmap>>(emptyMap())
    val art: StateFlow<Map<String, ImageBitmap>> = _art

    private val requestedArt = ConcurrentHashMap<String, Long>()

    fun artKey(itemId: Long, size: Int): String = "$itemId/$size"

    fun handle(path: String, data: ByteArray): Boolean {
        when {
            path == PlusWear.PLAYER -> _player.value = PlusWear.playerStateFromBytes(data)
            path == PlusWear.QUEUE -> _queue.value = PlusWear.episodeRowsFromBytes(data)
            path == PlusWear.SEARCH_RESULTS -> _search.value = PlusWear.searchResultsFromBytes(data)
            path == PlusWear.VOLUME -> {
                val values = PlusWear.volumeFromBytes(data)
                _volume.value = VolumeLevel(values[0], values[1], SystemClock.elapsedRealtime())
                _player.update { it?.withVolume(values[0], values[1]) }
            }
            path.startsWith(PlusWear.ART_PREFIX) -> {
                val key = path.substring(PlusWear.ART_PREFIX.length)
                if (data.isNotEmpty()) {
                    val bitmap = BitmapFactory.decodeByteArray(data, 0, data.size)
                    if (bitmap != null) {
                        _art.update { current ->
                            val trimmed = if (current.size >= MAX_ART) current.entries.drop(1)
                                .associate { it.key to it.value } else current
                            trimmed + (key to bitmap.asImageBitmap())
                        }
                    }
                }
            }
            else -> return false
        }
        return true
    }

    suspend fun requestArt(context: Context, itemId: Long, size: Int) {
        if (itemId <= 0) {
            return
        }
        val key = artKey(itemId, size)
        val now = SystemClock.elapsedRealtime()
        val last = requestedArt[key]
        if (_art.value.containsKey(key) || (last != null && now - last < RETRY_ART_MILLIS)) {
            return
        }
        requestedArt[key] = now
        WearMessageSender.send(context, PlusWear.artPath(itemId, size))
    }

    fun setPlaying(playing: Boolean) {
        _player.update { it?.withPlaying(playing) }
    }

    fun clearSearch() {
        _search.value = null
    }
}
