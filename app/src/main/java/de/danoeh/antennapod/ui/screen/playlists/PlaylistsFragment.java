package de.danoeh.antennapod.ui.screen.playlists;

import android.os.Bundle;
import android.text.Layout;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.appbar.MaterialToolbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.ui.common.EmptyViewHandler;
import de.danoeh.antennapod.ui.common.LiftOnScrollListener;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;

public class PlaylistsFragment extends Fragment implements MaterialToolbar.OnMenuItemClickListener {
    public static final String TAG = "PlaylistsFragment";
    private static final String KEY_UP_ARROW = "up_arrow";

    private PlaylistListAdapter adapter;
    private EmptyViewHandler emptyView;
    private ProgressBar progressBar;
    private Disposable disposable;
    private boolean displayUpArrow;
    private final ActivityResultLauncher<String> exportLauncher = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/json"), uri -> {
                if (uri != null) {
                    PlaylistBackup.exportTo(requireContext(), uri);
                }
            });
    private final ActivityResultLauncher<String[]> importLauncher = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> {
                if (uri != null) {
                    PlaylistBackup.importFrom(requireActivity(), uri);
                }
            });

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.playlists_fragment, container, false);
        MaterialToolbar toolbar = root.findViewById(R.id.toolbar);
        toolbar.inflateMenu(R.menu.playlists);
        toolbar.setOnMenuItemClickListener(this);
        displayUpArrow = getParentFragmentManager().getBackStackEntryCount() != 0;
        if (savedInstanceState != null) {
            displayUpArrow = savedInstanceState.getBoolean(KEY_UP_ARROW);
        }
        ((MainActivity) getActivity()).setupToolbarToggle(toolbar, displayUpArrow);

        progressBar = root.findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);

        adapter = new PlaylistListAdapter();
        RecyclerView recyclerView = root.findViewById(R.id.recyclerView);
        recyclerView.setLayoutManager(new LinearLayoutManager(getContext()));
        recyclerView.setAdapter(adapter);
        recyclerView.addOnScrollListener(new LiftOnScrollListener(root.findViewById(R.id.appbar)));

        emptyView = new EmptyViewHandler(getContext());
        emptyView.attachToRecyclerView(recyclerView);
        emptyView.setIcon(R.drawable.ic_playlists);
        emptyView.setTitle(R.string.no_playlists_head_label);
        emptyView.setMessage(R.string.no_playlists_label);
        emptyView.updateAdapter(adapter);
        emptyView.hide();
        return root;
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        outState.putBoolean(KEY_UP_ARROW, displayUpArrow);
        super.onSaveInstanceState(outState);
    }

    @Override
    public void onStart() {
        super.onStart();
        EventBus.getDefault().register(this);
        loadItems();
    }

    @Override
    public void onStop() {
        super.onStop();
        EventBus.getDefault().unregister(this);
        if (disposable != null) {
            disposable.dispose();
        }
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        if (item.getItemId() == R.id.new_playlist_item) {
            PlaylistDialogs.showCreate(requireContext(), null);
            return true;
        } else if (item.getItemId() == R.id.export_playlists_item) {
            exportLauncher.launch(PlaylistBackup.FILE_NAME);
            return true;
        } else if (item.getItemId() == R.id.import_playlists_item) {
            importLauncher.launch(new String[]{"*/*"});
            return true;
        }
        return false;
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaylistEvent(PlaylistEvent event) {
        loadItems();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedItemEvent(FeedItemEvent event) {
        loadItems();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListUpdateEvent(FeedListUpdateEvent event) {
        loadItems();
    }

    private void loadItems() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(PlaylistDB::getPlaylists)
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(playlists -> {
                    progressBar.setVisibility(View.GONE);
                    adapter.setPlaylists(playlists);
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void showPlaylistMenu(View anchor, Playlist playlist) {
        PopupMenu popup = new PopupMenu(requireContext(), anchor);
        popup.inflate(R.menu.playlist);
        popup.getMenu().findItem(R.id.playlist_sort_item).setVisible(false);
        popup.setOnMenuItemClickListener(menuItem ->
                PlaylistDialogs.onMenuItemClick(requireContext(), playlist, menuItem.getItemId(), null));
        popup.show();
    }

    private class PlaylistListAdapter extends RecyclerView.Adapter<PlaylistViewHolder> {
        private List<Playlist> playlists = new ArrayList<>();

        void setPlaylists(List<Playlist> playlists) {
            this.playlists = playlists;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public PlaylistViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new PlaylistViewHolder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.playlist_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull PlaylistViewHolder holder, int position) {
            Playlist playlist = playlists.get(position);
            holder.title.setText(playlist.getTitle());
            holder.autoDownloadIcon.setVisibility(playlist.getAutoDownload() != 0 ? View.VISIBLE : View.GONE);
            holder.speedIcon.setVisibility(playlist.hasSpeedTiers() ? View.VISIBLE : View.GONE);
            String count = getResources().getQuantityString(R.plurals.num_episodes,
                    playlist.getEpisodeCount(), playlist.getEpisodeCount()).replace(' ', '\u00A0');
            if (playlist.isLoaded()) {
                String full = getString(R.string.playlist_loaded_info,
                        PlaylistSpeedDialog.withSpeed(requireContext(), playlist.getId(), count));
                holder.count.setText(full);
                holder.count.post(() -> {
                    Layout layout = holder.count.getLayout();
                    int countStart = full.lastIndexOf(count);
                    if (layout != null && layout.getLineCount() > 1 && countStart > 0
                            && full.equals(holder.count.getText().toString())) {
                        String head = full.substring(0, countStart).replaceAll("[\\s\u00A0\u00B7\u2022]+$", "");
                        holder.count.setText(head + "\n" + count);
                    }
                });
            } else {
                holder.count.setText(count);
            }
            holder.itemView.setOnClickListener(v -> ((MainActivity) requireActivity())
                    .loadChildFragment(PlaylistFragment.newInstance(playlist.getId(), playlist.getTitle())));
            holder.itemView.setOnLongClickListener(v -> {
                showPlaylistMenu(v, playlist);
                return true;
            });
        }

        @Override
        public int getItemCount() {
            return playlists.size();
        }
    }

    private static class PlaylistViewHolder extends RecyclerView.ViewHolder {
        final TextView title;
        final TextView count;
        final ImageView autoDownloadIcon;
        final ImageView speedIcon;

        PlaylistViewHolder(View itemView) {
            super(itemView);
            title = itemView.findViewById(R.id.title);
            count = itemView.findViewById(R.id.count);
            autoDownloadIcon = itemView.findViewById(R.id.auto_download_icon);
            speedIcon = itemView.findViewById(R.id.speed_icon);
        }
    }
}
