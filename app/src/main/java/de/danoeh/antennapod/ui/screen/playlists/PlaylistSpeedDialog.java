package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.util.Consumer;

import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.model.feed.PlaylistSpeedTier;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.screen.playback.PlaybackSpeedSeekBar;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class PlaylistSpeedDialog {
    private static final String TAG = "PlaylistSpeedDialog";
    private static final float DEFAULT_SPEED = 1.25f;
    private static final int DEFAULT_HOURS = 20;

    private PlaylistSpeedDialog() {
    }

    public static String formatSpeed(float speed) {
        return new DecimalFormat("0.##", DecimalFormatSymbols.getInstance(Locale.getDefault())).format(speed);
    }

    public static String withSpeed(Context context, long playlistId, String rest) {
        float speed = UserPreferences.getPlusPlaylistSpeed();
        int hours = UserPreferences.getPlusPlaylistSpeedHours();
        if (speed <= 0 || hours < 0 || UserPreferences.getPlusPlaylistSpeedPlaylist() != playlistId) {
            return rest;
        }
        return context.getString(R.string.playlist_speed_info, formatSpeed(speed), hours, rest);
    }

    public static void show(Context context, Playlist playlist) {
        Observable.fromCallable(() -> new Loaded(PlaylistDB.getSpeedTiers(playlist.getId()),
                        PlaylistDB.getTimeLeft(playlist.getId())))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(loaded -> showDialog(context, playlist, loaded),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showDialog(Context context, Playlist playlist, Loaded loaded) {
        View root = LayoutInflater.from(context).inflate(R.layout.playlist_speed_dialog, null);
        TextView message = root.findViewById(R.id.speed_message);
        LinearLayout rows = root.findViewById(R.id.speed_tiers);
        String timeLeft = Converter.getDurationStringLocalized(context.getResources(), loaded.timeLeft, false);
        Runnable updateMessage = () -> message.setText(context.getString(rows.getChildCount() > 0
                ? R.string.playlist_speed_message_on : R.string.playlist_speed_message_off, timeLeft));
        for (PlaylistSpeedTier tier : loaded.tiers) {
            addRow(context, rows, tier.getHours(), tier.getSpeed(), updateMessage);
        }
        updateMessage.run();
        root.findViewById(R.id.add_speed_button).setOnClickListener(v -> {
            int hours = DEFAULT_HOURS;
            float speed = DEFAULT_SPEED;
            if (rows.getChildCount() > 0) {
                View last = rows.getChildAt(rows.getChildCount() - 1);
                hours = readHours(last, 0) + DEFAULT_HOURS;
                speed = Math.min(4.0f, ((float) last.getTag()) + 0.25f);
            }
            addRow(context, rows, hours, speed, updateMessage);
            updateMessage.run();
        });
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.playlist_speed_label)
                .setView(root)
                .setPositiveButton(android.R.string.ok, (dialog, which) ->
                        PlaylistDB.setSpeedTiers(playlist.getId(), readTiers(rows)))
                .setNegativeButton(R.string.cancel_label, null);
        if (!loaded.tiers.isEmpty()) {
            builder.setNeutralButton(R.string.playlist_speed_turn_off, (dialog, which) ->
                    PlaylistDB.setSpeedTiers(playlist.getId(), new ArrayList<>()));
        }
        builder.show();
    }

    private static void addRow(Context context, LinearLayout rows, int hours, float speed, Runnable onChanged) {
        View row = LayoutInflater.from(context).inflate(R.layout.playlist_speed_tier, rows, false);
        EditText hoursInput = row.findViewById(R.id.tier_hours);
        MaterialButton speedButton = row.findViewById(R.id.tier_speed);
        hoursInput.setText(String.valueOf(hours));
        Consumer<Float> setSpeed = newSpeed -> {
            row.setTag(newSpeed);
            speedButton.setText(context.getString(R.string.playlist_speed_value, formatSpeed(newSpeed)));
        };
        setSpeed.accept(speed);
        speedButton.setOnClickListener(v -> showPicker(context, readHours(row, hours),
                (float) row.getTag(), setSpeed));
        row.findViewById(R.id.tier_remove).setOnClickListener(v -> {
            rows.removeView(row);
            onChanged.run();
        });
        rows.addView(row);
    }

    private static int readHours(View row, int fallback) {
        String text = ((EditText) row.findViewById(R.id.tier_hours)).getText().toString().trim();
        if (TextUtils.isEmpty(text)) {
            return fallback;
        }
        try {
            return Integer.parseInt(text);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static List<PlaylistSpeedTier> readTiers(LinearLayout rows) {
        List<PlaylistSpeedTier> tiers = new ArrayList<>();
        for (int i = 0; i < rows.getChildCount(); i++) {
            View row = rows.getChildAt(i);
            int hours = readHours(row, -1);
            if (hours >= 0) {
                tiers.add(new PlaylistSpeedTier(hours, (float) row.getTag()));
            }
        }
        return tiers;
    }

    private static void showPicker(Context context, int hours, float speed, Consumer<Float> onPicked) {
        BottomSheetDialog sheet = new BottomSheetDialog(context);
        View root = LayoutInflater.from(context).inflate(R.layout.playlist_speed_picker, null);
        TextView title = root.findViewById(R.id.picker_title);
        TextView value = root.findViewById(R.id.picker_value);
        PlaybackSpeedSeekBar seekBar = root.findViewById(R.id.picker_seek_bar);
        ChipGroup presets = root.findViewById(R.id.picker_presets);
        title.setText(context.getString(R.string.playlist_speed_picker_title, hours));
        float[] picked = {speed};
        List<Chip> chips = new ArrayList<>();
        Consumer<Float> update = newSpeed -> {
            picked[0] = newSpeed;
            value.setText(context.getString(R.string.playlist_speed_value,
                    String.format(Locale.getDefault(), "%1$.2f", newSpeed)));
            for (Chip chip : chips) {
                chip.setChecked(Math.abs((float) chip.getTag() - newSpeed) < 0.001f);
            }
        };
        for (float preset : UserPreferences.getPlaybackSpeedArray()) {
            Chip chip = new Chip(context);
            chip.setTag(preset);
            chip.setCheckable(true);
            chip.setCheckedIconVisible(false);
            chip.setText(String.format(Locale.getDefault(), "%1$.2f", preset));
            chip.setOnClickListener(v -> {
                seekBar.updateSpeed(preset);
                update.accept(preset);
            });
            chips.add(chip);
            presets.addView(chip);
        }
        seekBar.updateSpeed(speed);
        seekBar.setProgressChangedListener(update);
        update.accept(speed);
        Button cancel = root.findViewById(R.id.picker_cancel);
        Button ok = root.findViewById(R.id.picker_ok);
        cancel.setOnClickListener(v -> sheet.dismiss());
        ok.setOnClickListener(v -> {
            onPicked.accept(Math.round(picked[0] * 20) / 20.0f);
            sheet.dismiss();
        });
        sheet.setContentView(root);
        sheet.show();
    }

    private static class Loaded {
        final List<PlaylistSpeedTier> tiers;
        final long timeLeft;

        Loaded(List<PlaylistSpeedTier> tiers, long timeLeft) {
            this.tiers = tiers;
            this.timeLeft = timeLeft;
        }
    }
}
