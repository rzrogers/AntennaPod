package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.Nullable;

import com.google.android.material.appbar.MaterialToolbar;

import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class QueuePlaylistMenu {
    private static final String TAG = "QueuePlaylistMenu";

    private QueuePlaylistMenu() {
    }

    public static Disposable refresh(MaterialToolbar toolbar) {
        return Observable.fromCallable(() -> {
            Playlist loaded = PlaylistDB.getLoadedPlaylist();
            return loaded == null ? Collections.<Playlist>emptyList() : Collections.singletonList(loaded);
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe((List<Playlist> loaded) -> update(toolbar, loaded.isEmpty() ? null : loaded.get(0)),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void update(MaterialToolbar toolbar, @Nullable Playlist loaded) {
        toolbar.setTitle(loaded != null ? loaded.getTitle() : toolbar.getContext().getString(R.string.queue_label));
        Menu menu = toolbar.getMenu();
        MenuItem unload = menu.findItem(R.id.unload_playlist_item);
        if (unload != null) {
            unload.setVisible(loaded != null);
        }
        MenuItem save = menu.findItem(R.id.save_queue_as_playlist_item);
        if (save != null) {
            save.setVisible(loaded == null);
        }
    }

    public static boolean onMenuItemClick(Context context, int itemId) {
        if (itemId == R.id.unload_playlist_item) {
            DBWriter.clearQueue();
            return true;
        } else if (itemId == R.id.save_queue_as_playlist_item) {
            PlaylistDialogs.showSaveQueue(context);
            return true;
        }
        return false;
    }
}
