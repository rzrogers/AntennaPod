package de.danoeh.antennapod;

import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioManager;
import android.media.MediaRouter2;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.android.gms.wearable.Wearable;
import de.danoeh.antennapod.event.playback.SleepTimerUpdatedEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear;
import de.danoeh.antennapod.playback.service.PlaybackController;
import de.danoeh.antennapod.playback.service.PlaybackService;
import de.danoeh.antennapod.playback.service.PlaybackServiceStarter;
import de.danoeh.antennapod.playback.service.internal.MediaLibrarySessionCallback;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.SleepTimerPreferences;
import de.danoeh.antennapod.storage.preferences.SleepTimerType;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.episodes.ImageResourceUtils;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import org.greenrobot.eventbus.EventBus;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class PlusWearPlayer {
    private static final String TAG = "PlusWearPlayer";
    private static final int MAX_SEARCH_RESULTS = 40;
    private static final int MAX_ART_SIZE = 400;

    private PlusWearPlayer() {
    }

    static boolean handle(Context context, String path, String nodeId) {
        if (PlusWear.PLAYER.equals(path)) {
            sendPlayerState(context, nodeId);
        } else if (PlusWear.QUEUE.equals(path)) {
            reply(context, nodeId, path, PlusWear.episodeRowsToBytes(queueRows()));
        } else if (PlusWear.PLAY.equals(path)) {
            play(context);
        } else if (PlusWear.SPEED_NEXT.equals(path)) {
            PlaybackController.bindToMedia3Service(context, controller -> {
                List<Float> speeds = UserPreferences.getPlaybackSpeedArray();
                if (speeds.size() <= 1) {
                    return;
                }
                int index = speeds.indexOf(controller.getPlaybackParameters().speed);
                float next = index == -1 || index == speeds.size() - 1 ? speeds.get(0) : speeds.get(index + 1);
                controller.setPlaybackSpeed(next);
            });
        } else if (path.startsWith(PlusWear.SPEED_PREFIX)) {
            setSpeed(context, path.substring(PlusWear.SPEED_PREFIX.length()));
        } else if (path.startsWith(PlusWear.SLEEP_PREFIX)) {
            setSleepTimer(context, path.substring(PlusWear.SLEEP_PREFIX.length()));
        } else if (PlusWear.VOLUME_UP.equals(path) || PlusWear.VOLUME_DOWN.equals(path)) {
            AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, PlusWear.VOLUME_UP.equals(path)
                    ? AudioManager.ADJUST_RAISE : AudioManager.ADJUST_LOWER, 0);
            reply(context, nodeId, PlusWear.VOLUME, PlusWear.volumeToBytes(
                    audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                    audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)));
        } else if (PlusWear.OUTPUT_PICKER.equals(path)) {
            new Handler(Looper.getMainLooper()).post(() -> showOutputSwitcher(context));
        } else if (path.startsWith(PlusWear.ART_PREFIX)) {
            sendArt(context, nodeId, path);
        } else if (path.startsWith(PlusWear.SEARCH_PREFIX)) {
            String query = path.substring(PlusWear.SEARCH_PREFIX.length());
            reply(context, nodeId, PlusWear.SEARCH_RESULTS, PlusWear.searchResultsToBytes(search(query)));
        } else {
            return false;
        }
        return true;
    }

    private static FeedMedia currentMedia() {
        return DBReader.getFeedMedia(PlaybackPreferences.getCurrentlyPlayingFeedMediaId());
    }

    private static void sendPlayerState(Context context, String nodeId) {
        FeedMedia media = currentMedia();
        if (media == null || media.getItem() == null) {
            reply(context, nodeId, PlusWear.PLAYER, PlusWear.playerStateToBytes(state(context, null, 0, 0, false,
                    UserPreferences.getPlaybackSpeed())));
            return;
        }
        if (!PlaybackService.isRunning) {
            reply(context, nodeId, PlusWear.PLAYER, PlusWear.playerStateToBytes(state(context, media,
                    media.getPosition(), media.getDuration(), false,
                    PlaybackSpeedUtils.getCurrentPlaybackSpeed(media))));
            return;
        }
        PlaybackController.bindToMedia3Service(context, controller -> {
            int duration = controller.getDuration() > 0 ? (int) controller.getDuration() : media.getDuration();
            PlusWear.PlayerState state = state(context, media, (int) controller.getCurrentPosition(), duration,
                    controller.isPlaying(), controller.getPlaybackParameters().speed);
            reply(context, nodeId, PlusWear.PLAYER, PlusWear.playerStateToBytes(state));
        });
    }

    private static PlusWear.PlayerState state(Context context, FeedMedia media, int position, int duration,
                                              boolean playing, float speed) {
        List<Float> speedList = UserPreferences.getPlaybackSpeedArray();
        float[] speeds = new float[speedList.size()];
        for (int i = 0; i < speeds.length; i++) {
            speeds[i] = speedList.get(i);
        }
        long sleepMillis = -1;
        SleepTimerUpdatedEvent sleep = EventBus.getDefault().getStickyEvent(SleepTimerUpdatedEvent.class);
        boolean sleepEpisode = false;
        if (sleep != null && !sleep.isCancelled() && !sleep.isOver()) {
            sleepEpisode = SleepTimerPreferences.getSleepTimerType() == SleepTimerType.EPISODES;
            sleepMillis = sleepEpisode ? 0 : sleep.getMillisTimeLeft();
        }
        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        AudioDeviceInfo device = currentOutput(audioManager);
        int outputType = PlusWear.OUTPUT_SPEAKER;
        String outputName = "";
        if (device != null) {
            outputType = outputType(device.getType());
            if (outputType == PlusWear.OUTPUT_BLUETOOTH && device.getProductName() != null) {
                outputName = device.getProductName().toString();
            }
        }
        long itemId = 0;
        String title = "";
        String feedTitle = "";
        if (media != null && media.getItem() != null) {
            FeedItem item = media.getItem();
            itemId = item.getId();
            title = item.getTitle() != null ? item.getTitle() : "";
            if (item.getFeed() != null && item.getFeed().getTitle() != null) {
                feedTitle = item.getFeed().getTitle();
            }
        }
        return new PlusWear.PlayerState(itemId, title, feedTitle, position, duration, playing, speed, speeds,
                sleepMillis, sleepEpisode, outputType, outputName,
                audioManager.getStreamVolume(AudioManager.STREAM_MUSIC),
                audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
    }

    private static AudioDeviceInfo currentOutput(AudioManager audioManager) {
        if (Build.VERSION.SDK_INT >= 33) {
            AudioAttributes attributes = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            List<AudioDeviceInfo> devices = audioManager.getAudioDevicesForAttributes(attributes);
            if (!devices.isEmpty()) {
                return devices.get(0);
            }
        }
        AudioDeviceInfo best = null;
        for (AudioDeviceInfo device : audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
            int type = outputType(device.getType());
            if (type == PlusWear.OUTPUT_BLUETOOTH) {
                return device;
            } else if (type == PlusWear.OUTPUT_WIRED) {
                best = device;
            }
        }
        return best;
    }

    private static int outputType(int deviceType) {
        switch (deviceType) {
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
            case AudioDeviceInfo.TYPE_BLE_HEADSET:
            case AudioDeviceInfo.TYPE_BLE_SPEAKER:
            case AudioDeviceInfo.TYPE_BLE_BROADCAST:
            case AudioDeviceInfo.TYPE_HEARING_AID:
                return PlusWear.OUTPUT_BLUETOOTH;
            case AudioDeviceInfo.TYPE_WIRED_HEADPHONES:
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
            case AudioDeviceInfo.TYPE_USB_DEVICE:
                return PlusWear.OUTPUT_WIRED;
            default:
                return PlusWear.OUTPUT_SPEAKER;
        }
    }

    private static void play(Context context) {
        if (PlaybackService.isRunning) {
            PlaybackController.bindToMedia3Service(context, controller -> controller.play());
            return;
        }
        FeedMedia media = currentMedia();
        if (media == null) {
            List<FeedItem> queue = DBReader.getQueue();
            if (queue.isEmpty() || queue.get(0).getMedia() == null) {
                return;
            }
            media = queue.get(0).getMedia();
        }
        new PlaybackServiceStarter(context, media).callEvenIfRunning(true).start();
    }

    private static void setSpeed(Context context, String value) {
        float speed;
        try {
            speed = Float.parseFloat(value);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed speed: " + value, e);
            return;
        }
        speed = Math.max(0.5f, Math.min(4.0f, speed));
        if (!PlaybackSpeedUtils.isPlaylistSpeedInEffect(currentMedia())) {
            UserPreferences.setPlaybackSpeed(speed);
        }
        final float newSpeed = speed;
        PlaybackController.bindToMedia3Service(context, controller -> controller.setPlaybackSpeed(newSpeed));
    }

    private static void setSleepTimer(Context context, String value) {
        int minutes;
        try {
            minutes = Integer.parseInt(value);
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed sleep timer: " + value, e);
            return;
        }
        if (minutes == PlusWear.SLEEP_OFF) {
            PlaybackController.bindToMedia3Service(context, controller -> controller.sendCustomCommand(
                    MediaLibrarySessionCallback.SESSION_COMMAND_DISABLE_SLEEP_TIMER, Bundle.EMPTY));
            return;
        }
        if (!PlaybackService.isRunning) {
            return;
        }
        if (minutes == PlusWear.SLEEP_END_OF_EPISODE) {
            SleepTimerPreferences.setSleepTimerType(SleepTimerType.EPISODES);
            SleepTimerPreferences.setLastTimer("1");
        } else {
            SleepTimerPreferences.setSleepTimerType(SleepTimerType.CLOCK);
            SleepTimerPreferences.setLastTimer(String.valueOf(minutes));
        }
        PlaybackController.bindToMedia3Service(context, controller -> controller.sendCustomCommand(
                MediaLibrarySessionCallback.SESSION_COMMAND_SET_SLEEP_TIMER, Bundle.EMPTY));
    }

    private static void showOutputSwitcher(Context context) {
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                if (MediaRouter2.getInstance(context).showSystemOutputSwitcher()) {
                    return;
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "System output switcher failed", e);
            }
        }
        if (Build.VERSION.SDK_INT >= 31) {
            Intent intent = new Intent("com.android.systemui.action.LAUNCH_MEDIA_OUTPUT_DIALOG")
                    .setPackage("com.android.systemui")
                    .putExtra("package_name", context.getPackageName());
            context.sendBroadcast(intent);
            return;
        }
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    private static List<PlusWear.EpisodeRow> queueRows() {
        FeedMedia media = currentMedia();
        long currentId = media != null && media.getItem() != null ? media.getItem().getId() : 0;
        List<PlusWear.EpisodeRow> rows = new ArrayList<>();
        PlusWear.EpisodeRow currentRow = null;
        for (FeedItem item : DBReader.getQueue()) {
            PlusWear.EpisodeRow row = row(item, item.getId() == currentId);
            if (row.current) {
                currentRow = row;
            } else {
                rows.add(row);
            }
        }
        if (currentRow != null) {
            rows.add(0, currentRow);
        }
        return rows;
    }

    private static PlusWear.EpisodeRow row(FeedItem item, boolean current) {
        String feedTitle = item.getFeed() != null && item.getFeed().getTitle() != null
                ? item.getFeed().getTitle() : "";
        FeedMedia media = item.getMedia();
        return new PlusWear.EpisodeRow(item.getId(), item.getTitle() != null ? item.getTitle() : "", feedTitle,
                media != null ? media.getDuration() : 0, media != null ? media.getPosition() : 0,
                item.getPubDate() != null ? item.getPubDate().getTime() : 0, current);
    }

    private static PlusWear.SearchResults search(String query) {
        List<PlusWear.FeedRow> feeds = new ArrayList<>();
        List<PlusWear.EpisodeRow> episodes = new ArrayList<>();
        if (TextUtils.isEmpty(query.trim())) {
            return new PlusWear.SearchResults(feeds, episodes);
        }
        for (Feed feed : DBReader.searchFeeds(query, FeedItemFilter.unfiltered())) {
            if (feeds.size() >= MAX_SEARCH_RESULTS) {
                break;
            }
            feeds.add(new PlusWear.FeedRow(feed.getId(), feed.getTitle() != null ? feed.getTitle() : ""));
        }
        for (FeedItem item : DBReader.searchFeedItems(0, query, FeedItemFilter.unfiltered())) {
            if (episodes.size() >= MAX_SEARCH_RESULTS) {
                break;
            }
            if (item.hasMedia()) {
                episodes.add(row(item, false));
            }
        }
        return new PlusWear.SearchResults(feeds, episodes);
    }

    private static void sendArt(Context context, String nodeId, String path) {
        String[] parts = path.substring(PlusWear.ART_PREFIX.length()).split("/");
        if (parts.length != 2) {
            return;
        }
        long itemId;
        int size;
        try {
            itemId = Long.parseLong(parts[0]);
            size = Math.min(MAX_ART_SIZE, Integer.parseInt(parts[1]));
        } catch (NumberFormatException e) {
            Log.w(TAG, "Ignoring malformed art path: " + path, e);
            return;
        }
        FeedItem item = DBReader.getFeedItem(itemId);
        byte[] bytes = new byte[0];
        if (item != null) {
            Bitmap bitmap = loadBitmap(context, ImageResourceUtils.getEpisodeListImageLocation(item), size);
            if (bitmap == null) {
                bitmap = loadBitmap(context, ImageResourceUtils.getFallbackImageLocation(item), size);
            }
            if (bitmap != null) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out);
                bytes = out.toByteArray();
            }
        }
        reply(context, nodeId, path, bytes);
    }

    private static Bitmap loadBitmap(Context context, String url, int size) {
        if (TextUtils.isEmpty(url)) {
            return null;
        }
        try {
            return Glide.with(context)
                    .asBitmap()
                    .load(url)
                    .apply(new RequestOptions().centerCrop())
                    .submit(size, size)
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            Log.w(TAG, "Could not load artwork " + url, e);
            return null;
        }
    }

    private static void reply(Context context, String nodeId, String path, byte[] payload) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send reply to " + path, e));
    }
}
