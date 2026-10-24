package de.danoeh.antennapod.ui.screen.playlists;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.os.Bundle;
import android.util.Log;
import android.view.ContextMenu;
import android.view.LayoutInflater;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SimpleItemAnimator;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.google.android.material.appbar.MaterialToolbar;

import org.greenrobot.eventbus.EventBus;
import org.greenrobot.eventbus.Subscribe;
import org.greenrobot.eventbus.ThreadMode;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import de.danoeh.antennapod.R;
import de.danoeh.antennapod.activity.MainActivity;
import de.danoeh.antennapod.event.EpisodeDownloadEvent;
import de.danoeh.antennapod.event.FeedItemEvent;
import de.danoeh.antennapod.event.FeedListUpdateEvent;
import de.danoeh.antennapod.event.MessageEvent;
import de.danoeh.antennapod.event.PlaylistEvent;
import de.danoeh.antennapod.event.playback.PlaybackPositionEvent;
import de.danoeh.antennapod.model.feed.FeedItem;
import de.danoeh.antennapod.model.feed.Playlist;
import de.danoeh.antennapod.model.feed.SortOrder;
import de.danoeh.antennapod.storage.database.FeedItemPermutors;
import de.danoeh.antennapod.storage.database.PlaylistDB;
import de.danoeh.antennapod.storage.preferences.UserPreferences;
import de.danoeh.antennapod.ui.MenuItemUtils;
import de.danoeh.antennapod.ui.common.Converter;
import de.danoeh.antennapod.ui.common.EmptyViewHandler;
import de.danoeh.antennapod.ui.common.LiftOnScrollListener;
import de.danoeh.antennapod.ui.common.ThemeUtils;
import de.danoeh.antennapod.ui.episodes.PlaybackSpeedUtils;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListAdapter;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemListRecyclerView;
import de.danoeh.antennapod.ui.episodeslist.EpisodeItemViewHolder;
import de.danoeh.antennapod.ui.episodeslist.FeedItemMenuHandler;
import de.danoeh.antennapod.ui.screen.feed.ItemSortDialog;
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers;
import io.reactivex.rxjava3.core.Completable;
import io.reactivex.rxjava3.core.Observable;
import io.reactivex.rxjava3.disposables.Disposable;
import io.reactivex.rxjava3.schedulers.Schedulers;
import it.xabaras.android.recyclerview.swipedecorator.RecyclerViewSwipeDecorator;

public class PlaylistFragment extends Fragment implements MaterialToolbar.OnMenuItemClickListener {
    public static final String TAG = "PlaylistFragment";
    private static final String ARG_PLAYLIST_ID = "playlist_id";
    private static final String ARG_PLAYLIST_TITLE = "playlist_title";

    private long playlistId;
    private String playlistTitle;
    private int autoDownload;
    private MaterialToolbar toolbar;
    private TextView infoBar;
    private EpisodeItemListRecyclerView recyclerView;
    private PlaylistItemsAdapter adapter;
    private EmptyViewHandler emptyView;
    private ProgressBar progressBar;
    private ItemTouchHelper itemTouchHelper;
    private List<FeedItem> items;
    private Disposable disposable;

    public static PlaylistFragment newInstance(long playlistId, String title) {
        PlaylistFragment fragment = new PlaylistFragment();
        Bundle args = new Bundle();
        args.putLong(ARG_PLAYLIST_ID, playlistId);
        args.putString(ARG_PLAYLIST_TITLE, title);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        playlistId = requireArguments().getLong(ARG_PLAYLIST_ID);
        playlistTitle = requireArguments().getString(ARG_PLAYLIST_TITLE);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        View root = inflater.inflate(R.layout.queue_fragment, container, false);
        toolbar = root.findViewById(R.id.toolbar);
        toolbar.setTitle(playlistTitle);
        toolbar.inflateMenu(R.menu.playlist);
        toolbar.setOnMenuItemClickListener(this);
        ((MainActivity) getActivity()).setupToolbarToggle(toolbar, true);

        progressBar = root.findViewById(R.id.progressBar);
        progressBar.setVisibility(View.VISIBLE);
        infoBar = root.findViewById(R.id.info_bar);
        int paddingHorizontal = (int) (getResources().getDisplayMetrics().density * 60);
        infoBar.setPadding(paddingHorizontal, 0, paddingHorizontal, 0);

        SwipeRefreshLayout swipeRefreshLayout = root.findViewById(R.id.swipeRefresh);
        swipeRefreshLayout.setEnabled(false);

        recyclerView = root.findViewById(R.id.recyclerView);
        RecyclerView.ItemAnimator animator = recyclerView.getItemAnimator();
        if (animator instanceof SimpleItemAnimator) {
            ((SimpleItemAnimator) animator).setSupportsChangeAnimations(false);
        }
        registerForContextMenu(recyclerView);
        recyclerView.addOnScrollListener(new LiftOnScrollListener(root.findViewById(R.id.appbar)));

        itemTouchHelper = new ItemTouchHelper(new PlaylistTouchCallback());
        itemTouchHelper.attachToRecyclerView(recyclerView);

        adapter = new PlaylistItemsAdapter(requireActivity());
        recyclerView.setAdapter(adapter);

        emptyView = new EmptyViewHandler(getContext());
        emptyView.attachToRecyclerView(recyclerView);
        emptyView.setIcon(R.drawable.ic_playlists);
        emptyView.setTitle(R.string.no_playlist_items_head_label);
        emptyView.setMessage(R.string.no_playlist_items_label);
        emptyView.updateAdapter(adapter);
        emptyView.hide();
        return root;
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
    public void onDestroyView() {
        super.onDestroyView();
        if (toolbar != null) {
            toolbar.setOnMenuItemClickListener(null);
        }
        adapter = null;
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        Playlist playlist = new Playlist(playlistId, playlistTitle, false, 0, 0);
        if (item.getItemId() == R.id.playlist_sort_item) {
            PlaylistSortDialog.newInstance(playlistId).show(getChildFragmentManager(), "SortDialog");
            return true;
        } else if (item.getItemId() == R.id.rename_playlist_item) {
            PlaylistDialogs.showRename(requireContext(), playlist);
            return true;
        } else if (item.getItemId() == R.id.delete_playlist_item) {
            PlaylistDialogs.showDelete(requireContext(), playlist, null);
            return true;
        } else if (item.getItemId() == R.id.playlist_auto_download_item) {
            PlaylistDialogs.showAutoDownload(requireContext(), playlistId, autoDownload);
            return true;
        }
        return false;
    }

    @Override
    public boolean onContextItemSelected(@NonNull MenuItem item) {
        if (!isVisible() || adapter == null) {
            return false;
        }
        FeedItem selectedItem = adapter.getLongPressedItem();
        if (selectedItem == null) {
            return super.onContextItemSelected(item);
        }
        return FeedItemMenuHandler.onMenuItemClicked(this, item.getItemId(), selectedItem);
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaylistEvent(PlaylistEvent event) {
        if (event.playlistId == playlistId) {
            loadItems();
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedItemEvent(FeedItemEvent event) {
        loadItems();
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onFeedListUpdateEvent(FeedListUpdateEvent event) {
        loadItems();
    }

    @Subscribe(sticky = true, threadMode = ThreadMode.MAIN)
    public void onEpisodeDownloadEvent(EpisodeDownloadEvent event) {
        if (items == null || adapter == null) {
            return;
        }
        for (String downloadUrl : event.getUrls()) {
            int pos = EpisodeDownloadEvent.indexOfItemWithDownloadUrl(items, downloadUrl);
            if (pos >= 0) {
                adapter.notifyItemChangedCompat(pos);
            }
        }
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    public void onPlaybackPositionEvent(PlaybackPositionEvent event) {
        if (adapter == null) {
            return;
        }
        for (int i = 0; i < adapter.getItemCount(); i++) {
            EpisodeItemViewHolder holder = (EpisodeItemViewHolder) recyclerView.findViewHolderForAdapterPosition(i);
            if (holder != null && holder.isPlayingItem()) {
                holder.notifyPlaybackPositionUpdated(event);
                break;
            }
        }
    }

    private void loadItems() {
        if (disposable != null) {
            disposable.dispose();
        }
        disposable = Observable.fromCallable(() -> {
            Playlist current = null;
            for (Playlist playlist : PlaylistDB.getPlaylists()) {
                if (playlist.getId() == playlistId) {
                    current = playlist;
                }
            }
            return new PlaylistContent(current, PlaylistDB.getItems(playlistId));
        })
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(content -> {
                    if (adapter == null || !isAdded()) {
                        return;
                    }
                    if (content.playlist == null) {
                        getParentFragmentManager().popBackStack();
                        return;
                    }
                    playlistTitle = content.playlist.getTitle();
                    autoDownload = content.playlist.getAutoDownload();
                    toolbar.setTitle(playlistTitle);
                    progressBar.setVisibility(View.GONE);
                    items = content.items;
                    adapter.updateItems(items);
                    refreshInfoBar();
                }, error -> Log.e(TAG, Log.getStackTraceString(error)));
    }

    private void refreshInfoBar() {
        long timeLeft = 0;
        for (FeedItem item : items) {
            float playbackSpeed = 1;
            if (UserPreferences.timeRespectsSpeed()) {
                playbackSpeed = PlaybackSpeedUtils.getCurrentPlaybackSpeed(item.getMedia());
            }
            if (item.getMedia() != null) {
                long itemTimeLeft = item.getMedia().getDuration() - item.getMedia().getPosition();
                timeLeft += (long) (itemTimeLeft / playbackSpeed);
            }
        }
        String episodes = getResources().getQuantityString(R.plurals.num_episodes, items.size(), items.size());
        String time = Converter.getDurationStringLocalized(getResources(), timeLeft, false);
        infoBar.setText(getString(R.string.queue_time_left_label, episodes, time));
    }

    private static List<Long> idsOf(List<FeedItem> list) {
        List<Long> ids = new ArrayList<>();
        for (FeedItem item : list) {
            ids.add(item.getId());
        }
        return ids;
    }

    private static class PlaylistContent {
        final Playlist playlist;
        final List<FeedItem> items;

        PlaylistContent(Playlist playlist, List<FeedItem> items) {
            this.playlist = playlist;
            this.items = items;
        }
    }

    private class PlaylistItemsAdapter extends EpisodeItemListAdapter {

        PlaylistItemsAdapter(FragmentActivity activity) {
            super(activity);
        }

        @Override
        @SuppressLint("ClickableViewAccessibility")
        protected void afterBindViewHolder(EpisodeItemViewHolder holder, int pos) {
            holder.dragHandle.setVisibility(View.VISIBLE);
            holder.dragHandle.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    itemTouchHelper.startDrag(holder);
                }
                return false;
            });
            holder.coverHolder.setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    boolean isLtr = holder.itemView.getLayoutDirection() == View.LAYOUT_DIRECTION_LTR;
                    float factor = isLtr ? 1 : -1;
                    if (factor * event.getX() < factor * 0.5 * v.getWidth()) {
                        itemTouchHelper.startDrag(holder);
                    }
                }
                return false;
            });
        }

        @Override
        public void onCreateContextMenu(ContextMenu menu, View v, ContextMenu.ContextMenuInfo menuInfo) {
            super.onCreateContextMenu(menu, v, menuInfo);
            MenuItemUtils.setOnClickListeners(menu, PlaylistFragment.this::onContextItemSelected);
        }
    }

    private class PlaylistTouchCallback extends ItemTouchHelper.SimpleCallback {
        private int dragFrom = -1;
        private int dragTo = -1;

        PlaylistTouchCallback() {
            super(ItemTouchHelper.UP | ItemTouchHelper.DOWN, ItemTouchHelper.LEFT | ItemTouchHelper.RIGHT);
        }

        @Override
        public boolean isLongPressDragEnabled() {
            return false;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder,
                              @NonNull RecyclerView.ViewHolder target) {
            int from = viewHolder.getBindingAdapterPosition();
            int to = target.getBindingAdapterPosition();
            if (items == null || from < 0 || to < 0 || from >= items.size() || to >= items.size()) {
                return false;
            }
            if (dragFrom == -1) {
                dragFrom = from;
            }
            dragTo = to;
            items.add(to, items.remove(from));
            adapter.notifyItemMoved(from, to);
            return true;
        }

        @Override
        public void clearView(@NonNull RecyclerView recyclerView, @NonNull RecyclerView.ViewHolder viewHolder) {
            super.clearView(recyclerView, viewHolder);
            if (dragFrom != -1 && dragTo != -1 && dragFrom != dragTo && items != null) {
                PlaylistDB.setOrder(playlistId, idsOf(items));
            }
            dragFrom = -1;
            dragTo = -1;
        }

        @Override
        public void onSwiped(@NonNull RecyclerView.ViewHolder viewHolder, int direction) {
            int position = viewHolder.getBindingAdapterPosition();
            if (items == null || position < 0 || position >= items.size()) {
                return;
            }
            if (disposable != null) {
                disposable.dispose();
            }
            List<Long> previousOrder = idsOf(items);
            FeedItem item = items.remove(position);
            adapter.notifyItemRemoved(position);
            refreshInfoBar();
            List<Long> removed = Collections.singletonList(item.getId());
            PlaylistDB.removeItems(playlistId, removed);
            long id = playlistId;
            EventBus.getDefault().post(new MessageEvent(getString(R.string.removed_from_playlist_message),
                    context -> Completable.fromAction(() -> {
                        PlaylistDB.addItems(id, removed).get();
                        PlaylistDB.setOrder(id, previousOrder);
                    }).subscribeOn(Schedulers.io()).subscribe(() -> { },
                            error -> Log.e(TAG, Log.getStackTraceString(error))),
                    getString(R.string.undo)));
        }

        @Override
        public void onChildDraw(@NonNull Canvas c, @NonNull RecyclerView recyclerView,
                                @NonNull RecyclerView.ViewHolder viewHolder,
                                float dx, float dy, int actionState, boolean isCurrentlyActive) {
            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                Context context = requireContext();
                int actionColor = ThemeUtils.getColorFromAttr(context, R.attr.colorAccent);
                int background = ThemeUtils.getColorFromAttr(context, R.attr.background_elevated);
                new RecyclerViewSwipeDecorator.Builder(c, recyclerView, viewHolder, dx, dy,
                        actionState, isCurrentlyActive)
                        .addSwipeRightActionIcon(R.drawable.ic_playlist_remove)
                        .addSwipeLeftActionIcon(R.drawable.ic_playlist_remove)
                        .addSwipeRightBackgroundColor(background)
                        .addSwipeLeftBackgroundColor(background)
                        .setActionIconTint(actionColor)
                        .create()
                        .decorate();
            }
            super.onChildDraw(c, recyclerView, viewHolder, dx, dy, actionState, isCurrentlyActive);
        }
    }

    public static class PlaylistSortDialog extends ItemSortDialog {
        private static final String ARG_ID = "playlist_id";

        static PlaylistSortDialog newInstance(long playlistId) {
            PlaylistSortDialog dialog = new PlaylistSortDialog();
            Bundle args = new Bundle();
            args.putLong(ARG_ID, playlistId);
            dialog.setArguments(args);
            return dialog;
        }

        @Override
        protected void onAddItem(int title, SortOrder ascending, SortOrder descending, boolean ascendingIsDefault) {
            if (ascending == SortOrder.EPISODE_FILENAME_A_Z || ascending == SortOrder.SIZE_SMALL_LARGE) {
                return;
            }
            if (ascending == SortOrder.DATE_OLD_NEW || ascending == SortOrder.SMART_SHUFFLE_OLD_NEW) {
                ascendingIsDefault = true;
            }
            super.onAddItem(title, ascending, descending, ascendingIsDefault);
        }

        @Override
        protected void onSelectionChanged() {
            super.onSelectionChanged();
            SortOrder order = sortOrder;
            if (order == null) {
                return;
            }
            long playlistId = requireArguments().getLong(ARG_ID);
            Completable.fromAction(() -> {
                List<FeedItem> playlistItems = PlaylistDB.getItems(playlistId);
                FeedItemPermutors.getPermutor(order).reorder(playlistItems);
                PlaylistDB.setOrder(playlistId, idsOf(playlistItems));
            }).subscribeOn(Schedulers.io()).subscribe(() -> { },
                    error -> Log.e(TAG, Log.getStackTraceString(error)));
        }
    }
}
