package de.danoeh.antennapod.storage.database;

import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;

import org.greenrobot.eventbus.EventBus;

import java.util.List;

import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_READ;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.TABLE_NAME_FEED_ITEMS;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_FEED;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_FEEDITEM;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_ID;
import static de.danoeh.antennapod.storage.database.PodDBAdapter.KEY_TITLE;

public final class PlaylistTables {
    public static final String TABLE_NAME_PLAYLISTS = "Playlists";
    public static final String TABLE_NAME_PLAYLIST_ITEMS = "PlaylistItems";
    public static final String TABLE_NAME_PLAYLIST_FEEDS = "PlaylistFeeds";
    public static final String TABLE_NAME_DELAYED_DELETIONS = "DelayedDeletions";

    public static final String KEY_PLAYLIST = "playlist";
    public static final String KEY_LOADED = "loaded";
    public static final String KEY_PLAYLIST_POSITION = "playlist_position";
    public static final String KEY_PLAYLIST_AUTO_DOWNLOAD = "auto_download";
    public static final String KEY_DELAYED_MEDIA = "media";
    public static final String KEY_DELAYED_DUE = "due";

    static final String[] TABLES = {
            TABLE_NAME_PLAYLISTS,
            TABLE_NAME_PLAYLIST_ITEMS,
            TABLE_NAME_PLAYLIST_FEEDS,
            TABLE_NAME_DELAYED_DELETIONS
    };

    private PlaylistTables() {
    }

    static void createIfMissing(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_PLAYLISTS + " ("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + KEY_TITLE + " TEXT,"
                + KEY_LOADED + " INTEGER DEFAULT 0,"
                + KEY_PLAYLIST_AUTO_DOWNLOAD + " INTEGER DEFAULT 0)");
        boolean hasAutoDownload = false;
        try (Cursor cursor = db.rawQuery("PRAGMA table_info(" + TABLE_NAME_PLAYLISTS + ")", null)) {
            int nameIndex = cursor.getColumnIndex("name");
            while (cursor.moveToNext()) {
                hasAutoDownload |= KEY_PLAYLIST_AUTO_DOWNLOAD.equals(cursor.getString(nameIndex));
            }
        }
        if (!hasAutoDownload) {
            db.execSQL("ALTER TABLE " + TABLE_NAME_PLAYLISTS + " ADD COLUMN "
                    + KEY_PLAYLIST_AUTO_DOWNLOAD + " INTEGER DEFAULT 0");
        }
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_PLAYLIST_ITEMS + " ("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + KEY_PLAYLIST + " INTEGER,"
                + KEY_FEEDITEM + " INTEGER,"
                + KEY_PLAYLIST_POSITION + " INTEGER)");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_PLAYLIST_FEEDS + " ("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + KEY_PLAYLIST + " INTEGER,"
                + KEY_FEED + " INTEGER)");
        db.execSQL("CREATE INDEX IF NOT EXISTS " + TABLE_NAME_PLAYLIST_ITEMS + "_" + KEY_PLAYLIST
                + " ON " + TABLE_NAME_PLAYLIST_ITEMS + " (" + KEY_PLAYLIST + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS " + TABLE_NAME_PLAYLIST_ITEMS + "_" + KEY_FEEDITEM
                + " ON " + TABLE_NAME_PLAYLIST_ITEMS + " (" + KEY_FEEDITEM + ")");
        db.execSQL("CREATE INDEX IF NOT EXISTS " + TABLE_NAME_PLAYLIST_FEEDS + "_" + KEY_FEED
                + " ON " + TABLE_NAME_PLAYLIST_FEEDS + " (" + KEY_FEED + ")");
        db.execSQL("DROP TRIGGER IF EXISTS " + TABLE_NAME_PLAYLIST_ITEMS + "_played");
        db.execSQL("CREATE TRIGGER IF NOT EXISTS " + TABLE_NAME_PLAYLIST_ITEMS + "_finished"
                + " AFTER UPDATE OF " + KEY_READ + " ON " + TABLE_NAME_FEED_ITEMS
                + " WHEN NEW." + KEY_READ + " = " + FeedItem.PLAYED
                + " AND OLD." + KEY_READ + " != " + FeedItem.PLAYED
                + " BEGIN DELETE FROM " + TABLE_NAME_PLAYLIST_ITEMS
                + " WHERE " + KEY_FEEDITEM + " = NEW." + KEY_ID + "; END");
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_DELAYED_DELETIONS + " ("
                + KEY_DELAYED_MEDIA + " INTEGER PRIMARY KEY NOT NULL,"
                + KEY_DELAYED_DUE + " INTEGER)");
        UserPreferences.setPlusPlaylistLoaded(getLoadedPlaylistId(db) != 0);
    }

    static long getLoadedPlaylistId(SQLiteDatabase db) {
        try (Cursor cursor = db.rawQuery("SELECT " + KEY_ID + " FROM " + TABLE_NAME_PLAYLISTS
                + " WHERE " + KEY_LOADED + " = 1 LIMIT 1", null)) {
            return cursor.moveToFirst() ? cursor.getLong(0) : 0;
        }
    }

    static long setLoaded(SQLiteDatabase db, long playlistId) {
        long previous = getLoadedPlaylistId(db);
        db.execSQL("UPDATE " + TABLE_NAME_PLAYLISTS + " SET " + KEY_LOADED + " = CASE WHEN "
                + KEY_ID + " = ? THEN 1 ELSE 0 END", new Object[]{playlistId});
        UserPreferences.setPlusPlaylistLoaded(getLoadedPlaylistId(db) != 0);
        return previous;
    }

    static long mirrorQueue(SQLiteDatabase db, List<FeedItem> queue) {
        long playlistId = getLoadedPlaylistId(db);
        if (playlistId == 0) {
            return 0;
        }
        db.delete(TABLE_NAME_PLAYLIST_ITEMS, KEY_PLAYLIST + "=?", new String[]{String.valueOf(playlistId)});
        ContentValues values = new ContentValues();
        for (int i = 0; i < queue.size(); i++) {
            values.clear();
            values.put(KEY_PLAYLIST, playlistId);
            values.put(KEY_FEEDITEM, queue.get(i).getId());
            values.put(KEY_PLAYLIST_POSITION, i);
            db.insert(TABLE_NAME_PLAYLIST_ITEMS, null, values);
        }
        return playlistId;
    }

    static void postChanged(long playlistId) {
        if (playlistId != 0) {
            EventBus.getDefault().post(new PlaylistEvent(playlistId));
        }
    }

    static void removeItems(SQLiteDatabase db, String itemIds) {
        db.delete(TABLE_NAME_PLAYLIST_ITEMS, KEY_FEEDITEM + " IN (" + itemIds + ")", null);
    }

    static void removeFeed(SQLiteDatabase db, long feedId) {
        db.delete(TABLE_NAME_PLAYLIST_FEEDS, KEY_FEED + "=?", new String[]{String.valueOf(feedId)});
    }
}
