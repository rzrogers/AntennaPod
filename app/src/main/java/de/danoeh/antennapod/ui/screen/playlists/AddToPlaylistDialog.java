package de.danoeh.antennapod.ui.screen.playlists;

import android.content.Context;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.util.Pair;
import androidx.core.widget.NestedScrollView;

import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import org.greenrobot.eventbus.EventBus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import androidx.appcompat.app.AlertDialog;
import de.danoeh.antennapod.R;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public final class AddToPlaylistDialog {
    private static final String TAG = "AddToPlaylistDialog";

    private AddToPlaylistDialog() {
    }

    public static void show(Context context, List<FeedItem> items) {
        List<Long> itemIds = new ArrayList<>();
        for (FeedItem item : items) {
            if (!item.isPlayed()) {
                itemIds.add(item.getId());
            }
        }
        if (itemIds.isEmpty()) {
            EventBus.getDefault().post(new MessageEvent(
                    context.getString(R.string.playlist_played_episodes_skipped)));
            return;
        }
        boolean skippedPlayed = itemIds.size() < items.size();
        Observable.fromCallable(() -> new Pair<>(PlaylistDB.getPlaylists(), PlaylistDB.getMembershipCounts(itemIds)))
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(result -> showDialog(context, itemIds, result.first, result.second, skippedPlayed),
                        error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private static void showDialog(Context context, List<Long> itemIds, List<Playlist> playlists,
                                   Map<Long, Integer> counts, boolean skippedPlayed) {
        float density = context.getResources().getDisplayMetrics().density;
        int padding = (int) (24 * density);
        LinearLayout list = new LinearLayout(context);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(padding - (int) (8 * density), (int) (8 * density), padding, 0);

        TextView newPlaylist = new TextView(context);
        newPlaylist.setText(R.string.new_playlist_label);
        newPlaylist.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        newPlaylist.setTextColor(ThemeUtils.getColorFromAttr(context, android.R.attr.textColorPrimary));
        newPlaylist.setGravity(Gravity.CENTER_VERTICAL);
        newPlaylist.setMinHeight((int) (48 * density));
        newPlaylist.setPadding((int) (8 * density), 0, 0, 0);
        newPlaylist.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_add, 0, 0, 0);
        newPlaylist.setCompoundDrawablePadding((int) (24 * density));
        TypedValue outValue = new TypedValue();
        context.getTheme().resolveAttribute(android.R.attr.selectableItemBackground, outValue, true);
        newPlaylist.setBackgroundResource(outValue.resourceId);
        list.addView(newPlaylist);

        List<MaterialCheckBox> checkBoxes = new ArrayList<>();
        List<Boolean> wasChecked = new ArrayList<>();
        for (Playlist playlist : playlists) {
            Integer count = counts.get(playlist.getId());
            boolean checked = count != null && count >= itemIds.size();
            MaterialCheckBox checkBox = new MaterialCheckBox(context);
            checkBox.setText(playlist.getTitle());
            checkBox.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
            checkBox.setMinHeight((int) (48 * density));
            checkBox.setChecked(checked);
            if (!checked && count != null && count > 0) {
                checkBox.setCheckedState(MaterialCheckBox.STATE_INDETERMINATE);
            }
            list.addView(checkBox);
            checkBoxes.add(checkBox);
            wasChecked.add(checked);
        }

        NestedScrollView scrollView = new NestedScrollView(context);
        scrollView.addView(list);

        AlertDialog dialog = new MaterialAlertDialogBuilder(context)
                .setTitle(R.string.add_to_playlist_label)
                .setView(scrollView)
                .setPositiveButton(android.R.string.ok, (d, which) -> {
                    boolean changed = false;
                    for (int i = 0; i < playlists.size(); i++) {
                        int state = checkBoxes.get(i).getCheckedState();
                        long playlistId = playlists.get(i).getId();
                        if (state == MaterialCheckBox.STATE_CHECKED && !wasChecked.get(i)) {
                            PlaylistDB.addItems(playlistId, itemIds);
                            changed = true;
                        } else if (state == MaterialCheckBox.STATE_UNCHECKED
                                && (wasChecked.get(i) || counts.containsKey(playlistId))) {
                            PlaylistDB.removeItems(playlistId, itemIds);
                            changed = true;
                        }
                    }
                    if (skippedPlayed) {
                        EventBus.getDefault().post(new MessageEvent(
                                context.getString(R.string.playlist_played_episodes_skipped)));
                    } else if (changed) {
                        EventBus.getDefault().post(new MessageEvent(
                                context.getString(R.string.playlists_updated_message)));
                    }
                })
                .setNegativeButton(R.string.cancel_label, null)
                .show();

        newPlaylist.setOnClickListener(v -> {
            dialog.dismiss();
            PlaylistDialogs.showCreate(context, itemIds);
        });
    }
}
