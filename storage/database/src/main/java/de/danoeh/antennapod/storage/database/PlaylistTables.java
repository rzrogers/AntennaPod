package de.danoeh.antennapod.storage.database;

import android.database.sqlite.SQLiteDatabase;

import de.danoeh.antennapod.model.feed.FeedItem;

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

    public static final String KEY_PLAYLIST = "playlist";
    public static final String KEY_LOADED = "loaded";
    public static final String KEY_PLAYLIST_POSITION = "playlist_position";

    static final String[] TABLES = {
            TABLE_NAME_PLAYLISTS,
            TABLE_NAME_PLAYLIST_ITEMS,
            TABLE_NAME_PLAYLIST_FEEDS
    };

    private PlaylistTables() {
    }

    static void createIfMissing(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS " + TABLE_NAME_PLAYLISTS + " ("
                + KEY_ID + " INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,"
                + KEY_TITLE + " TEXT,"
                + KEY_LOADED + " INTEGER DEFAULT 0)");
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
    }

    static void removeItems(SQLiteDatabase db, String itemIds) {
        db.delete(TABLE_NAME_PLAYLIST_ITEMS, KEY_FEEDITEM + " IN (" + itemIds + ")", null);
    }

    static void removeFeed(SQLiteDatabase db, long feedId) {
        db.delete(TABLE_NAME_PLAYLIST_FEEDS, KEY_FEED + "=?", new String[]{String.valueOf(feedId)});
    }
}
