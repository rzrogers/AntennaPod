package de.danoeh.antennapod.model.feed;

public class Playlist {
    private final long id;
    private final String title;
    private final boolean loaded;
    private final int episodeCount;
    private final int autoDownload;
    private final int addLocation;

    public Playlist(long id, String title, boolean loaded, int episodeCount, int autoDownload, int addLocation) {
        this.id = id;
        this.title = title;
        this.loaded = loaded;
        this.episodeCount = episodeCount;
        this.autoDownload = autoDownload;
        this.addLocation = addLocation;
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

    public int getAutoDownload() {
        return autoDownload;
    }

    public int getAddLocation() {
        return addLocation;
    }
}
