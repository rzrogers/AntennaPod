package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.storage.database.DBWriter;
import de.danoeh.antennapod.storage.preferences.UserPreferences;

public class PlaylistQueueSync {
    private final Context context;

    private PlaylistQueueSync(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void register(Context context) {
        EventBus.getDefault().register(new PlaylistQueueSync(context));
        DelayedDeletionWorker.enqueue(context);
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onFeedItemEvent(FeedItemEvent event) {
        if (!event.unreadStatusChanged || !UserPreferences.isPlusPlaylistLoaded()) {
            return;
        }
        List<Long> finished = new ArrayList<>();
        for (FeedItem item : event.items) {
            if (item.isPlayed()) {
                finished.add(item.getId());
            }
        }
        if (finished.isEmpty()) {
            return;
        }
        long[] ids = new long[finished.size()];
        for (int i = 0; i < ids.length; i++) {
            ids[i] = finished.get(i);
        }
        DBWriter.removeQueueItem(context, false, ids);
    }
}
