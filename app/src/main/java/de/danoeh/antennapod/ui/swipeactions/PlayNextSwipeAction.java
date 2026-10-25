package de.danoeh.antennapod.ui.swipeactions;

import android.content.Context;

import androidx.fragment.app.Fragment;

import org.greenrobot.eventbus.EventBus;

import java.util.Collections;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.FeedItemFilter;
import de.danoeh.antennapod.storage.database.PlaylistDB;

public class PlayNextSwipeAction implements SwipeAction {

    @Override
    public String getId() {
        return PLAY_NEXT;
    }

    @Override
    public int getActionIcon() {
        return R.drawable.ic_playlist_play;
    }

    @Override
    public int getActionColor() {
        return R.attr.colorAccent;
    }

    @Override
    public String getTitle(Context context) {
        return context.getString(R.string.play_next_label);
    }

    @Override
    public void performAction(FeedItem item, Fragment fragment, FeedItemFilter filter) {
        if (!item.hasMedia()) {
            return;
        }
        PlaylistDB.playNext(Collections.singletonList(item.getId()));
        EventBus.getDefault().post(new MessageEvent(fragment.getString(R.string.play_next_message)));
    }

    @Override
    public boolean willRemove(FeedItemFilter filter, FeedItem item) {
        return false;
    }
}
