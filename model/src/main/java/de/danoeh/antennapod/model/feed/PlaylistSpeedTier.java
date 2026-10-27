package de.danoeh.antennapod.model.feed;

public class PlaylistSpeedTier {
    private final int hours;
    private final float speed;

    public PlaylistSpeedTier(int hours, float speed) {
        this.hours = hours;
        this.speed = speed;
    }

    public int getHours() {
        return hours;
    }

    public float getSpeed() {
        return speed;
    }
}
