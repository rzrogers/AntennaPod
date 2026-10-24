package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.net.download.serviceinterface.AutoDownloadManager;
import de.danoeh.antennapod.storage.database.PlaylistDB;

public class PlaylistAutoDownloadTrigger {
    private static final String TAG = "PlaylistAutoDownload";
    private final Context context;

    private PlaylistAutoDownloadTrigger(Context context) {
        this.context = context.getApplicationContext();
    }

    public static void register(Context context) {
        EventBus.getDefault().register(new PlaylistAutoDownloadTrigger(context));
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onPlaylistEvent(PlaylistEvent event) {
        trigger();
    }

    @Subscribe(threadMode = ThreadMode.ASYNC)
    public void onFeedItemEvent(FeedItemEvent event) {
        if (event.unreadStatusChanged) {
            trigger();
        }
    }

    private void trigger() {
        try {
            if (PlaylistDB.hasAutoDownloadPlaylists()) {
                AutoDownloadManager.getInstance().autodownloadUndownloadedItems(context);
            }
        } catch (RuntimeException e) {
            Log.e(TAG, Log.getStackTraceString(e));
        }
    }
}
