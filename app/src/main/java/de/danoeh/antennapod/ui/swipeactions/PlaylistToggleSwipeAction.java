package de.danoeh.antennapod.ui.swipeactions;

import android.content.Context;
import android.util.Log;

import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class PlaylistToggleSwipeAction implements SwipeAction {
    private static final String TAG = "PlaylistToggleSwipe";
    static final String ID_PREFIX = "PLAYLIST_";

    private final long playlistId;
    private final String title;

    public PlaylistToggleSwipeAction(long playlistId, String title) {
        this.playlistId = playlistId;
        this.title = title;
    }

    static SwipeAction fromId(String key) {
        if (key == null || !key.startsWith(ID_PREFIX)) {
            return null;
        }
        try {
            long playlistId = Long.parseLong(key.substring(ID_PREFIX.length()));
            String title = PlaylistDB.getPlaylistTitle(playlistId);
            return title == null ? null : new PlaylistToggleSwipeAction(playlistId, title);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    @Override
    public String getId() {
        return ID_PREFIX + playlistId;
    }

    @Override
    public int getActionIcon() {
        return R.drawable.ic_playlists;
    }

    @Override
    public int getActionColor() {
        return R.attr.colorAccent;
    }

    @Override
    public String getTitle(Context context) {
        return title;
    }

    @Override
    public void performAction(FeedItem item, Fragment fragment, FeedItemFilter filter) {
        String added = fragment.getString(R.string.added_to_playlist_message, title);
        String removed = fragment.getString(R.string.removed_from_named_playlist_message, title);
        List<Long> itemIds = Collections.singletonList(item.getId());
        Completable.fromAction(() -> {
            if (PlaylistDB.getPlaylistTitle(playlistId) == null) {
                return;
            }
            if (PlaylistDB.containsItem(playlistId, item.getId())) {
                PlaylistDB.removeItems(playlistId, itemIds).get();
                EventBus.getDefault().post(new MessageEvent(removed));
            } else if (item.hasMedia()) {
                PlaylistDB.addItems(playlistId, itemIds).get();
                EventBus.getDefault().post(new MessageEvent(added));
            }
        }).subscribeOn(Schedulers.io()).subscribe(() -> { },
                error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    @Override
    public boolean willRemove(FeedItemFilter filter, FeedItem item) {
        return false;
    }
}
