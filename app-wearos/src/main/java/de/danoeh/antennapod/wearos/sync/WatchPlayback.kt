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

    private val _pending = MutableStateFlow<PlusWear.WatchMedia?>(null)
    val pending: StateFlow<PlusWear.WatchMedia?> = _pending

    @Volatile
    var recordSentAt = 0L
    private var activePlaylist = 0L

    private class MediaAnswer(val media: PlusWear.WatchMedia?, val answered: Boolean)

    private var player: ExoPlayer? = null
    private var playerWaiter: CompletableDeferred<ExoPlayer>? = null

    @Volatile
    private var mediaReply: CompletableDeferred<MediaAnswer>? = null

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
            reportPosition(context.applicationContext, media.itemId, oldPlayer.currentPosition.toInt())
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

    fun protectedMediaIds(): Set<Long> = setOfNotNull(current?.mediaId, _pending.value?.mediaId)

    private fun reportPosition(app: Context, itemId: Long, position: Int) {
        scope.launch {
            if (!WearMessageSender.sendToPhone(app, PlusWear.watchPositionPath(itemId, position))) {
                WatchStore.record(app, "position", itemId, position)
            }
        }
    }

    fun hasSpeaker(context: Context): Boolean = outputDevice(context, OUTPUT_SPEAKER) != null

    fun earbudsName(context: Context): String? {
        val device = outputDevice(context, OUTPUT_EARBUDS) ?: return null
        return device.productName?.toString() ?: ""
    }

    fun handleReply(path: String, data: ByteArray): Boolean {
        when (path) {
            PlusWear.WATCH_MEDIA -> mediaReply?.complete(MediaAnswer(PlusWear.watchMediaFromBytes(data), true))
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
        quiet: Boolean = false,
        stopAfter: Boolean = false
    ) {
        val app = context.applicationContext
        appContext = app
        moveJob?.cancel()
        moveJob = scope.launch {
            if (output != null) {
                _output.value = output
            } else if (!_active.value) {
                _output.value = if (outputDevice(app, OUTPUT_EARBUDS) != null) OUTPUT_EARBUDS else OUTPUT_SPEAKER
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
                    val phoneSpeeds = PlusPlayerRepository.player.value?.speeds
                    speeds = if (phoneSpeeds != null && phoneSpeeds.isNotEmpty()) phoneSpeeds else
                        WatchStore.speeds(app)
                }
                val previous = current
                val oldPlayer = player
                if (wasActive && !finishing && previous != null && oldPlayer != null) {
                    val position = oldPlayer.currentPosition.toInt()
                    oldPlayer.pause()
                    reportPosition(app, previous.itemId, position)
                }
                var answer = MediaAnswer(null, false)
                if (WearMessageSender.isPhoneNearby(app)) {
                    answer = requestMedia(app, requestPath)
                }
                val local = !answer.answered
                var media = if (local) localMedia(app, requestPath) else answer.media
                if (finishing) {
                    forgetFinished(app, requestPath.substring(PlusWear.WATCH_FINISHED_PREFIX.length).toLongOrNull())
                    if (stopAfter) {
                        media = null
                    }
                }
                if (media == null) {
                    ended = finishing
                } else {
                    val chosen = media
                    _pending.value = chosen
                    val file = if (local) {
                        WatchFiles.fileFor(app, chosen.mediaId).takeIf { WatchFiles.has(app, chosen) }
                    } else {
                        WatchFiles.fetch(app, chosen, WatchStore.useLte(app)) { progress, source ->
                            _moving.value = MoveState(progress, source)
                        }
                    }
                    if (file != null) {
                        var position = WatchStore.recordedPosition(app, chosen.itemId) ?: chosen.position
                        var speed = chosen.speed
                        if (!local) {
                            val taken = requestTaken(app, chosen.itemId)
                            takenMedia = chosen
                            position = taken?.position ?: chosen.position
                            speed = taken?.speed ?: chosen.speed
                        }
                        takenPosition = position
                        started = play(app, file, chosen, position, speed)
                    }
                }
            } finally {
                _moving.value = null
                _pending.value = null
                if (wakeLock != null && wakeLock.isHeld) {
                    wakeLock.release()
                }
            }
            if (finishing) {
                WatchDownloads.request(app)
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

    private fun localMedia(app: Context, requestPath: String): PlusWear.WatchMedia? {
        when {
            requestPath.startsWith(PlusWear.WATCH_PREPARE_PLAYLIST_PREFIX) -> {
                val id = requestPath.substring(PlusWear.WATCH_PREPARE_PLAYLIST_PREFIX.length).toLongOrNull()
                    ?: return null
                val plan = WatchStore.plan(app, id) ?: return null
                val first = WatchStore.downloaded(app, plan).firstOrNull() ?: return null
                activePlaylist = id
                WatchStore.record(app, "playlist", id)
                return first
            }
            requestPath.startsWith(PlusWear.WATCH_FINISHED_PREFIX) -> {
                val id = requestPath.substring(PlusWear.WATCH_FINISHED_PREFIX.length).toLongOrNull() ?: return null
                WatchStore.record(app, "finished", id)
                if (!WatchStore.followQueue(app)) {
                    return null
                }
                val plan = WatchStore.planContaining(app, id, activePlaylist) ?: return null
                val index = plan.items.indexOfFirst { it.itemId == id }
                return plan.items.drop(index + 1).firstOrNull { WatchFiles.has(app, it) }
            }
            requestPath.startsWith(PlusWear.WATCH_PREPARE_PREFIX) -> {
                var id = requestPath.substring(PlusWear.WATCH_PREPARE_PREFIX.length).toLongOrNull() ?: return null
                if (id <= 0) {
                    id = WatchStore.lastItem(app)
                }
                return WatchStore.findMedia(app, id)?.takeIf { WatchFiles.has(app, it) }
            }
        }
        return null
    }

    private fun forgetFinished(app: Context, itemId: Long?) {
        if (itemId == null) {
            return
        }
        val mediaId = WatchStore.findMedia(app, itemId)?.mediaId ?: current?.takeIf { it.itemId == itemId }?.mediaId
        WatchStore.removeItem(app, itemId)
        if (mediaId != null) {
            WatchFiles.deleteFile(app, mediaId)
        }
    }

    private fun episodeRow(media: PlusWear.WatchMedia): PlusWear.EpisodeRow = PlusWear.EpisodeRow(
        media.itemId,
        media.title,
        media.feedTitle,
        media.duration,
        media.position,
        0L,
        media.itemId == current?.itemId
    )

    private fun spanText(app: Context, millis: Long): String {
        val minutes = (millis / 60_000L).toInt()
        return when {
            minutes < 60 -> app.getString(CommonR.string.plus_wear_minutes, minutes)
            minutes % 60 == 0 -> app.getString(CommonR.string.plus_wear_hours, minutes / 60)
            else -> app.getString(CommonR.string.plus_wear_hours_minutes, minutes / 60, minutes % 60)
        }
    }

    suspend fun offline(context: Context, path: String): Boolean {
        val app = context.applicationContext
        appContext = app
        when {
            path.startsWith(PlusWear.PLAY_PLAYLIST_PREFIX) -> {
                val id = path.substring(PlusWear.PLAY_PLAYLIST_PREFIX.length).toLongOrNull() ?: return true
                scope.launch { start(app, requestPath = PlusWear.watchPreparePlaylistPath(id)) }
            }
            path.startsWith(WearDataPaths.PLAY_PREFIX) -> {
                val id = path.substring(WearDataPaths.PLAY_PREFIX.length).toLongOrNull() ?: return true
                scope.launch { start(app, requestPath = PlusWear.watchPreparePath(id)) }
            }
            path == PlusWear.PLAY -> scope.launch { start(app) }
            path == PlusWear.PLAYLISTS -> {
                val rows = PlusWearRepository.cachedPlaylists(app).map { row ->
                    val plan = WatchStore.plan(app, row.id)
                    val onWatch = if (plan != null) WatchStore.downloaded(app, plan) else emptyList()
                    val subtitle = if (onWatch.isEmpty()) {
                        app.getString(CommonR.string.plus_wear_not_on_watch)
                    } else {
                        app.getString(
                            CommonR.string.plus_wear_on_watch,
                            spanText(app, onWatch.sumOf { WatchStore.timeLeft(it) })
                        )
                    }
                    PlusWear.PlaylistRow(row.id, row.title, subtitle, onWatch.isNotEmpty(), row.recent)
                }
                PlusWearRepository.setOfflinePlaylists(rows)
            }
            path == PlusWear.QUEUE -> {
                val id = if (activePlaylist > 0) activePlaylist else WatchStore.lastPlaylist(app)
                val plan = WatchStore.plan(app, id) ?: WatchStore.allPlans(app).firstOrNull()
                val rows = if (plan != null) WatchStore.downloaded(app, plan).map { episodeRow(it) } else emptyList()
                PlusPlayerRepository.setQueue(rows)
            }
            path == PlusWear.EPISODES_ALL || path == PlusWear.EPISODES_DOWNLOADED -> {
                val rows = WatchStore.allPlans(app).flatMap { WatchStore.downloaded(app, it) }
                    .distinctBy { it.itemId }.map { episodeRow(it) }
                PlusPlayerRepository.setEpisodes(path, rows)
            }
            path.startsWith(PlusWear.EPISODES_PREFIX) -> PlusPlayerRepository.setEpisodes(path, emptyList())
            path == PlusWear.JUMP_SECONDS -> PlusWearRepository.setJumpSeconds(WatchStore.jumpSeconds(app))
            path == PlusWear.LOADED_PLAYLIST -> return true
            else -> return false
        }
        return true
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
        scope.launch {
            if (!WearMessageSender.sendToPhone(app, PlusWear.watchGivePath(media.itemId, position, play))) {
                WatchStore.record(app, "position", media.itemId, position)
            }
        }
    }

    private fun stop(app: Context) {
        val oldPlayer = player
        clearState()
        oldPlayer?.stop()
        oldPlayer?.clearMediaItems()
        app.stopService(Intent(app, WatchPlaybackService::class.java))
    }

    private suspend fun requestMedia(app: Context, path: String): MediaAnswer {
        val reply = CompletableDeferred<MediaAnswer>()
        mediaReply = reply
        if (!WearMessageSender.sendToPhone(app, path)) {
            return MediaAnswer(null, false)
        }
        return withTimeoutOrNull(REPLY_WAIT_MILLIS) { reply.await() } ?: MediaAnswer(null, false)
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
        WatchStore.planContaining(app, media.itemId, activePlaylist)?.let { activePlaylist = it.id }
        WatchStore.setLast(app, media.itemId, activePlaylist)
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
        start(app, requestPath = PlusWear.watchFinishedPath(media.itemId), quiet = true, stopAfter = sleepEpisode)
    }

    fun syncPosition(context: Context) {
        val app = context.applicationContext
        val media = current ?: return
        val syncing = player ?: return
        if (!_active.value || syncing.playbackState == Player.STATE_ENDED || endedItem == media.itemId) {
            return
        }
        reportPosition(app, media.itemId, syncing.currentPosition.toInt())
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
                val jump = if (WearMessageSender.nearby.value) {
                    PlusWearRepository.jumpSeconds.value
                } else {
                    WatchStore.jumpSeconds(app)
                }
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
