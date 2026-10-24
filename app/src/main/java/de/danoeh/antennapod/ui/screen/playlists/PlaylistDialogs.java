package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.view.LayoutInflater;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.ui.common.databinding.EditTextDialogBinding;

public final class PlaylistDialogs {
    private PlaylistDialogs() {
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
