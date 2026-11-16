package de.danoeh.antennapod.wearos

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import de.danoeh.antennapod.wearos.sync.WatchPlayback

class WatchPlaybackService : MediaSessionService() {
    private var player: ExoPlayer? = null
    private var session: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        val newPlayer = ExoPlayer.Builder(this)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .build(),
                true
            )
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
        newPlayer.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    WatchPlayback.onEnded(applicationContext)
                }
            }

            override fun onIsPlayingChanged(isPlaying: Boolean) {
                if (!isPlaying) {
                    WatchPlayback.syncPosition(applicationContext)
                }
            }
        })
        player = newPlayer
        session = MediaSession.Builder(this, newPlayer).build()
        WatchPlayback.attach(newPlayer)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onDestroy() {
        player?.let { WatchPlayback.detach(applicationContext, it) }
        session?.release()
        player?.release()
        session = null
        player = null
        super.onDestroy()
    }
}
