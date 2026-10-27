package de.danoeh.antennapod.ui.widget;

import androidx.annotation.DrawableRes;

final class WidgetJumpIcon {
    private static final int[] SECONDS = {5, 10, 15, 20, 30, 45, 60};
    private static final int[] BACK = {
            R.drawable.ic_widget_jump_back_5, R.drawable.ic_widget_jump_back_10,
            R.drawable.ic_widget_jump_back_15, R.drawable.ic_widget_jump_back_20,
            R.drawable.ic_widget_jump_back_30, R.drawable.ic_widget_jump_back_45,
            R.drawable.ic_widget_jump_back_60};
    private static final int[] FORWARD = {
            R.drawable.ic_widget_jump_forward_5, R.drawable.ic_widget_jump_forward_10,
            R.drawable.ic_widget_jump_forward_15, R.drawable.ic_widget_jump_forward_20,
            R.drawable.ic_widget_jump_forward_30, R.drawable.ic_widget_jump_forward_45,
            R.drawable.ic_widget_jump_forward_60};

    private WidgetJumpIcon() {
    }

    @DrawableRes
    static int back(int seconds) {
        return BACK[indexOf(seconds)];
    }

    @DrawableRes
    static int forward(int seconds) {
        return FORWARD[indexOf(seconds)];
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
