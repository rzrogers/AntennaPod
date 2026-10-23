package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;

import androidx.core.util.Pair;
import androidx.preference.Preference;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

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
            load(feedId, (playlists, currentId) -> showDialog(preference, feedId, playlists, currentId));
            return true;
        });
        load(feedId, (playlists, currentId) -> updateSummary(preference, playlists, currentId));
    }

    private interface Callback {
        void onLoaded(List<Playlist> playlists, long currentId);
    }

    private static void load(long feedId, Callback callback) {
        Observable.fromCallable(() -> new Pair<>(PlaylistDB.getPlaylists(), PlaylistDB.getFeedPlaylistId(feedId)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> callback.onLoaded(result.first, result.second),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void updateSummary(Preference preference, List<Playlist> playlists, long currentId) {
        for (Playlist playlist : playlists) {
            if (playlist.getId() == currentId) {
                preference.setSummary(playlist.getTitle());
                return;
            }
        }
        preference.setSummary(R.string.playlist_none_label);
    }

    private static void showDialog(Preference preference, long feedId, List<Playlist> playlists, long currentId) {
        Context context = preference.getContext();
        String[] names = new String[playlists.size() + 1];
        names[0] = context.getString(R.string.playlist_none_label);
        int checked = 0;
        for (int i = 0; i < playlists.size(); i++) {
            names[i + 1] = playlists.get(i).getTitle();
            if (playlists.get(i).getId() == currentId) {
                checked = i + 1;
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.feed_add_to_playlist_title)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    long playlistId = which == 0 ? 0 : playlists.get(which - 1).getId();
                    PlaylistDB.setFeedPlaylist(feedId, playlistId);
                    updateSummary(preference, playlists, playlistId);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
