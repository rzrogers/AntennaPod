package de.danoeh.antennapod.ui.swipeactions;

import android.content.Context;

import androidx.fragment.app.Fragment;

import java.util.Collections;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.ui.screen.playlists.AddToPlaylistDialog;

public class AddToPlaylistSwipeAction implements SwipeAction {

    @Override
    public String getId() {
        return ADD_TO_PLAYLIST;
    }

    @Override
    public int getActionIcon() {
        return R.drawable.ic_playlist_add;
    }

    @Override
    public int getActionColor() {
        return R.attr.colorAccent;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.add_to_playlist_label);
    }

    @Override
    public void performAction(FeedItem item, Fragment fragment, FeedItemFilter filter) {
        AddToPlaylistDialog.show(fragment.requireContext(), Collections.singletonList(item));
    }

    @Override
    public boolean willRemove(FeedItemFilter filter, FeedItem item) {
        return false;
    }
}
