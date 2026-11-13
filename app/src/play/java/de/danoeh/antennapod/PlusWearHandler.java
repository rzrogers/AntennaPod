package de.danoeh.antennapod;

import android.content.Context;
import android.content.Intent;
import android.util.Log;
import com.google.android.gms.wearable.Wearable;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear;
import de.danoeh.antennapod.playback.service.internal.AutoPlaylists;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.appstartintent.MainActivityStarter;
import de.danoeh.antennapod.ui.screen.playlists.PlaylistPlayer;

import java.util.ArrayList;
import java.util.List;

final class PlusWearHandler {
    private static final String TAG = "PlusWearHandler";

    private PlusWearHandler() {
    }

    static boolean handle(Context context, String path, String sourceNodeId) {
        if (!PlusWear.isPlusPath(path)) {
            return false;
        }
        if (PlusWear.PLAYLISTS.equals(path)) {
            List<PlusWear.PlaylistRow> rows = new ArrayList<>();
            for (AutoPlaylists.Row row : AutoPlaylists.describePlaylists(context)) {
                rows.add(new PlusWear.PlaylistRow(row.id, row.title, row.subtitle, row.playable));
            }
            reply(context, sourceNodeId, path, PlusWear.playlistsToBytes(rows));
        } else if (PlusWear.LOADED_PLAYLIST.equals(path)) {
            Playlist loaded = PlaylistDB.getLoadedPlaylist();
            reply(context, sourceNodeId, path, PlusWear.titleToBytes(loaded != null ? loaded.getTitle() : ""));
        } else if (PlusWear.JUMP_SECONDS.equals(path)) {
            reply(context, sourceNodeId, path, PlusWear.jumpSecondsToBytes(
                    UserPreferences.getRewindSecs(), UserPreferences.getFastForwardSecs()));
        } else if (PlusWear.OPEN_APP_ON_PHONE.equals(path)) {
            Intent intent = new MainActivityStarter(context).getIntent();
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(intent);
        } else if (path.startsWith(PlusWear.PLAY_PLAYLIST_PREFIX)) {
            try {
                long playlistId = Long.parseLong(path.substring(PlusWear.PLAY_PLAYLIST_PREFIX.length()));
                PlaylistPlayer.playInBackground(context, playlistId);
            } catch (NumberFormatException e) {
                Log.w(TAG, "Ignoring malformed play playlist path: " + path, e);
            }
        } else {
            Log.d(TAG, "Ignoring unknown path: " + path);
        }
        return true;
    }

    private static void reply(Context context, String nodeId, String path, byte[] payload) {
        Wearable.getMessageClient(context).sendMessage(nodeId, path, payload)
                .addOnFailureListener(e -> Log.e(TAG, "Failed to send reply to " + path, e));
    }
}
