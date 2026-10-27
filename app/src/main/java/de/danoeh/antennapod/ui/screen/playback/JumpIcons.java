package de.danoeh.antennapod.ui.screen.playback;

import androidx.annotation.DrawableRes;
import de.danoeh.antennapod.R;

public final class JumpIcons {
    private static final int[] SECONDS = {5, 10, 15, 20, 30, 45, 60};
    private static final int[] BACK = {
            R.drawable.ic_jump_back_5, R.drawable.ic_jump_back_10, R.drawable.ic_jump_back_15,
            R.drawable.ic_jump_back_20, R.drawable.ic_jump_back_30, R.drawable.ic_jump_back_45,
            R.drawable.ic_jump_back_60};
    private static final int[] FORWARD = {
            R.drawable.ic_jump_forward_5, R.drawable.ic_jump_forward_10, R.drawable.ic_jump_forward_15,
            R.drawable.ic_jump_forward_20, R.drawable.ic_jump_forward_30, R.drawable.ic_jump_forward_45,
            R.drawable.ic_jump_forward_60};
    private static final int[] BACK_VIDEO = {
            R.drawable.ic_jump_back_5_video_white, R.drawable.ic_jump_back_10_video_white,
            R.drawable.ic_jump_back_15_video_white, R.drawable.ic_jump_back_20_video_white,
            R.drawable.ic_jump_back_30_video_white, R.drawable.ic_jump_back_45_video_white,
            R.drawable.ic_jump_back_60_video_white};
    private static final int[] FORWARD_VIDEO = {
            R.drawable.ic_jump_forward_5_video_white, R.drawable.ic_jump_forward_10_video_white,
            R.drawable.ic_jump_forward_15_video_white, R.drawable.ic_jump_forward_20_video_white,
            R.drawable.ic_jump_forward_30_video_white, R.drawable.ic_jump_forward_45_video_white,
            R.drawable.ic_jump_forward_60_video_white};

    private JumpIcons() {
    }

    @DrawableRes
    public static int back(int seconds) {
        return BACK[indexOf(seconds)];
    }

    @DrawableRes
    public static int forward(int seconds) {
        return FORWARD[indexOf(seconds)];
    }

    @DrawableRes
    public static int backVideo(int seconds) {
        return BACK_VIDEO[indexOf(seconds)];
    }

    @DrawableRes
    public static int forwardVideo(int seconds) {
        return FORWARD_VIDEO[indexOf(seconds)];
    }

    private static int indexOf(int seconds) {
        int best = 0;
        for (int i = 1; i < SECONDS.length; i++) {
            if (Math.abs(SECONDS[i] - seconds) < Math.abs(SECONDS[best] - seconds)) {
                best = i;
            }
        }
        return best;
    }
}
