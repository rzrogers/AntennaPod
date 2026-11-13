package de.danoeh.antennapod.net.sync.wearinterface;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
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
    public static final String PLAYER = "/plus_player";
    public static final String QUEUE = "/plus_queue";
    public static final String PLAY = "/plus_play";
    public static final String SPEED_NEXT = "/plus_speed_next";
    public static final String SPEED_PREFIX = "/plus_speed/";
    public static final String SLEEP_PREFIX = "/plus_sleep/";
    public static final String VOLUME_UP = "/plus_volume_up";
    public static final String VOLUME_DOWN = "/plus_volume_down";
    public static final String VOLUME = "/plus_volume";
    public static final String OUTPUT_PICKER = "/plus_output_picker";
    public static final String ART_PREFIX = "/plus_art/";
    public static final String SEARCH_PREFIX = "/plus_search/";
    public static final String SEARCH_RESULTS = "/plus_search_results";
    public static final int OUTPUT_SPEAKER = 0;
    public static final int OUTPUT_WIRED = 1;
    public static final int OUTPUT_BLUETOOTH = 2;
    public static final int SLEEP_OFF = 0;
    public static final int SLEEP_END_OF_EPISODE = -1;

    private static final String KEY_ID = "id";
    private static final String KEY_TITLE = "title";
    private static final String KEY_SUBTITLE = "subtitle";
    private static final String KEY_PLAYABLE = "playable";
    private static final String KEY_BACK = "back";
    private static final String KEY_FORWARD = "forward";
    private static final String KEY_RECENT = "recent";
    private static final String KEY_FEED_TITLE = "feed_title";
    private static final String KEY_DURATION = "duration";
    private static final String KEY_POSITION = "position";
    private static final String KEY_PUB_DATE = "pub_date";
    private static final String KEY_CURRENT = "current";
    private static final String KEY_PLAYING = "playing";
    private static final String KEY_SPEED = "speed";
    private static final String KEY_SPEEDS = "speeds";
    private static final String KEY_SLEEP_MILLIS = "sleep_millis";
    private static final String KEY_SLEEP_EPISODE = "sleep_episode";
    private static final String KEY_OUTPUT_TYPE = "output_type";
    private static final String KEY_OUTPUT_NAME = "output_name";
    private static final String KEY_VOLUME = "volume";
    private static final String KEY_VOLUME_MAX = "volume_max";
    private static final String KEY_FEEDS = "feeds";
    private static final String KEY_EPISODES = "episodes";

    private PlusWear() {
    }

    public static String playPlaylistPath(long playlistId) {
        return PLAY_PLAYLIST_PREFIX + playlistId;
    }

    public static String speedPath(float speed) {
        return SPEED_PREFIX + speed;
    }

    public static String sleepPath(int minutes) {
        return SLEEP_PREFIX + minutes;
    }

    public static String artPath(long itemId, int size) {
        return ART_PREFIX + itemId + "/" + size;
    }

    public static String searchPath(@NonNull String query) {
        return SEARCH_PREFIX + query;
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
        public final int recent;

        public PlaylistRow(long id, @NonNull String title, @NonNull String subtitle, boolean playable, int recent) {
            this.id = id;
            this.title = title;
            this.subtitle = subtitle;
            this.playable = playable;
            this.recent = recent;
        }
    }

    public static final class EpisodeRow {
        public final long id;
        @NonNull
        public final String title;
        @NonNull
        public final String feedTitle;
        public final int duration;
        public final int position;
        public final long pubDate;
        public final boolean current;

        public EpisodeRow(long id, @NonNull String title, @NonNull String feedTitle, int duration, int position,
                          long pubDate, boolean current) {
            this.id = id;
            this.title = title;
            this.feedTitle = feedTitle;
            this.duration = duration;
            this.position = position;
            this.pubDate = pubDate;
            this.current = current;
        }
    }

    public static final class FeedRow {
        public final long id;
        @NonNull
        public final String title;

        public FeedRow(long id, @NonNull String title) {
            this.id = id;
            this.title = title;
        }
    }

    public static final class SearchResults {
        @NonNull
        public final List<FeedRow> feeds;
        @NonNull
        public final List<EpisodeRow> episodes;

        public SearchResults(@NonNull List<FeedRow> feeds, @NonNull List<EpisodeRow> episodes) {
            this.feeds = feeds;
            this.episodes = episodes;
        }
    }

    public static final class PlayerState {
        public final long itemId;
        @NonNull
        public final String title;
        @NonNull
        public final String feedTitle;
        public final int position;
        public final int duration;
        public final boolean playing;
        public final float speed;
        @NonNull
        public final float[] speeds;
        public final long sleepMillis;
        public final boolean sleepEpisode;
        public final int outputType;
        @NonNull
        public final String outputName;
        public final int volume;
        public final int volumeMax;

        public PlayerState(long itemId, @NonNull String title, @NonNull String feedTitle, int position, int duration,
                           boolean playing, float speed, @NonNull float[] speeds, long sleepMillis,
                           boolean sleepEpisode, int outputType, @NonNull String outputName,
                           int volume, int volumeMax) {
            this.itemId = itemId;
            this.title = title;
            this.feedTitle = feedTitle;
            this.position = position;
            this.duration = duration;
            this.playing = playing;
            this.speed = speed;
            this.speeds = speeds;
            this.sleepMillis = sleepMillis;
            this.sleepEpisode = sleepEpisode;
            this.outputType = outputType;
            this.outputName = outputName;
            this.volume = volume;
            this.volumeMax = volumeMax;
        }

        public PlayerState withPlaying(boolean newPlaying) {
            return new PlayerState(itemId, title, feedTitle, position, duration, newPlaying, speed, speeds,
                    sleepMillis, sleepEpisode, outputType, outputName, volume, volumeMax);
        }

        public PlayerState withVolume(int newVolume, int newVolumeMax) {
            return new PlayerState(itemId, title, feedTitle, position, duration, playing, speed, speeds,
                    sleepMillis, sleepEpisode, outputType, outputName, newVolume, newVolumeMax);
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
                obj.put(KEY_RECENT, row.recent);
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
                        obj.optString(KEY_SUBTITLE, ""), obj.optBoolean(KEY_PLAYABLE, false),
                        obj.optInt(KEY_RECENT, -1)));
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

    @NonNull
    private static JSONObject episodeRowToJson(@NonNull EpisodeRow row) throws JSONException {
        JSONObject obj = new JSONObject();
        obj.put(KEY_ID, row.id);
        obj.put(KEY_TITLE, row.title);
        obj.put(KEY_FEED_TITLE, row.feedTitle);
        obj.put(KEY_DURATION, row.duration);
        obj.put(KEY_POSITION, row.position);
        obj.put(KEY_PUB_DATE, row.pubDate);
        obj.put(KEY_CURRENT, row.current);
        return obj;
    }

    @NonNull
    private static EpisodeRow episodeRowFromJson(@NonNull JSONObject obj) {
        return new EpisodeRow(obj.optLong(KEY_ID, 0), obj.optString(KEY_TITLE, ""),
                obj.optString(KEY_FEED_TITLE, ""), obj.optInt(KEY_DURATION, 0), obj.optInt(KEY_POSITION, 0),
                obj.optLong(KEY_PUB_DATE, 0), obj.optBoolean(KEY_CURRENT, false));
    }

    @NonNull
    private static JSONArray episodeRowsToJson(@NonNull List<EpisodeRow> rows) {
        JSONArray array = new JSONArray();
        for (EpisodeRow row : rows) {
            try {
                array.put(episodeRowToJson(row));
            } catch (JSONException e) {
                // skip malformed row
            }
        }
        return array;
    }

    @NonNull
    private static List<EpisodeRow> episodeRowsFromJson(JSONArray array) {
        List<EpisodeRow> rows = new ArrayList<>();
        if (array == null) {
            return rows;
        }
        for (int i = 0; i < array.length(); i++) {
            JSONObject obj = array.optJSONObject(i);
            if (obj != null) {
                rows.add(episodeRowFromJson(obj));
            }
        }
        return rows;
    }

    @NonNull
    public static byte[] episodeRowsToBytes(@NonNull List<EpisodeRow> rows) {
        return episodeRowsToJson(rows).toString().getBytes(StandardCharsets.UTF_8);
    }

    @NonNull
    public static List<EpisodeRow> episodeRowsFromBytes(@NonNull byte[] data) {
        try {
            return episodeRowsFromJson(new JSONArray(new String(data, StandardCharsets.UTF_8)));
        } catch (JSONException e) {
            return new ArrayList<>();
        }
    }

    @NonNull
    public static byte[] searchResultsToBytes(@NonNull SearchResults results) {
        try {
            JSONArray feeds = new JSONArray();
            for (FeedRow feed : results.feeds) {
                JSONObject obj = new JSONObject();
                obj.put(KEY_ID, feed.id);
                obj.put(KEY_TITLE, feed.title);
                feeds.put(obj);
            }
            JSONObject obj = new JSONObject();
            obj.put(KEY_FEEDS, feeds);
            obj.put(KEY_EPISODES, episodeRowsToJson(results.episodes));
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    @NonNull
    public static SearchResults searchResultsFromBytes(@NonNull byte[] data) {
        List<FeedRow> feeds = new ArrayList<>();
        try {
            JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray feedArray = obj.optJSONArray(KEY_FEEDS);
            if (feedArray != null) {
                for (int i = 0; i < feedArray.length(); i++) {
                    JSONObject feed = feedArray.optJSONObject(i);
                    if (feed != null) {
                        feeds.add(new FeedRow(feed.optLong(KEY_ID, 0), feed.optString(KEY_TITLE, "")));
                    }
                }
            }
            return new SearchResults(feeds, episodeRowsFromJson(obj.optJSONArray(KEY_EPISODES)));
        } catch (JSONException e) {
            return new SearchResults(feeds, new ArrayList<>());
        }
    }

    @NonNull
    public static byte[] playerStateToBytes(@NonNull PlayerState state) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_ID, state.itemId);
            obj.put(KEY_TITLE, state.title);
            obj.put(KEY_FEED_TITLE, state.feedTitle);
            obj.put(KEY_POSITION, state.position);
            obj.put(KEY_DURATION, state.duration);
            obj.put(KEY_PLAYING, state.playing);
            obj.put(KEY_SPEED, (double) state.speed);
            JSONArray speeds = new JSONArray();
            for (float speed : state.speeds) {
                speeds.put((double) speed);
            }
            obj.put(KEY_SPEEDS, speeds);
            obj.put(KEY_SLEEP_MILLIS, state.sleepMillis);
            obj.put(KEY_SLEEP_EPISODE, state.sleepEpisode);
            obj.put(KEY_OUTPUT_TYPE, state.outputType);
            obj.put(KEY_OUTPUT_NAME, state.outputName);
            obj.put(KEY_VOLUME, state.volume);
            obj.put(KEY_VOLUME_MAX, state.volumeMax);
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    @Nullable
    public static PlayerState playerStateFromBytes(@NonNull byte[] data) {
        if (data.length == 0) {
            return null;
        }
        try {
            JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
            JSONArray speedArray = obj.optJSONArray(KEY_SPEEDS);
            float[] speeds = new float[speedArray != null ? speedArray.length() : 0];
            for (int i = 0; i < speeds.length; i++) {
                speeds[i] = (float) speedArray.optDouble(i, 1.0);
            }
            return new PlayerState(obj.optLong(KEY_ID, 0), obj.optString(KEY_TITLE, ""),
                    obj.optString(KEY_FEED_TITLE, ""), obj.optInt(KEY_POSITION, 0), obj.optInt(KEY_DURATION, 0),
                    obj.optBoolean(KEY_PLAYING, false), (float) obj.optDouble(KEY_SPEED, 1.0), speeds,
                    obj.optLong(KEY_SLEEP_MILLIS, -1), obj.optBoolean(KEY_SLEEP_EPISODE, false),
                    obj.optInt(KEY_OUTPUT_TYPE, OUTPUT_SPEAKER), obj.optString(KEY_OUTPUT_NAME, ""),
                    obj.optInt(KEY_VOLUME, 0), obj.optInt(KEY_VOLUME_MAX, 0));
        } catch (JSONException e) {
            return null;
        }
    }

    @NonNull
    public static byte[] volumeToBytes(int volume, int max) {
        try {
            JSONObject obj = new JSONObject();
            obj.put(KEY_VOLUME, volume);
            obj.put(KEY_VOLUME_MAX, max);
            return obj.toString().getBytes(StandardCharsets.UTF_8);
        } catch (JSONException e) {
            return new byte[0];
        }
    }

    @NonNull
    public static int[] volumeFromBytes(@NonNull byte[] data) {
        try {
            JSONObject obj = new JSONObject(new String(data, StandardCharsets.UTF_8));
            return new int[]{obj.optInt(KEY_VOLUME, 0), obj.optInt(KEY_VOLUME_MAX, 0)};
        } catch (JSONException e) {
            return new int[]{0, 0};
        }
    }
}
