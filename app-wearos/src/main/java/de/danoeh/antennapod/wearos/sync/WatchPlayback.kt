package de.danoeh.antennapod.wearos.sync

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import de.danoeh.antennapod.net.sync.wearinterface.WearDataPaths
import de.danoeh.antennapod.ui.common.R as CommonR
import de.danoeh.antennapod.wearos.WatchPlaybackService
import java.io.File
import kotlin.math.abs
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

object WatchPlayback {
    const val OUTPUT_SPEAKER = 0
    const val OUTPUT_EARBUDS = 1
    private const val TAG = "WatchPlayback"
    private const val REPLY_WAIT_MILLIS = 15_000L
    private const val PLAYER_WAIT_MILLIS = 5_000L
    private const val SYNC_EVERY_SECONDS = 30
    private const val MOVE_WAKE_MILLIS = 10 * 60_000L

    data class MoveState(val progress: Float, val source: Int)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val _active = MutableStateFlow(false)
    val active: StateFlow<Boolean> = _active

    private val _output = MutableStateFlow(OUTPUT_SPEAKER)
    val output: StateFlow<Int> = _output

    private val _moving = MutableStateFlow<MoveState?>(null)
    val moving: StateFlow<MoveState?> = _moving

    private var player: ExoPlayer? = null
    private var playerWaiter: CompletableDeferred<ExoPlayer>? = null

    @Volatile
    private var mediaReply: CompletableDeferred<PlusWear.WatchMedia?>? = null

    @Volatile
    private var takenReply: CompletableDeferred<PlusWear.WatchTaken?>? = null
    private var current: PlusWear.WatchMedia? = null
    private var speeds = FloatArray(0)
    private var moveJob: Job? = null
    private var tickJob: Job? = null
    private var endedItem = 0L
    private var sleepUntil = 0L
    private var sleepEpisode = false
    private var appContext: Context? = null

    fun attach(newPlayer: ExoPlayer) {
        player = newPlayer
        playerWaiter?.complete(newPlayer)
        playerWaiter = null
    }

    fun detach(context: Context, oldPlayer: ExoPlayer) {
        if (player !== oldPlayer) {
            return
        }
        val media = current
        if (_active.value && media != null) {
            val position = oldPlayer.currentPosition.toInt()
            val app = context.applicationContext
            scope.launch { WearMessageSender.sendToPhone(app, PlusWear.watchPositionPath(media.itemId, position)) }
        }
        player = null
        clearState()
    }

    private fun clearState() {
        tickJob?.cancel()
        _active.value = false
        current = null
        sleepUntil = 0L
        sleepEpisode = false
        PlusPlayerRepository.requestVolumeResync()
    }

    private fun isBluetooth(type: Int): Boolean = type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
        type == AudioDeviceInfo.TYPE_BLE_HEADSET || type == AudioDeviceInfo.TYPE_BLE_SPEAKER ||
        type == AudioDeviceInfo.TYPE_BLE_BROADCAST || type == AudioDeviceInfo.TYPE_HEARING_AID

    private fun outputDevice(context: Context, output: Int): AudioDeviceInfo? {
        val audio = context.getSystemService(AudioManager::class.java) ?: return null
        val devices = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
        return if (output == OUTPUT_SPEAKER) {
            devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
        } else {
            devices.firstOrNull { isBluetooth(it.type) }
        }
    }

    fun hasSpeaker(context: Context): Boolean = outputDevice(context, OUTPUT_SPEAKER) != null

    fun earbudsName(context: Context): String? {
        val device = outputDevice(context, OUTPUT_EARBUDS) ?: return null
        return device.productName?.toString() ?: ""
    }

    fun handleReply(path: String, data: ByteArray): Boolean {
        when (path) {
            PlusWear.WATCH_MEDIA -> mediaReply?.complete(PlusWear.watchMediaFromBytes(data))
            PlusWear.WATCH_TAKEN -> takenReply?.complete(PlusWear.watchTakenFromBytes(data))
            PlusWear.WATCH_PHONE_PLAYING -> scope.launch { phoneTookOver() }
            else -> return false
        }
        return true
    }

    private fun phoneTookOver() {
        val app = appContext ?: return
        if (_active.value) {
            stop(app)
        }
    }

    fun start(
        context: Context,
        output: Int? = null,
        requestPath: String = PlusWear.watchPreparePath(0),
        quiet: Boolean = false
    ) {
        val app = context.applicationContext
        appContext = app
        moveJob?.cancel()
        moveJob = scope.launch {
            if (output != null) {
                _output.value = output
            }
            _moving.value = MoveState(0f, WatchFiles.SOURCE_NONE)
            val wasActive = _active.value
            val finishing = requestPath.startsWith(PlusWear.WATCH_FINISHED_PREFIX)
            var started = false
            var ended = false
            var takenMedia: PlusWear.WatchMedia? = null
            var takenPosition = 0
            val wakeLock = app.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "antennapod:plus-watch-move")
            wakeLock?.acquire(MOVE_WAKE_MILLIS)
            try {
                if (!wasActive) {
                    PlusPlayerRepository.player.value?.speeds?.let { speeds = it }
                }
                val previous = current
                val oldPlayer = player
                if (wasActive && !finishing && previous != null && oldPlayer != null) {
                    val position = oldPlayer.currentPosition.toInt()
                    oldPlayer.pause()
                    WearMessageSender.sendToPhone(app, PlusWear.watchPositionPath(previous.itemId, position))
                }
                val media = requestMedia(app, requestPath)
                if (media == null) {
                    ended = finishing
                } else {
                    val file = WatchFiles.fetch(app, media) { progress, source ->
                        _moving.value = MoveState(progress, source)
                    }
                    if (file != null) {
                        val taken = requestTaken(app, media.itemId)
                        takenMedia = media
                        takenPosition = taken?.position ?: media.position
                        started = play(app, file, media, takenPosition, taken?.speed ?: media.speed)
                    }
                }
            } finally {
                _moving.value = null
                if (wakeLock != null && wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
            if (started) {
                return@launch
            }
            val failedMedia = takenMedia
            if (!wasActive && failedMedia != null) {
                WearMessageSender.sendToPhone(app, PlusWear.watchGivePath(failedMedia.itemId, takenPosition, true))
            }
            if (finishing && _active.value) {
                stop(app)
            }
            if (!(ended && quiet)) {
                Toast.makeText(app, CommonR.string.plus_wear_move_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    fun cancelMove() {
        moveJob?.cancel()
        _moving.value = null
    }

    fun giveBack(context: Context) {
        val app = context.applicationContext
        val media = current
        val oldPlayer = player
        if (!_active.value || media == null || oldPlayer == null) {
            return
        }
        val position = oldPlayer.currentPosition.toInt()
        val play = oldPlayer.playWhenReady && oldPlayer.playbackState != Player.STATE_ENDED
        moveJob?.cancel()
        stop(app)
        scope.launch { WearMessageSender.sendToPhone(app, PlusWear.watchGivePath(media.itemId, position, play)) }
    }

    private fun stop(app: Context) {
        val oldPlayer = player
        clearState()
        oldPlayer?.stop()
        oldPlayer?.clearMediaItems()
        app.stopService(Intent(app, WatchPlaybackService::class.java))
    }

    private suspend fun requestMedia(app: Context, path: String): PlusWear.WatchMedia? {
        val reply = CompletableDeferred<PlusWear.WatchMedia?>()
        mediaReply = reply
        WearMessageSender.sendToPhone(app, path)
        return withTimeoutOrNull(REPLY_WAIT_MILLIS) { reply.await() }
    }

    private suspend fun requestTaken(app: Context, itemId: Long): PlusWear.WatchTaken? {
        val reply = CompletableDeferred<PlusWear.WatchTaken?>()
        takenReply = reply
        WearMessageSender.sendToPhone(app, PlusWear.watchTakePath(itemId))
        return withTimeoutOrNull(REPLY_WAIT_MILLIS) { reply.await() }
    }

    private suspend fun ensurePlayer(app: Context): ExoPlayer? {
        player?.let { return it }
        val waiter = CompletableDeferred<ExoPlayer>()
        playerWaiter = waiter
        try {
            app.startService(Intent(app, WatchPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            Log.w(TAG, "Could not start the watch player", e)
            return null
        }
        return withTimeoutOrNull(PLAYER_WAIT_MILLIS) { waiter.await() }
    }

    private suspend fun play(
        app: Context,
        file: File,
        media: PlusWear.WatchMedia,
        position: Int,
        speed: Float
    ): Boolean {
        val newPlayer = ensurePlayer(app) ?: return false
        current = media
        endedItem = 0L
        newPlayer.setPreferredAudioDevice(outputDevice(app, _output.value))
        newPlayer.setMediaItem(
            MediaItem.Builder()
                .setUri(Uri.fromFile(file))
                .setMediaId(media.itemId.toString())
                .setMediaMetadata(
                    MediaMetadata.Builder().setTitle(media.title).setArtist(media.feedTitle).build()
                )
                .build()
        )
        newPlayer.setPlaybackSpeed(speed.coerceIn(0.5f, 4f))
        newPlayer.prepare()
        val introMillis = media.skipIntro * 1000
        newPlayer.seekTo((if (position < introMillis) introMillis else position).toLong())
        if (_output.value == OUTPUT_SPEAKER || outputDevice(app, OUTPUT_EARBUDS) != null) {
            newPlayer.play()
        }
        _active.value = true
        val audio = app.getSystemService(AudioManager::class.java)
        if (audio != null) {
            PlusPlayerRepository.setVolume(
                audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            )
        }
        publish(app)
        startTicker(app)
        return true
    }

    private fun startTicker(app: Context) {
        tickJob?.cancel()
        tickJob = scope.launch {
            var seconds = 0
            while (isActive) {
                delay(1_000)
                val ticking = player ?: continue
                val media = current ?: continue
                if (sleepUntil > 0 && SystemClock.elapsedRealtime() >= sleepUntil) {
                    sleepUntil = 0L
                    ticking.pause()
                }
                if (!ticking.isPlaying) {
                    continue
                }
                seconds++
                val duration = ticking.duration
                if (media.skipEnding > 0 && duration > 0 &&
                    ticking.currentPosition >= duration - media.skipEnding * 1000L
                ) {
                    ticking.pause()
                    onEnded(app)
                } else if (seconds % SYNC_EVERY_SECONDS == 0) {
                    syncPosition(app)
                }
            }
        }
    }

    fun onEnded(context: Context) {
        val app = context.applicationContext
        val media = current ?: return
        if (!_active.value || endedItem == media.itemId) {
            return
        }
        endedItem = media.itemId
        if (sleepEpisode) {
            scope.launch { WearMessageSender.sendToPhone(app, PlusWear.watchFinishedPath(media.itemId)) }
            stop(app)
            return
        }
        start(app, requestPath = PlusWear.watchFinishedPath(media.itemId), quiet = true)
    }

    fun syncPosition(context: Context) {
        val app = context.applicationContext
        val media = current ?: return
        val syncing = player ?: return
        if (!_active.value || syncing.playbackState == Player.STATE_ENDED || endedItem == media.itemId) {
            return
        }
        val position = syncing.currentPosition.toInt()
        scope.launch { WearMessageSender.sendToPhone(app, PlusWear.watchPositionPath(media.itemId, position)) }
    }

    private fun publish(app: Context) {
        val media = current ?: return
        val publishing = player ?: return
        val audio = app.getSystemService(AudioManager::class.java)
        val duration = if (publishing.duration > 0) publishing.duration.toInt() else media.duration
        val sleepMillis = when {
            sleepEpisode -> 0L
            sleepUntil > 0 -> (sleepUntil - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
            else -> -1L
        }
        PlusPlayerRepository.setLocal(
            PlusWear.PlayerState(
                media.itemId,
                media.title,
                media.feedTitle,
                publishing.currentPosition.toInt(),
                duration,
                publishing.playWhenReady && publishing.playbackState != Player.STATE_ENDED,
                publishing.playbackParameters.speed,
                speeds,
                sleepMillis,
                sleepEpisode,
                PlusWear.OUTPUT_SPEAKER,
                "",
                audio?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0,
                audio?.getStreamMaxVolume(AudioManager.STREAM_MUSIC) ?: 0
            )
        )
    }

    fun intercept(context: Context, path: String): Boolean {
        if (!_active.value) {
            return false
        }
        val app = context.applicationContext
        val handled = path == PlusWear.PLAYER || path == PlusWear.PLAY || path == WearDataPaths.PAUSE ||
            path == WearDataPaths.SKIP_FORWARD || path == WearDataPaths.SKIP_BACKWARD ||
            path == PlusWear.SPEED_NEXT || path.startsWith(PlusWear.SPEED_PREFIX) ||
            path.startsWith(PlusWear.SLEEP_PREFIX) || path == PlusWear.VOLUME_UP ||
            path == PlusWear.VOLUME_DOWN || path.startsWith(WearDataPaths.PLAY_PREFIX) ||
            path.startsWith(PlusWear.PLAY_PLAYLIST_PREFIX)
        if (handled) {
            scope.launch { command(app, path) }
        }
        return handled
    }

    private fun command(app: Context, path: String) {
        val target = player ?: return
        when {
            path == PlusWear.PLAYER -> publish(app)
            path == PlusWear.PLAY -> {
                if (_output.value == OUTPUT_EARBUDS && outputDevice(app, OUTPUT_EARBUDS) == null) {
                    Toast.makeText(app, CommonR.string.plus_wear_connect_earbuds, Toast.LENGTH_SHORT).show()
                } else {
                    target.setPreferredAudioDevice(outputDevice(app, _output.value))
                    target.play()
                }
            }
            path == WearDataPaths.PAUSE -> target.pause()
            path == WearDataPaths.SKIP_FORWARD || path == WearDataPaths.SKIP_BACKWARD -> {
                val jump = PlusWearRepository.jumpSeconds.value
                val delta = if (path == WearDataPaths.SKIP_FORWARD) jump[1] * 1000L else -jump[0] * 1000L
                val limit = if (target.duration > 0) target.duration else Long.MAX_VALUE
                target.seekTo((target.currentPosition + delta).coerceIn(0L, limit))
            }
            path == PlusWear.SPEED_NEXT -> {
                if (speeds.size > 1) {
                    val now = target.playbackParameters.speed
                    val index = speeds.indexOfFirst { abs(it - now) < 0.001f }
                    target.setPlaybackSpeed(
                        if (index == -1 || index == speeds.size - 1) speeds[0] else speeds[index + 1]
                    )
                }
            }
            path.startsWith(PlusWear.SPEED_PREFIX) -> {
                val speed = path.substring(PlusWear.SPEED_PREFIX.length).toFloatOrNull()
                if (speed != null) {
                    target.setPlaybackSpeed(speed.coerceIn(0.5f, 4f))
                }
            }
            path.startsWith(PlusWear.SLEEP_PREFIX) -> {
                val minutes = path.substring(PlusWear.SLEEP_PREFIX.length).toIntOrNull() ?: 0
                sleepEpisode = minutes == PlusWear.SLEEP_END_OF_EPISODE
                sleepUntil = if (minutes > 0) SystemClock.elapsedRealtime() + minutes * 60_000L else 0L
            }
            path == PlusWear.VOLUME_UP || path == PlusWear.VOLUME_DOWN -> {
                val audio = app.getSystemService(AudioManager::class.java) ?: return
                audio.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    if (path == PlusWear.VOLUME_UP) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                    0
                )
                PlusPlayerRepository.setVolume(
                    audio.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                )
            }
            path.startsWith(WearDataPaths.PLAY_PREFIX) -> {
                val itemId = path.substring(WearDataPaths.PLAY_PREFIX.length).toLongOrNull() ?: return
                if (itemId == current?.itemId) {
                    target.play()
                } else {
                    start(app, requestPath = PlusWear.watchPreparePath(itemId))
                }
            }
            path.startsWith(PlusWear.PLAY_PLAYLIST_PREFIX) -> {
                val playlistId = path.substring(PlusWear.PLAY_PLAYLIST_PREFIX.length).toLongOrNull() ?: return
                start(app, requestPath = PlusWear.watchPreparePlaylistPath(playlistId))
            }
        }
        if (path != PlusWear.PLAYER) {
            publish(app)
        }
    }
}
