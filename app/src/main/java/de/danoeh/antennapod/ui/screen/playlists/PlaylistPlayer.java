package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.content.Intent;
import android.util.Log;

import androidx.core.content.pm.ShortcutInfoCompat;
import androidx.core.content.pm.ShortcutManagerCompat;
import androidx.core.graphics.drawable.IconCompat;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.BuildConfig;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.actionbutton.ItemActionButton;
import de.danoeh.antennapod.actionbutton.PlayActionButton;
import de.danoeh.antennapod.actionbutton.PlayLocalActionButton;
import de.danoeh.antennapod.actionbutton.StreamActionButton;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackStatus;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class PlaylistPlayer {
    private static final String TAG = "PlaylistPlayer";
    public static final String EXTRA_PLAYLIST_ID = "plus_playlist_id";

    private PlaylistPlayer() {
    }

    public static void play(Context context, long playlistId) {
        String noEpisodesMessage = context.getString(R.string.playlist_no_playable_episodes);
        Observable.fromCallable(() -> {
            PlaylistDB.playPlaylist(playlistId).get();
            Playlist loadedPlaylist = PlaylistDB.getLoadedPlaylist();
            List<FeedItem> queue = DBReader.getQueue();
            if (loadedPlaylist == null || loadedPlaylist.getId() != playlistId) {
                return Collections.<FeedItem>emptyList();
            }
            if (!queue.isEmpty()) {
                PlaylistDB.updateSpeedDecision(queue.get(0).getMedia());
            }
            return queue;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(queue -> {
                    if (queue.isEmpty()) {
                        EventBus.getDefault().post(new MessageEvent(noEpisodesMessage));
                        return;
                    }
                    applySpeedIfCurrent(context, queue.get(0));
                    startPlayback(context, queue.get(0));
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void applySpeedIfCurrent(Context context, FeedItem item) {
        FeedMedia media = item.getMedia();
        if (media == null || media.getId() != PlaybackPreferences.getCurrentlyPlayingFeedMediaId()
                || !BuildConfig.USE_MEDIA3_PLAYBACK_SERVICE) {
            return;
        }
        PlaybackPreferences.setCurrentlyPlayingTemporaryPlaybackSpeed(FeedPreferences.SPEED_USE_GLOBAL);
        float speed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(media);
        PlaybackController.bindToMedia3Service(context, controller -> controller.setPlaybackSpeed(speed));
    }

    private static void startPlayback(Context context, FeedItem item) {
        FeedMedia media = item.getMedia();
        if (media == null || PlaybackStatus.isCurrentlyPlaying(media)) {
            return;
        }
        ItemActionButton button;
        if (item.getFeed().isLocalFeed()) {
            button = new PlayLocalActionButton(item);
        } else if (media.isDownloaded()) {
            button = new PlayActionButton(item);
        } else {
            button = new StreamActionButton(item);
        }
        button.onClick(context);
    }

    public static void handleIntent(Context context, Intent intent) {
        long playlistId = intent.getLongExtra(EXTRA_PLAYLIST_ID, 0);
        if (playlistId > 0) {
            play(context, playlistId);
        }
    }

    public static void requestShortcut(Context context, Playlist playlist) {
        if (!ShortcutManagerCompat.isRequestPinShortcutSupported(context)) {
            EventBus.getDefault().post(new MessageEvent(context.getString(R.string.shortcut_not_supported_message)));
            return;
        }
        Intent intent = new Intent(context, MainActivity.class);
        intent.setAction(Intent.ACTION_MAIN);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
        intent.putExtra(EXTRA_PLAYLIST_ID, playlist.getId());
        ShortcutInfoCompat shortcut = new ShortcutInfoCompat.Builder(context, "plus-playlist-" + playlist.getId())
                .setShortLabel(playlist.getTitle())
                .setLongLabel(playlist.getTitle())
                .setIntent(intent)
                .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_playlist))
                .build();
        ShortcutManagerCompat.requestPinShortcut(context, shortcut, null);
    }
}
