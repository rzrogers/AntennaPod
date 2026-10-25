package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;
import android.view.LayoutInflater;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import java.util.ArrayList;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.FeedItemPermutors;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.ui.common.databinding.EditTextDialogBinding;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class PlaylistDialogs {
    private static final String TAG = "PlaylistDialogs";

    private PlaylistDialogs() {
    }

    public static boolean onMenuItemClick(Context context, Playlist playlist, int itemId, Runnable onDeleted) {
        if (itemId == R.id.playlist_sort_item) {
            showSort(context, playlist.getId());
        } else if (itemId == R.id.playlist_add_location_item) {
            showAddLocation(context, playlist.getId(), playlist.getAddLocation());
        } else if (itemId == R.id.playlist_auto_download_item) {
            showAutoDownload(context, playlist.getId(), playlist.getAutoDownload());
        } else if (itemId == R.id.playlist_add_to_home_screen_item) {
            PlaylistPlayer.requestShortcut(context, playlist);
        } else if (itemId == R.id.rename_playlist_item) {
            showRename(context, playlist);
        } else if (itemId == R.id.delete_playlist_item) {
            showDelete(context, playlist, onDeleted);
        } else {
            return false;
        }
        return true;
    }

    public static void showAddLocation(Context context, long playlistId, int current) {
        int[] values = {PlaylistDB.ADD_LOCATION_BACK, PlaylistDB.ADD_LOCATION_FRONT, PlaylistDB.ADD_LOCATION_RANDOM};
        String[] names = {
                context.getString(R.string.playlist_add_location_back),
                context.getString(R.string.playlist_add_location_front),
                context.getString(R.string.playlist_add_location_random)
        };
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.playlist_add_location_label)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    PlaylistDB.setAddLocation(playlistId, values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    public static void showSort(Context context, long playlistId) {
        SortOrder[] orders = {
                SortOrder.DATE_NEW_OLD, SortOrder.DATE_OLD_NEW,
                SortOrder.EPISODE_TITLE_A_Z, SortOrder.EPISODE_TITLE_Z_A,
                SortOrder.FEED_TITLE_A_Z, SortOrder.FEED_TITLE_Z_A,
                SortOrder.DURATION_SHORT_LONG, SortOrder.DURATION_LONG_SHORT,
                SortOrder.RANDOM, SortOrder.SMART_SHUFFLE_OLD_NEW, SortOrder.SMART_SHUFFLE_NEW_OLD
        };
        int[] titles = {
                R.string.playlist_sort_date_new_old, R.string.playlist_sort_date_old_new,
                R.string.playlist_sort_title_a_z, R.string.playlist_sort_title_z_a,
                R.string.playlist_sort_feed_a_z, R.string.playlist_sort_feed_z_a,
                R.string.playlist_sort_duration_short_long, R.string.playlist_sort_duration_long_short,
                R.string.random, R.string.playlist_sort_smart_shuffle_old_new,
                R.string.playlist_sort_smart_shuffle_new_old
        };
        String[] names = new String[titles.length];
        for (int i = 0; i < titles.length; i++) {
            names[i] = context.getString(titles[i]);
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.sort)
                .setSingleChoiceItems(names, -1, (dialog, which) -> {
                    SortOrder order = orders[which];
                    dialog.dismiss();
                    Completable.fromAction(() -> {
                        List<FeedItem> items = PlaylistDB.getItems(playlistId);
                        FeedItemPermutors.getPermutor(order).reorder(items);
                        List<Long> ids = new ArrayList<>();
                        for (FeedItem item : items) {
                            ids.add(item.getId());
                        }
                        PlaylistDB.setOrder(playlistId, ids);
                    }).subscribeOn(Schedulers.io()).subscribe(() -> { },
                            error -> Log.e(TAG, Log.getStackTraceString(error)));
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    public static void showCreate(Context context, List<Long> itemIds) {
        EditTextDialogBinding binding = EditTextDialogBinding.inflate(LayoutInflater.from(context));
        binding.textInputLayout.setHint(R.string.playlist_name_hint);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.new_playlist_title)
                .setView(binding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String title = binding.textInput.getText() == null
                            ? "" : binding.textInput.getText().toString().trim();
                    if (!title.isEmpty()) {
                        PlaylistDB.createPlaylist(title, itemIds);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
        binding.textInput.requestFocus();
    }

    public static void showSaveQueue(Context context) {
        EditTextDialogBinding binding = EditTextDialogBinding.inflate(LayoutInflater.from(context));
        binding.textInputLayout.setHint(R.string.playlist_name_hint);
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.new_playlist_title)
                .setView(binding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String title = binding.textInput.getText() == null
                            ? "" : binding.textInput.getText().toString().trim();
                    if (!title.isEmpty()) {
                        PlaylistDB.saveQueueAsPlaylist(title);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
        binding.textInput.requestFocus();
    }

    public static void showRename(Context context, Playlist playlist) {
        EditTextDialogBinding binding = EditTextDialogBinding.inflate(LayoutInflater.from(context));
        binding.textInputLayout.setHint(R.string.playlist_name_hint);
        binding.textInput.setText(playlist.getTitle());
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.rename_playlist_label)
                .setView(binding.getRoot())
                .setPositiveButton(android.R.string.ok, (dialog, which) -> {
                    String title = binding.textInput.getText() == null
                            ? "" : binding.textInput.getText().toString().trim();
                    if (!title.isEmpty()) {
                        PlaylistDB.renamePlaylist(playlist.getId(), title);
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    public static void showAutoDownload(Context context, long playlistId, int current) {
        int[] values = {0, 1, 2, 3, 5, 10, -1};
        String[] names = new String[values.length];
        int checked = 0;
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) {
                names[i] = context.getString(R.string.playlist_auto_download_off);
            } else if (values[i] < 0) {
                names[i] = context.getString(R.string.playlist_auto_download_all);
            } else {
                names[i] = context.getResources().getQuantityString(
                        R.plurals.playlist_auto_download_next, values[i], values[i]);
            }
            if (values[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.playlist_auto_download_label)
                .setSingleChoiceItems(names, checked, (dialog, which) -> {
                    PlaylistDB.setAutoDownload(playlistId, values[which]);
                    dialog.dismiss();
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }

    public static void showDelete(Context context, Playlist playlist, Runnable onDeleted) {
        new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.delete_playlist_label)
                .setMessage(context.getString(R.string.delete_playlist_confirmation_msg, playlist.getTitle()))
                .setPositiveButton(R.string.delete_label, (dialog, which) -> {
                    PlaylistDB.deletePlaylist(playlist.getId());
                    if (onDeleted != null) {
                        onDeleted.run();
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();
    }
}
