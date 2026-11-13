package de.danoeh.antennapod.net.sync.wearinterface;

import androidx.annotation.NonNull;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public final class PlusWear {
    public static final String PLAYLISTS = "/plus_playlists";
    public static final String PLAY_PLAYLIST_PREFIX = "/plus_play_playlist/";
    public static final String LOADED_PLAYLIST = "/plus_loaded_playlist";
    public static final String JUMP_SECONDS = "/plus_jump_seconds";
    public static final String OPEN_APP_ON_PHONE = "/plus_open_app";

    private static final String KEY_ID = "id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_SUBTITLE = "subtitle";
    private static final String KEY_PLAYABLE = "playable";
    private static final String KEY_BACK = "back";
    private static final String KEY_FORWARD = "forward";

    private PlusWear() {
    }

    public static String playPlaylistPath(long playlistId) {
        return PLAY_PLAYLIST_PREFIX + playlistId;
    }

    public static boolean isPlusPath(@NonNull String path) {
        return path.startsWith("/plus_");
    }

    public static final class PlaylistRow {
        public final long id;
        @NonNull
        public final String title;
        @NonNull
        public final String subtitle;
        public final boolean playable;

        public PlaylistRow(long id, @NonNull String title, @NonNull String subtitle, boolean playable) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.playable = playable;
        }
    }

    @NonNull
    public static byte[] playlistsToBytes(@NonNull List<PlaylistRow> rows) {
        JSONArray array = new JSONArray();
        for (PlaylistRow row : rows) {
            try {
                JSONObject obj = new JSONObject();
                obj.put(KEY_ID, row.id);
                obj.put(KEY_TITLE, row.title);
                obj.put(KEY_SUBTITLE, row.subtitle);
                obj.put(KEY_PLAYABLE, row.playable);
                array.put(obj);
            } catch (JSONException e) {
                // skip malformed row
            }
        }
        return array.toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    public static List<PlaylistRow> playlistsFromBytes(@NonNull byte[] data) {
        List<PlaylistRow> rows = new ArrayList<>();
        try {
            JSONArray array = new JSONArray(new String(data, StandardCharsets.UTF_8));
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.getJSONObject(i);
                rows.add(new PlaylistRow(obj.optLong(KEY_ID, 0), obj.optString(KEY_TITLE, ""),
                        obj.optString(KEY_SUBTITLE, ""), obj.optBoolean(KEY_PLAYABLE, false)));
            }
        } catch (JSONException e) {
            // return whatever we managed to parse
        }
        return rows;
    }

    @NonNull
    public static byte[] titleToBytes(@NonNull String title) {
        return title.getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    public static String titleFromBytes(@NonNull byte[] data) {
        return new String(data, StandardCharsets.UTF_8);
    }

    @NonNull
    public static byte[] jumpSecondsToBytes(int back, int forward) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_BACK, back);
            obj.put(KEY_FORWARD, forward);
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    @NonNull
    public static int[] jumpSecondsFromBytes(@NonNull byte[] data) {
        try {
            JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
            return new int[]{obj.optInt(KEY_BACK, 10), obj.optInt(KEY_FORWARD, 30)};
        } catch (JSONException e) {
            return new int[]{10, 30};
        }
    }
}
