package de.danoeh.antennapod.playback.service.internal;

import android.content.ContentResolver;
import android.content.Context;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.annotation.WorkerThread;
import androidx.media.utils.MediaConstants;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.session.MediaSession;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.SettableFuture;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.base.RewindAfterPauseUtils;
import de.danoeh.antennapod.playback.service.R;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import io.reactivex.rxjava3.core.Single;
import io.reactivex.rxjava3.schedulers.Schedulers;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

final class AutoPlaylists {
    private static final String TAG = "AutoPlaylists";
    static final String MEDIA_ID_PLAYLISTS = "plus_playlists";
    private static final String MEDIA_ID_PLAYLIST_PREFIX = "PlusPlaylist:";

    private AutoPlaylists() {
    }

    static boolean isPlaylistId(@Nullable String mediaId) {
        return mediaId != null && mediaId.startsWith(MEDIA_ID_PLAYLIST_PREFIX);
    }

    static MediaItem createTab(Context context) {
        return MediaItemAdapter.from(context, MEDIA_ID_PLAYLISTS,
                context.getString(R.string.playlists_label), R.drawable.ic_playlists_black, null);
    }

    @WorkerThread
    static ImmutableList<MediaItem> getChildren(Context context) {
        Uri iconUri = new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .authority(context.getResources().getResourcePackageName(R.drawable.ic_playlists_black))
                .appendPath(context.getResources().getResourceTypeName(R.drawable.ic_playlists_black))
                .appendPath(context.getResources().getResourceEntryName(R.drawable.ic_playlists_black))
                .build();
        ImmutableList.Builder<MediaItem> builder = ImmutableList.builder();
        for (Playlist playlist : PlaylistDB.getPlaylists()) {
            boolean playable = hasPlayableItems(playlist.getId());
            MediaMetadata metadata = new MediaMetadata.Builder()
                    .setTitle(playlist.getTitle())
                    .setSubtitle(subtitle(context, playlist, playable))
                    .setArtworkUri(iconUri)
                    .setIsPlayable(playable)
                    .setIsBrowsable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .build();
            builder.add(new MediaItem.Builder()
                    .setMediaId(MEDIA_ID_PLAYLIST_PREFIX + playlist.getId())
                    .setMediaMetadata(metadata)
                    .build());
        }
        return builder.build();
    }

    private static String subtitle(Context context, Playlist playlist, boolean playable) {
        if (!playable) {
            return context.getString(R.string.playlist_auto_no_episodes);
        }
        if (playlist.isLoaded()) {
            float speed = UserPreferences.getPlusPlaylistSpeed();
            int hours = UserPreferences.getPlusPlaylistSpeedHours();
            if (speed > 0 && hours >= 0 && UserPreferences.getPlusPlaylistSpeedPlaylist() == playlist.getId()) {
                String speedText = new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault()))
                        .format(speed);
                return context.getString(R.string.playlist_loaded_info,
                        context.getString(R.string.playlist_auto_speed, speedText, hours));
            }
            return context.getString(R.string.playlist_auto_playing_in_queue);
        }
        String episodes = context.getResources().getQuantityString(R.plurals.num_episodes,
                playlist.getEpisodeCount(), playlist.getEpisodeCount());
        String time = Converter.getDurationStringLocalized(context.getResources(),
                PlaylistDB.getTimeLeft(playlist.getId()), false);
        return context.getString(R.string.queue_time_left_label, episodes, time);
    }

    @WorkerThread
    private static boolean hasPlayableItems(long playlistId) {
        for (FeedItem item : PlaylistDB.getItems(playlistId)) {
            FeedMedia media = item.getMedia();
            if (media != null && (media.localFileAvailable() || media.getStreamUrl() != null)) {
                return true;
            }
        }
        return false;
    }

    @UnstableApi
    static ListenableFuture<MediaSession.MediaItemsWithStartPosition> play(Context context, MediaSession session,
                                                                           String mediaId) {
        Player player = session.getPlayer();
        MediaItem currentItem = player.getCurrentMediaItem();
        long currentPosition = player.getCurrentPosition();
        SettableFuture<MediaSession.MediaItemsWithStartPosition> future = SettableFuture.create();
        Single.fromCallable(() -> {
            long playlistId = Long.parseLong(mediaId.substring(MEDIA_ID_PLAYLIST_PREFIX.length()));
            FeedMedia media = loadPlaylist(player, playlistId);
            if (media == null || (currentItem != null && String.valueOf(media.getId()).equals(currentItem.mediaId))) {
                return keepCurrent(currentItem, currentPosition);
            }
            long startPosition = SkipUtils.skipIntroIfNecessary(context, media);
            startPosition = RewindAfterPauseUtils.calculatePositionWithRewind(
                    (int) startPosition, media.getLastPlayedTimeStatistics());
            return new MediaSession.MediaItemsWithStartPosition(
                    Collections.singletonList(MediaItemAdapter.fromPlayable(context, media, false)),
                    0, startPosition);
        })
                .subscribeOn(Schedulers.io())
                .subscribe(future::set, error -> {
                    Log.e(TAG, "Failed to play playlist", error);
                    future.set(keepCurrent(currentItem, currentPosition));
                });
        return future;
    }

    @UnstableApi
    private static MediaSession.MediaItemsWithStartPosition keepCurrent(@Nullable MediaItem currentItem,
                                                                        long currentPosition) {
        if (currentItem == null) {
            return new MediaSession.MediaItemsWithStartPosition(Collections.emptyList(), 0, 0);
        }
        return new MediaSession.MediaItemsWithStartPosition(
                Collections.singletonList(currentItem), 0, currentPosition);
    }

    @WorkerThread
    @Nullable
    static FeedMedia playByName(Player player, String query) {
        String wanted = query.trim().toLowerCase(Locale.getDefault());
        if (wanted.isEmpty()) {
            return null;
        }
        Playlist match = null;
        for (Playlist playlist : PlaylistDB.getPlaylists()) {
            String title = playlist.getTitle().trim().toLowerCase(Locale.getDefault());
            if (!hasPlayableItems(playlist.getId())) {
                continue;
            }
            if (title.equals(wanted)) {
                match = playlist;
                break;
            }
            if (match == null && title.contains(wanted)) {
                match = playlist;
            }
        }
        if (match == null) {
            int longest = 0;
            for (Playlist playlist : PlaylistDB.getPlaylists()) {
                String title = playlist.getTitle().trim().toLowerCase(Locale.getDefault());
                if (!title.isEmpty() && title.length() > longest && wanted.contains(title)
                        && hasPlayableItems(playlist.getId())) {
                    match = playlist;
                    longest = title.length();
                }
            }
        }
        return match == null ? null : loadPlaylist(player, match.getId());
    }

    @WorkerThread
    @Nullable
    private static FeedMedia loadPlaylist(Player player, long playlistId) {
        if (!hasPlayableItems(playlistId)) {
            return null;
        }
        try {
            PlaylistDB.playPlaylist(playlistId).get();
        } catch (Exception e) {
            Log.e(TAG, "Failed to load playlist", e);
            return null;
        }
        Playlist loaded = PlaylistDB.getLoadedPlaylist();
        List<FeedItem> queue = DBReader.getQueue();
        if (loaded == null || loaded.getId() != playlistId || queue.isEmpty() || queue.get(0).getMedia() == null) {
            return null;
        }
        FeedMedia media = queue.get(0).getMedia();
        PlaylistDB.updateSpeedDecision(media);
        if (media.getId() == PlaybackPreferences.getCurrentlyPlayingFeedMediaId()) {
            PlaybackPreferences.setCurrentlyPlayingTemporaryPlaybackSpeed(FeedPreferences.SPEED_USE_GLOBAL);
            float speed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(media);
            new Handler(Looper.getMainLooper()).post(() -> player.setPlaybackSpeed(speed));
        }
        return media;
    }

    @WorkerThread
    static ImmutableList<MediaItem> withQueueHeading(String parentId, ImmutableList<MediaItem> items) {
        if (!"queue".equals(parentId)) {
            return items;
        }
        Playlist loaded = PlaylistDB.getLoadedPlaylist();
        if (loaded == null) {
            return items;
        }
        ImmutableList.Builder<MediaItem> builder = ImmutableList.builder();
        for (MediaItem item : items) {
            Bundle extras = item.mediaMetadata.extras == null ? new Bundle() : new Bundle(item.mediaMetadata.extras);
            extras.putString(MediaConstants.DESCRIPTION_EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, loaded.getTitle());
            builder.add(item.buildUpon()
                    .setMediaMetadata(item.mediaMetadata.buildUpon().setExtras(extras).build())
                    .build());
        }
        return builder.build();
    }
}
