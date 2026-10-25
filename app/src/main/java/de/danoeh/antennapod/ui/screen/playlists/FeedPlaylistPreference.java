package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.text.TextUtils;
import android.util.Log;

import androidx.core.util.Pair;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class FeedPlaylistPreference {
    private static final String TAG = "FeedPlaylistPreference";

    private FeedPlaylistPreference() {
    }

    public static void setup(Preference preference, long feedId) {
        if (preference == null) {
            return;
        }
        preference.setOnPreferenceClickListener(p -> {
            load(feedId, (playlists, currentIds) -> showDialog(preference, feedId, playlists, currentIds));
            return true;
        });
        load(feedId, (playlists, currentIds) -> updateSummary(preference, playlists, currentIds));
    }

    private interface Callback {
        void onLoaded(List<Playlist> playlists, Set<Long> currentIds);
    }

    private static void load(long feedId, Callback callback) {
        Observable.fromCallable(() -> new Pair<>(PlaylistDB.getPlaylists(), PlaylistDB.getFeedPlaylistIds(feedId)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> callback.onLoaded(result.first, result.second),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void updateSummary(Preference preference, List<Playlist> playlists, Set<Long> currentIds) {
        List<String> names = new ArrayList<>();
        for (Playlist playlist : playlists) {
            if (currentIds.contains(playlist.getId())) {
                names.add(playlist.getTitle());
            }
        }
        if (names.isEmpty()) {
            preference.setSummary(R.string.playlist_none_label);
        } else {
            preference.setSummary(TextUtils.join(", ", names));
        }
    }

    private static void showDialog(Preference preference, long feedId, List<Playlist> playlists,
                                   Set<Long> currentIds) {
        Context context = preference.getContext();
        String[] names = new String[playlists.size()];
        boolean[] checked = new boolean[playlists.size()];
        for (int i = 0; i < playlists.size(); i++) {
            names[i] = playlists.get(i).getTitle();
            checked[i] = currentIds.contains(playlists.get(i).getId());
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.feed_add_to_playlist_title)
                .setMultiChoiceItems(names, checked, (dialog, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    Set<Long> selected = new HashSet<>();
                    for (int i = 0; i < playlists.size(); i++) {
                        if (checked[i]) {
                            selected.add(playlists.get(i).getId());
                        }
                    }
                    PlaylistDB.setFeedPlaylists(feedId, selected);
                    updateSummary(preference, playlists, selected);
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
