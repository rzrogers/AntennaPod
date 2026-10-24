package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.util.Log;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;

import de.danoeh.antennapod.model.feed.FeedMedia;
import de.danoeh.antennapod.storage.preferences.PlaybackPreferences;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import static de.danoeh.antennapod.storage.database.PlaylistTables.KEY_DELAYED_DUE;
import static de.danoeh.antennapod.storage.database.PlaylistTables.KEY_DELAYED_MEDIA;
import static de.danoeh.antennapod.storage.database.PlaylistTables.TABLE_NAME_DELAYED_DELETIONS;

public final class DelayedDeletion {
    private static final String TAG = "DelayedDeletion";

    private DelayedDeletion() {
    }

    private static SQLiteDatabase db() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        return adapter.getPlaylistDatabase();
    }

    public static void deleteOrSchedule(@NonNull Context context, FeedMedia media) {
        long delayMinutes = UserPreferences.getPlusAutoDeleteDelayMinutes();
        if (delayMinutes <= 0 || media == null) {
            DBWriter.deleteFeedMediaOfItem(context, media);
            return;
        }
        long due = System.currentTimeMillis() + delayMinutes * 60 * 1000;
        long mediaId = media.getId();
        DBWriter.runOnQueueThread(() -> {
            ContentValues values = new ContentValues();
            values.put(KEY_DELAYED_MEDIA, mediaId);
            values.put(KEY_DELAYED_DUE, due);
            db().insertWithOnConflict(TABLE_NAME_DELAYED_DELETIONS, null, values, SQLiteDatabase.CONFLICT_REPLACE);
        });
    }

    public static void runDue(@NonNull Context context) {
        List<Long> dueMediaIds = new ArrayList<>();
        try (Cursor cursor = db().rawQuery("SELECT " + KEY_DELAYED_MEDIA + " FROM " + TABLE_NAME_DELAYED_DELETIONS
                + " WHERE " + KEY_DELAYED_DUE + " <= ?", new String[]{String.valueOf(System.currentTimeMillis())})) {
            while (cursor.moveToNext()) {
                dueMediaIds.add(cursor.getLong(0));
            }
        }
        if (dueMediaIds.isEmpty()) {
            return;
        }
        LongList queueIds = DBReader.getQueueIDList();
        Set<Long> playlistIds = PlaylistDB.getAllItemIds();
        long playingMediaId = PlaybackPreferences.getCurrentlyPlayingFeedMediaId();
        for (long mediaId : dueMediaIds) {
            if (mediaId == playingMediaId) {
                continue;
            }
            FeedMedia media = DBReader.getFeedMedia(mediaId);
            boolean keep = media == null || !media.isDownloaded()
                    || queueIds.contains(media.getItemId()) || playlistIds.contains(media.getItemId());
            try {
                if (!keep) {
                    DBWriter.deleteFeedMediaOfItem(context, media).get();
                }
                DBWriter.runOnQueueThread(() -> db().delete(TABLE_NAME_DELAYED_DELETIONS,
                        KEY_DELAYED_MEDIA + "=?", new String[]{String.valueOf(mediaId)})).get();
            } catch (InterruptedException | ExecutionException e) {
                Log.e(TAG, Log.getStackTraceString(e));
            }
        }
    }
}
