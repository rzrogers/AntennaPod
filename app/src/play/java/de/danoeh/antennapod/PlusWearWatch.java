package de.danoeh.antennapod;

import android.content.Context;
import android.util.Log;
import androidx.media3.session.MediaController;
import com.google.android.gms.wearable.ChannelClient;
import com.google.android.gms.wearable.Wearable;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.FeedPreferences;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear;
import de.danoeh.antennapod.playback.base.MediaItemAdapter;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.DelayedDeletion;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.List;

final class PlusWearWatch {
    private static final String TAG = "PlusWearWatch";

    private PlusWearWatch() {
    }

    static boolean handle(Context context, String path, String nodeId) {
        try {
            if (path.startsWith(PlusWear.WATCH_PREPARE_PLAYLIST_PREFIX)) {
                preparePlaylist(context, nodeId,
                        Long.parseLong(path.substring(PlusWear.WATCH_PREPARE_PLAYLIST_PREFIX.length())));
            } else if (path.startsWith(PlusWear.WATCH_PREPARE_PREFIX)) {
                prepare(context, nodeId, Long.parseLong(path.substring(PlusWear.WATCH_PREPARE_PREFIX.length())));
            } else if (path.startsWith(PlusWear.WATCH_FINISHED_PREFIX)) {
                finished(context, nodeId, Long.parseLong(path.substring(PlusWear.WATCH_FINISHED_PREFIX.length())));
            } else if (path.startsWith(PlusWear.WATCH_TAKE_PREFIX)) {
                take(context, nodeId, Long.parseLong(path.substring(PlusWear.WATCH_TAKE_PREFIX.length())));
            } else if (path.startsWith(PlusWear.WATCH_GIVE_PREFIX)) {
                String[] parts = path.substring(PlusWear.WATCH_GIVE_PREFIX.length()).split("/");
                if (parts.length == 3) {
                    give(context, Long.parseLong(parts[0]), Integer.parseInt(parts[1]), "1".equals(parts[2]));
                }
            } else if (path.startsWith(PlusWear.WATCH_POSITION_PREFIX)) {
                String[] parts = path.substring(PlusWear.WATCH_POSITION_PREFIX.length()).split("/");
                if (parts.length == 2) {
                    position(context, nodeId, Long.parseLong(parts[0]), Integer.parseInt(parts[1]));
                }
            } else if (PlusWear.WATCH_PING.equals(path)) {
                Log.d(TAG, "Watch is copying");
            } else {
                return false;
            }
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed watch path: " + path, e);
        }
        return true;
    }

    static void onChannelOpened(Context context, ChannelClient.Channel channel) {
        String path = channel.getPath();
        if (!path.startsWith(PlusWear.WATCH_FILE_CHANNEL_PREFIX)) {
            return;
        }
        ChannelClient client = Wearable.getChannelClient(context);
        File file = null;
        try {
            FeedMedia media = DBReader.getFeedMedia(
                    Long.parseLong(path.substring(PlusWear.WATCH_FILE_CHANNEL_PREFIX.length())));
            file = localFile(media);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed channel path: " + path, e);
        }
        if (file == null) {
            client.close(channel);
            return;
        }
        final File source = file;
        client.getOutputStream(channel).addOnSuccessListener(out -> new Thread(() -> {
            try {
                PlusWearFileServer.send(source, out);
            } catch (IOException e) {
                Log.d(TAG, "Sending file over channel ended", e);
            }
        }, TAG).start()).addOnFailureListener(e -> Log.w(TAG, "Could not open channel output", e));
    }

    private static FeedMedia mediaOfItem(long itemId) {
        if (itemId > 0) {
            FeedItem item = DBReader.getFeedItem(itemId);
            return item != null ? item.getMedia() : null;
        }
        FeedMedia media = DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
        if (media != null && media.getItem() != null) {
            return media;
        }
        List<FeedItem> queue = DBReader.getQueue();
        return queue.isEmpty() ? null : queue.get(0).getMedia();
    }

    private static File localFile(FeedMedia media) {
        if (media == null || !media.localFileAvailable()) {
            return null;
        }
        File file = new File(media.getLocalFileUrl());
        return file.isFile() && file.length() > 0 ? file : null;
    }

    private static void prepare(Context context, String nodeId, long itemId) {
        sendMedia(context, nodeId, mediaOfItem(itemId));
    }

    private static void preparePlaylist(Context context, String nodeId, long playlistId) {
        try {
            PlaylistDB.playPlaylist(playlistId).get();
        } catch (Exception e) {
            Log.w(TAG, "Could not load playlist " + playlistId, e);
            sendMedia(context, nodeId, null);
            return;
        }
        Playlist loaded = PlaylistDB.getLoadedPlaylist();
        List<FeedItem> queue = DBReader.getQueue();
        if (loaded == null || loaded.getId() != playlistId || queue.isEmpty() || queue.get(0).getMedia() == null) {
            sendMedia(context, nodeId, null);
            return;
        }
        PlaylistDB.updateSpeedDecision(queue.get(0).getMedia());
        sendMedia(context, nodeId, queue.get(0).getMedia());
    }

    private static void sendMedia(Context context, String nodeId, FeedMedia media) {
        if (media == null || media.getItem() == null) {
            reply(context, nodeId, PlusWear.WATCH_MEDIA, PlusWear.watchMediaToBytes(null));
            return;
        }
        FeedItem item = media.getItem();
        String title = item.getTitle() != null ? item.getTitle() : "";
        String feedTitle = "";
        int skipIntro = 0;
        int skipEnding = 0;
        Feed feed = item.getFeed();
        if (feed != null) {
            feedTitle = feed.getTitle() != null ? feed.getTitle() : "";
            FeedPreferences preferences = feed.getPreferences();
            if (preferences != null) {
                skipIntro = preferences.getFeedSkipIntro();
                skipEnding = preferences.getFeedSkipEnding();
            }
        }
        File file = localFile(media);
        String host = "";
        int port = 0;
        String token = "";
        if (file != null) {
            PlusWearFileServer.Offer offer = PlusWearFileServer.offer(context, file);
            if (offer != null) {
                host = offer.host;
                port = offer.port;
                token = offer.token;
            }
        }
        String url = media.getDownloadUrl() != null ? media.getDownloadUrl() : "";
        reply(context, nodeId, PlusWear.WATCH_MEDIA, PlusWear.watchMediaToBytes(new PlusWear.WatchMedia(
                item.getId(), media.getId(), title, feedTitle, media.getDuration(), media.getPosition(),
                PlaybackSpeedUtils.getCurrentPlaybackSpeed(media), url, file != null ? file.length() : 0,
                file != null, host, port, token, skipIntro, skipEnding)));
    }

    private static boolean isCurrent(MediaController controller, FeedMedia media) {
        return controller.getCurrentMediaItem() != null
                && String.valueOf(media.getId()).equals(controller.getCurrentMediaItem().mediaId);
    }

    private static void take(Context context, String nodeId, long itemId) {
        FeedMedia media = mediaOfItem(itemId);
        if (media == null || media.getItem() == null) {
            reply(context, nodeId, PlusWear.WATCH_TAKEN, new byte[0]);
            return;
        }
        long id = media.getItem().getId();
        int savedPosition = media.getPosition();
        float savedSpeed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(media);
        if (!PlaybackService.isRunning) {
            reply(context, nodeId, PlusWear.WATCH_TAKEN, PlusWear.watchTakenToBytes(
                    new PlusWear.WatchTaken(id, savedPosition, savedSpeed)));
            return;
        }
        PlaybackController.bindToMedia3Service(context, controller -> {
            int position = savedPosition;
            float speed = savedSpeed;
            if (isCurrent(controller, media)) {
                position = (int) controller.getCurrentPosition();
                speed = controller.getPlaybackParameters().speed;
                controller.pause();
            } else {
                controller.pause();
                controller.setMediaItem(MediaItemAdapter.fromMediaIdStub(media.getId()));
                controller.prepare();
            }
            reply(context, nodeId, PlusWear.WATCH_TAKEN, PlusWear.watchTakenToBytes(
                    new PlusWear.WatchTaken(id, position, speed)));
        });
    }

    private static void savePosition(FeedMedia media, int position) {
        media.setPosition(position);
        try {
            DBWriter.setFeedMediaPlaybackInformation(media).get();
        } catch (Exception e) {
            Log.w(TAG, "Could not save the watch's position", e);
        }
    }

    private static void give(Context context, long itemId, int position, boolean play) {
        FeedMedia media = mediaOfItem(itemId);
        if (media == null) {
            return;
        }
        savePosition(media, position);
        if (!play && !PlaybackService.isRunning) {
            return;
        }
        PlaybackController.bindToMedia3Service(context, controller -> {
            if (isCurrent(controller, media)) {
                controller.seekTo(position);
                if (play) {
                    controller.play();
                }
            } else if (play) {
                new PlaybackServiceStarter(context, media).callEvenIfRunning(true).start();
            }
        });
    }

    private static void position(Context context, String nodeId, long itemId, int position) {
        FeedMedia media = mediaOfItem(itemId);
        if (media == null) {
            return;
        }
        if (!PlaybackService.isRunning) {
            savePosition(media, position);
            return;
        }
        PlaybackController.bindToMedia3Service(context, controller -> {
            if (controller.isPlaying()) {
                reply(context, nodeId, PlusWear.WATCH_PHONE_PLAYING, new byte[0]);
                return;
            }
            boolean current = isCurrent(controller, media);
            if (current) {
                controller.seekTo(position);
            }
            new Thread(() -> savePosition(media, position), TAG).start();
        });
    }

    private static void finished(Context context, String nodeId, long itemId) {
        FeedMedia media = mediaOfItem(itemId);
        if (itemId <= 0 || media == null || media.getItem() == null) {
            sendMedia(context, nodeId, null);
            return;
        }
        FeedItem item = media.getItem();
        List<FeedItem> queue = DBReader.getQueue();
        FeedItem next = null;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId() == item.getId() && i + 1 < queue.size()) {
                next = queue.get(i + 1);
            }
        }
        DBWriter.markItemsPlayed(FeedItem.PLAYED, true, Collections.singletonList(item));
        DBWriter.removeQueueItem(context, true, item);
        Feed feed = item.getFeed();
        if (feed != null && feed.getPreferences() != null) {
            FeedPreferences.AutoDeleteAction action = feed.getPreferences().getCurrentAutoDelete();
            boolean autoDeleteEnabledGlobally = UserPreferences.isAutoDelete()
                    && (!feed.isLocalFeed() || UserPreferences.isAutoDeleteLocal());
            boolean shouldAutoDelete = action == FeedPreferences.AutoDeleteAction.ALWAYS
                    || (action == FeedPreferences.AutoDeleteAction.GLOBAL && autoDeleteEnabledGlobally);
            if (shouldAutoDelete && (!item.isTagged(FeedItem.TAG_FAVORITE)
                    || !UserPreferences.shouldFavoriteKeepEpisode())) {
                DelayedDeletion.deleteOrSchedule(context, media);
            }
        }
        DBWriter.addItemToPlaybackHistory(media);
        if (next == null || next.getMedia() == null || !UserPreferences.isFollowQueue()) {
            sendMedia(context, nodeId, null);
            return;
        }
        PlaylistDB.updateSpeedDecision(next.getMedia());
        sendMedia(context, nodeId, next.getMedia());
    }

    private static void reply(Context context, String nodeId, String path, byte[] payload) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send reply to " + path, e));
    }
}
