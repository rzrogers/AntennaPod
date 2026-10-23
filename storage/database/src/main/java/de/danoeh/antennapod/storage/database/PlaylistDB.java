package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.mapper.FeedItemCursor;

import static de.danoeh.antennapod.storage.database.PlaylistTables.KEY_LOADED;
import static de.danoeh.antennapod.storage.database.PlaylistTables.KEY_PLAYLIST;
import static de.danoeh.antennapod.storage.database.PlaylistTables.KEY_PLAYLIST_POSITION;
import static de.danoeh.antennapod.storage.database.PlaylistTables.TABLE_NAME_PLAYLISTS;
import static de.danoeh.antennapod.storage.database.PlaylistTables.TABLE_NAME_PLAYLIST_FEEDS;
import static de.danoeh.antennapod.storage.database.PlaylistTables.TABLE_NAME_PLAYLIST_ITEMS;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_FEEDITEM;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_ID;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_READ;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_TITLE;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.TABLE_NAME_FEED_ITEMS;

public final class PlaylistDB {
    private static final String TAG = "PlaylistDB";
    private static final int CHUNK_SIZE = 500;
    private static final ExecutorService dbExec = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r);
        t.setName("PlaylistDatabaseExecutor");
        t.setPriority(Thread.MIN_PRIORITY);
        return t;
    });

    private PlaylistDB() {
    }

    private static SQLiteDatabase db() {
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        adapter.open();
        return adapter.getPlaylistDatabase();
    }

    private static void prune(SQLiteDatabase db) {
        db.execSQL("DELETE FROM " + TABLE_NAME_PLAYLIST_ITEMS + " WHERE " + KEY_FEEDITEM
                + " IN (SELECT " + KEY_ID + " FROM " + TABLE_NAME_FEED_ITEMS
                + " WHERE " + KEY_READ + " = " + FeedItem.PLAYED + ")"
                + " OR " + KEY_FEEDITEM + " NOT IN (SELECT " + KEY_ID + " FROM " + TABLE_NAME_FEED_ITEMS + ")");
    }

    @NonNull
    public static synchronized List<Playlist> getPlaylists() {
        SQLiteDatabase db = db();
        prune(db);
        List<Playlist> result = new ArrayList<>();
        String query = "SELECT p." + KEY_ID + ", p." + KEY_TITLE + ", p." + KEY_LOADED
                + ", (SELECT COUNT(*) FROM " + TABLE_NAME_PLAYLIST_ITEMS + " i WHERE i." + KEY_PLAYLIST
                + " = p." + KEY_ID + ")"
                + " FROM " + TABLE_NAME_PLAYLISTS + " p ORDER BY p." + KEY_TITLE + " COLLATE NOCASE, p." + KEY_ID;
        try (Cursor cursor = db.rawQuery(query, null)) {
            while (cursor.moveToNext()) {
                result.add(new Playlist(cursor.getLong(0), cursor.getString(1),
                        cursor.getInt(2) == 1, cursor.getInt(3)));
            }
        }
        return result;
    }

    @NonNull
    public static synchronized List<FeedItem> getItems(long playlistId) {
        SQLiteDatabase db = db();
        prune(db);
        List<Long> ids = getItemIds(db, playlistId);
        Map<Long, FeedItem> itemsById = new HashMap<>();
        PodDBAdapter adapter = PodDBAdapter.getInstance();
        for (int start = 0; start < ids.size(); start += CHUNK_SIZE) {
            List<Long> chunk = ids.subList(start, Math.min(ids.size(), start + CHUNK_SIZE));
            String[] idStrings = new String[chunk.size()];
            for (int i = 0; i < chunk.size(); i++) {
                idStrings[i] = String.valueOf(chunk.get(i));
            }
            try (FeedItemCursor cursor = new FeedItemCursor(adapter.getFeedItemCursor(idStrings))) {
                while (cursor.moveToNext()) {
                    FeedItem item = cursor.getFeedItem();
                    itemsById.put(item.getId(), item);
                }
            }
        }
        List<FeedItem> items = new ArrayList<>();
        for (long id : ids) {
            FeedItem item = itemsById.get(id);
            if (item != null) {
                items.add(item);
            }
        }
        DBReader.loadFeedDataOfFeedItemList(items);
        return items;
    }

    @NonNull
    public static synchronized Map<Long, Integer> getMembershipCounts(List<Long> itemIds) {
        SQLiteDatabase db = db();
        Map<Long, Integer> counts = new HashMap<>();
        for (int start = 0; start < itemIds.size(); start += CHUNK_SIZE) {
            List<Long> chunk = itemIds.subList(start, Math.min(itemIds.size(), start + CHUNK_SIZE));
            String query = "SELECT " + KEY_PLAYLIST + ", COUNT(DISTINCT " + KEY_FEEDITEM + ") FROM "
                    + TABLE_NAME_PLAYLIST_ITEMS + " WHERE " + KEY_FEEDITEM + " IN ("
                    + TextUtils.join(",", chunk) + ") GROUP BY " + KEY_PLAYLIST;
            try (Cursor cursor = db.rawQuery(query, null)) {
                while (cursor.moveToNext()) {
                    long playlistId = cursor.getLong(0);
                    Integer previous = counts.get(playlistId);
                    counts.put(playlistId, (previous == null ? 0 : previous) + cursor.getInt(1));
                }
            }
        }
        return counts;
    }

    private static List<Long> getItemIds(SQLiteDatabase db, long playlistId) {
        List<Long> ids = new ArrayList<>();
        try (Cursor cursor = db.query(TABLE_NAME_PLAYLIST_ITEMS, new String[]{KEY_FEEDITEM},
                KEY_PLAYLIST + "=?", new String[]{String.valueOf(playlistId)},
                null, null, KEY_PLAYLIST_POSITION + " ASC, " + KEY_ID + " ASC")) {
            while (cursor.moveToNext()) {
                ids.add(cursor.getLong(0));
            }
        }
        return ids;
    }

    private static Future<?> runOnDbThread(Runnable runnable) {
        return dbExec.submit(() -> {
            try {
                synchronized (PlaylistDB.class) {
                    runnable.run();
                }
            } catch (RuntimeException e) {
                Log.e(TAG, Log.getStackTraceString(e));
            }
        });
    }

    public static Future<?> createPlaylist(String title, List<Long> itemIds) {
        return runOnDbThread(() -> {
            ContentValues values = new ContentValues();
            values.put(KEY_TITLE, title);
            values.put(KEY_LOADED, 0);
            long playlistId = db().insert(TABLE_NAME_PLAYLISTS, null, values);
            if (itemIds != null && !itemIds.isEmpty()) {
                insertItems(db(), playlistId, itemIds);
            }
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }

    public static Future<?> renamePlaylist(long playlistId, String title) {
        return runOnDbThread(() -> {
            ContentValues values = new ContentValues();
            values.put(KEY_TITLE, title);
            db().update(TABLE_NAME_PLAYLISTS, values, KEY_ID + "=?", new String[]{String.valueOf(playlistId)});
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }

    public static Future<?> deletePlaylist(long playlistId) {
        return runOnDbThread(() -> {
            SQLiteDatabase db = db();
            String[] args = new String[]{String.valueOf(playlistId)};
            db.beginTransactionNonExclusive();
            try {
                db.delete(TABLE_NAME_PLAYLIST_ITEMS, KEY_PLAYLIST + "=?", args);
                db.delete(TABLE_NAME_PLAYLIST_FEEDS, KEY_PLAYLIST + "=?", args);
                db.delete(TABLE_NAME_PLAYLISTS, KEY_ID + "=?", args);
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }

    public static Future<?> addItems(long playlistId, List<Long> itemIds) {
        return runOnDbThread(() -> {
            insertItems(db(), playlistId, itemIds);
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }

    private static void insertItems(SQLiteDatabase db, long playlistId, List<Long> itemIds) {
        Set<Long> existing = new HashSet<>(getItemIds(db, playlistId));
        long position = 0;
        try (Cursor cursor = db.rawQuery("SELECT MAX(" + KEY_PLAYLIST_POSITION + ") FROM "
                + TABLE_NAME_PLAYLIST_ITEMS + " WHERE " + KEY_PLAYLIST + "=?",
                new String[]{String.valueOf(playlistId)})) {
            if (cursor.moveToFirst() && !cursor.isNull(0)) {
                position = cursor.getLong(0) + 1;
            }
        }
        db.beginTransactionNonExclusive();
        try {
            ContentValues values = new ContentValues();
            for (long itemId : itemIds) {
                if (!existing.add(itemId)) {
                    continue;
                }
                values.clear();
                values.put(KEY_PLAYLIST, playlistId);
                values.put(KEY_FEEDITEM, itemId);
                values.put(KEY_PLAYLIST_POSITION, position++);
                db.insert(TABLE_NAME_PLAYLIST_ITEMS, null, values);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public static Future<?> removeItems(long playlistId, List<Long> itemIds) {
        return runOnDbThread(() -> {
            SQLiteDatabase db = db();
            db.beginTransactionNonExclusive();
            try {
                for (long itemId : itemIds) {
                    db.delete(TABLE_NAME_PLAYLIST_ITEMS, KEY_PLAYLIST + "=? AND " + KEY_FEEDITEM + "=?",
                            new String[]{String.valueOf(playlistId), String.valueOf(itemId)});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }

    public static Future<?> setOrder(long playlistId, List<Long> itemIds) {
        return runOnDbThread(() -> {
            SQLiteDatabase db = db();
            db.beginTransactionNonExclusive();
            try {
                ContentValues values = new ContentValues();
                for (int i = 0; i < itemIds.size(); i++) {
                    values.clear();
                    values.put(KEY_PLAYLIST_POSITION, i);
                    db.update(TABLE_NAME_PLAYLIST_ITEMS, values, KEY_PLAYLIST + "=? AND " + KEY_FEEDITEM + "=?",
                            new String[]{String.valueOf(playlistId), String.valueOf(itemIds.get(i))});
                }
                db.setTransactionSuccessful();
            } finally {
                db.endTransaction();
            }
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        });
    }
}
