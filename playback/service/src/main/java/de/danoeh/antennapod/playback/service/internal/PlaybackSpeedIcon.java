package de.danoeh.antennapod.playback.service.internal;

import androidx.annotation.DrawableRes;
import de.danoeh.antennapod.playback.service.R;

import java.text.DecimalFormat;

final class PlaybackSpeedIcon {
    private static final float MIN_SPEED = 0.5f;
    private static final float STEP = 0.05f;
    private static final int[] ICONS = {
            R.drawable.ic_notification_speed_050,
            R.drawable.ic_notification_speed_055,
            R.drawable.ic_notification_speed_060,
            R.drawable.ic_notification_speed_065,
            R.drawable.ic_notification_speed_070,
            R.drawable.ic_notification_speed_075,
            R.drawable.ic_notification_speed_080,
            R.drawable.ic_notification_speed_085,
            R.drawable.ic_notification_speed_090,
            R.drawable.ic_notification_speed_095,
            R.drawable.ic_notification_speed_100,
            R.drawable.ic_notification_speed_105,
            R.drawable.ic_notification_speed_110,
            R.drawable.ic_notification_speed_115,
            R.drawable.ic_notification_speed_120,
            R.drawable.ic_notification_speed_125,
            R.drawable.ic_notification_speed_130,
            R.drawable.ic_notification_speed_135,
            R.drawable.ic_notification_speed_140,
            R.drawable.ic_notification_speed_145,
            R.drawable.ic_notification_speed_150,
            R.drawable.ic_notification_speed_155,
            R.drawable.ic_notification_speed_160,
            R.drawable.ic_notification_speed_165,
            R.drawable.ic_notification_speed_170,
            R.drawable.ic_notification_speed_175,
            R.drawable.ic_notification_speed_180,
            R.drawable.ic_notification_speed_185,
            R.drawable.ic_notification_speed_190,
            R.drawable.ic_notification_speed_195,
            R.drawable.ic_notification_speed_200,
            R.drawable.ic_notification_speed_205,
            R.drawable.ic_notification_speed_210,
            R.drawable.ic_notification_speed_215,
            R.drawable.ic_notification_speed_220,
            R.drawable.ic_notification_speed_225,
            R.drawable.ic_notification_speed_230,
            R.drawable.ic_notification_speed_235,
            R.drawable.ic_notification_speed_240,
            R.drawable.ic_notification_speed_245,
            R.drawable.ic_notification_speed_250,
            R.drawable.ic_notification_speed_255,
            R.drawable.ic_notification_speed_260,
            R.drawable.ic_notification_speed_265,
            R.drawable.ic_notification_speed_270,
            R.drawable.ic_notification_speed_275,
            R.drawable.ic_notification_speed_280,
            R.drawable.ic_notification_speed_285,
            R.drawable.ic_notification_speed_290,
            R.drawable.ic_notification_speed_295,
            R.drawable.ic_notification_speed_300,
            R.drawable.ic_notification_speed_305,
            R.drawable.ic_notification_speed_310,
            R.drawable.ic_notification_speed_315,
            R.drawable.ic_notification_speed_320,
            R.drawable.ic_notification_speed_325,
            R.drawable.ic_notification_speed_330,
            R.drawable.ic_notification_speed_335,
            R.drawable.ic_notification_speed_340,
            R.drawable.ic_notification_speed_345,
            R.drawable.ic_notification_speed_350,
            R.drawable.ic_notification_speed_355,
            R.drawable.ic_notification_speed_360,
            R.drawable.ic_notification_speed_365,
            R.drawable.ic_notification_speed_370,
            R.drawable.ic_notification_speed_375,
            R.drawable.ic_notification_speed_380,
            R.drawable.ic_notification_speed_385,
            R.drawable.ic_notification_speed_390,
            R.drawable.ic_notification_speed_395,
            R.drawable.ic_notification_speed_400
    };

    private PlaybackSpeedIcon() {
    }

    private static int index(float speed) {
        int index = Math.round((speed - MIN_SPEED) / STEP);
        return Math.max(0, Math.min(ICONS.length - 1, index));
    }

    @DrawableRes
    static int forSpeed(float speed) {
        return ICONS[index(speed)];
    }

    static String label(float speed) {
        return new DecimalFormat("0.##").format(MIN_SPEED + index(speed) * STEP);
    }
}
