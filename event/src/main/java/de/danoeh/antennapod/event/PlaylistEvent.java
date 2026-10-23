package de.danoeh.antennapod.event;

public class PlaylistEvent {
    public final long playlistId;

    public PlaylistEvent(long playlistId) {
        this.playlistId = playlistId;
    }
}
