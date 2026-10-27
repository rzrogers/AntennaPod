package de.danoeh.antennapod.ui.screen.playlists;

import android.app.Activity;
import android.content.Context;
import android.net.Uri;
import android.text.TextUtils;
import android.util.Log;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.apache.commons.io.IOUtils;
import org.greenrobot.eventbus.EventBus;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.Feed;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.model.feed.PlaylistSpeedTier;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.DBReader;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class PlaylistBackup {
    private static final String TAG = "PlaylistBackup";
    public static final String FILE_NAME = "antennapod-plus-playlists.json";
    private static final int VERSION = 1;

    private PlaylistBackup() {
    }

    public static void exportTo(Context context, Uri uri) {
        Context appContext = context.getApplicationContext();
        Observable.fromCallable(() -> {
            JSONArray playlists = new JSONArray();
            for (Playlist playlist : PlaylistDB.getPlaylists()) {
                JSONArray episodes = new JSONArray();
                for (FeedItem item : PlaylistDB.getItems(playlist.getId())) {
                    JSONObject episode = new JSONObject();
                    episode.put("feedUrl", item.getFeed() != null ? item.getFeed().getDownloadUrl() : "");
                    episode.put("feedTitle", item.getFeed() != null ? item.getFeed().getTitle() : "");
                    episode.put("guid", item.getItemIdentifier() != null ? item.getItemIdentifier() : "");
                    episode.put("mediaUrl", item.getMedia() != null ? item.getMedia().getDownloadUrl() : "");
                    episode.put("title", item.getTitle() != null ? item.getTitle() : "");
                    episodes.put(episode);
                }
                JSONObject entry = new JSONObject();
                entry.put("title", playlist.getTitle());
                entry.put("autoDownload", playlist.getAutoDownload());
                entry.put("addLocation", playlist.getAddLocation());
                JSONArray speeds = new JSONArray();
                for (PlaylistSpeedTier tier : PlaylistDB.getSpeedTiers(playlist.getId())) {
                    JSONObject speed = new JSONObject();
                    speed.put("hours", tier.getHours());
                    speed.put("speed", Math.round(tier.getSpeed() * 100) / 100.0);
                    speeds.put(speed);
                }
                entry.put("speeds", speeds);
                entry.put("episodes", episodes);
                playlists.put(entry);
            }
            JSONObject root = new JSONObject();
            root.put("app", "AntennaPod+");
            root.put("version", VERSION);
            root.put("playlists", playlists);
            try (OutputStream out = appContext.getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) {
                    throw new IOException("No output stream");
                }
                out.write(root.toString(2).getBytes(StandardCharsets.UTF_8));
            }
            return true;
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(done -> EventBus.getDefault().post(
                        new MessageEvent(appContext.getString(R.string.playlists_export_done_message))),
                        error -> {
                            Log.e(TAG, Log.getStackTraceString(error));
                            String message = appContext.getString(R.string.export_error_label);
                            EventBus.getDefault().post(new MessageEvent(message + ": " + error.getMessage()));
                        });
    }

    public static void importFrom(Activity activity, Uri uri) {
        Context appContext = activity.getApplicationContext();
        Observable.fromCallable(() -> {
            String text;
            try (InputStream in = appContext.getContentResolver().openInputStream(uri)) {
                if (in == null) {
                    throw new IOException("No input stream");
                }
                text = IOUtils.toString(in, StandardCharsets.UTF_8);
            }
            JSONArray playlists = new JSONObject(text).getJSONArray("playlists");
            Map<String, Feed> feedsByUrl = new HashMap<>();
            for (Feed feed : DBReader.getFeedList()) {
                feedsByUrl.put(feed.getDownloadUrl(), feed);
            }
            Map<Long, List<FeedItem>> itemsByFeed = new HashMap<>();
            List<String> missing = new ArrayList<>();
            for (int i = 0; i < playlists.length(); i++) {
                JSONObject entry = playlists.getJSONObject(i);
                String title = entry.getString("title").trim();
                if (title.isEmpty()) {
                    continue;
                }
                JSONArray episodes = entry.optJSONArray("episodes");
                List<Long> itemIds = new ArrayList<>();
                for (int j = 0; episodes != null && j < episodes.length(); j++) {
                    JSONObject episode = episodes.getJSONObject(j);
                    FeedItem item = findItem(episode, feedsByUrl, itemsByFeed);
                    if (item != null) {
                        itemIds.add(item.getId());
                    } else {
                        missing.add(episode.optString("feedTitle") + ": " + episode.optString("title"));
                    }
                }
                List<PlaylistSpeedTier> speedTiers = null;
                JSONArray speeds = entry.optJSONArray("speeds");
                if (speeds != null) {
                    speedTiers = new ArrayList<>();
                    for (int j = 0; j < speeds.length(); j++) {
                        JSONObject speed = speeds.getJSONObject(j);
                        speedTiers.add(new PlaylistSpeedTier(speed.optInt("hours", -1),
                                (float) speed.optDouble("speed", 0)));
                    }
                }
                PlaylistDB.importPlaylist(title, entry.optInt("autoDownload", 0),
                        entry.optInt("addLocation", PlaylistDB.ADD_LOCATION_BACK), itemIds, speedTiers).get();
            }
            return new ImportResult(playlists.length(), missing);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> {
                    EventBus.getDefault().post(new MessageEvent(appContext.getResources().getQuantityString(
                            R.plurals.playlists_import_done_message, result.count, result.count)));
                    if (!result.missing.isEmpty() && !activity.isFinishing() && !activity.isDestroyed()) {
                        new MaterialAlertDialogBuilder(activity)
                                .setTitle(R.string.playlists_import_missing_title)
                                .setMessage(activity.getString(R.string.playlists_import_missing_message,
                                        TextUtils.join("\n", result.missing)))
                                .setPositiveButton(android.R.string.ok, null)
                                .show();
                    }
                }, error -> {
                    Log.e(TAG, Log.getStackTraceString(error));
                    EventBus.getDefault().post(new MessageEvent(
                            appContext.getString(R.string.playlists_import_failed_message)));
                });
    }

    private static FeedItem findItem(JSONObject episode, Map<String, Feed> feedsByUrl,
                                     Map<Long, List<FeedItem>> itemsByFeed) {
        Feed feed = feedsByUrl.get(episode.optString("feedUrl"));
        if (feed == null) {
            return null;
        }
        List<FeedItem> items = itemsByFeed.get(feed.getId());
        if (items == null) {
            items = DBReader.getFeedItemList(feed, FeedItemFilter.unfiltered(),
                    SortOrder.DATE_NEW_OLD, 0, Integer.MAX_VALUE);
            itemsByFeed.put(feed.getId(), items);
        }
        String guid = episode.optString("guid");
        String mediaUrl = episode.optString("mediaUrl");
        String title = episode.optString("title");
        if (!guid.isEmpty()) {
            for (FeedItem item : items) {
                if (guid.equals(item.getItemIdentifier())) {
                    return item;
                }
            }
        }
        if (!mediaUrl.isEmpty()) {
            for (FeedItem item : items) {
                if (item.getMedia() != null && mediaUrl.equals(item.getMedia().getDownloadUrl())) {
                    return item;
                }
            }
        }
        if (!title.isEmpty()) {
            for (FeedItem item : items) {
                if (title.equals(item.getTitle())) {
                    return item;
                }
            }
        }
        return null;
    }

    private static class ImportResult {
        final int count;
        final List<String> missing;

        ImportResult(int count, List<String> missing) {
            this.count = count;
            this.missing = missing;
        }
    }
}
