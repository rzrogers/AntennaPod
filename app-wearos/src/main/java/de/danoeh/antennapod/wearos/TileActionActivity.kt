package de.danoeh.antennapod.wearos

import android.app.Activity
import android.os.Bundle
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.wearos.sync.WearMessageSender
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TileActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val action = intent.getStringExtra(EXTRA_ACTION)
        val context = applicationContext
        if (action == PlaylistTileService.ACTION_OPEN_PHONE) {
            backgroundScope.launch { WearMessageSender.send(context, PlusWear.OPEN_APP_ON_PHONE) }
        } else if (action != null && action.startsWith(PlaylistTileService.ACTION_PLAY_PREFIX)) {
            val playlistId = action.substring(PlaylistTileService.ACTION_PLAY_PREFIX.length).toLongOrNull()
            if (playlistId != null) {
                backgroundScope.launch {
                    WearMessageSender.send(context, PlusWear.playPlaylistPath(playlistId))
                    delay(2.seconds)
                    WearMessageSender.send(context, PlusWear.PLAYLISTS)
                }
            }
        }
        finish()
    }

    companion object {
        const val EXTRA_ACTION = "action"
        private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
