package de.danoeh.antennapod.model.feed;

public class Playlist {
    private final long id;
    private final String title;
    private final boolean loaded;
    private final int episodeCount;

    public Playlist(long id, String title, boolean loaded, int episodeCount) {
        this.id = id;
        this.title = title;
        this.loaded = loaded;
        this.episodeCount = episodeCount;
    }

    public long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public boolean isLoaded() {
        return loaded;
    }

    public int getEpisodeCount() {
        return episodeCount;
    }
}
