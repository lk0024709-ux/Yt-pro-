package com.au.ytpro.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.widget.SearchView;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.au.ytpro.R;
import com.au.ytpro.WatchActivity;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.data.YouTubeRepository;

import java.util.List;

/**
 * Home tab: trending feed plus YouTube search in a native
 * {@link RecyclerView} of {@link androidx.cardview.widget.CardView} rows.
 */
public class HomeFragment extends Fragment {

    private VideoAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView emptyView;
    private SearchView searchView;
    private String lastQuery = "";

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_home, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        RecyclerView recyclerView = view.findViewById(R.id.recycler_view);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new VideoAdapter(R.layout.item_video_card, new VideoAdapter.Listener() {
            @Override
            public void onVideoClicked(VideoItem item) {
                WatchActivity.start(requireContext(), item);
            }
        });
        recyclerView.setAdapter(adapter);

        swipeRefresh = view.findViewById(R.id.swipe_refresh);
        progressBar = view.findViewById(R.id.progress_bar);
        emptyView = view.findViewById(R.id.empty_view);

        swipeRefresh.setColorSchemeResources(R.color.brand_red);
        swipeRefresh.setOnRefreshListener(new SwipeRefreshLayout.OnRefreshListener() {
            @Override
            public void onRefresh() {
                load();
            }
        });

        searchView = view.findViewById(R.id.search_view);
        searchView.setOnQueryTextListener(new SearchView.OnQueryTextListener() {
            @Override
            public boolean onQueryTextSubmit(String query) {
                lastQuery = query == null ? "" : query.trim();
                load();
                searchView.clearFocus();
                return true;
            }

            @Override
            public boolean onQueryTextChange(String newText) {
                return false;
            }
        });

        load();
    }

    private void load() {
        showLoading(true);
        YouTubeRepository.Callback<List<VideoItem>> callback =
                new YouTubeRepository.Callback<List<VideoItem>>() {
                    @Override
                    public void onSuccess(List<VideoItem> value) {
                        if (!isAdded()) {
                            return;
                        }
                        showLoading(false);
                        adapter.setItems(value);
                        emptyView.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
                        emptyView.setText(R.string.empty_feed);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (!isAdded()) {
                            return;
                        }
                        showLoading(false);
                        adapter.setItems(null);
                        emptyView.setVisibility(View.VISIBLE);
                        emptyView.setText(R.string.error_feed);
                    }
                };
        if (lastQuery.isEmpty()) {
            YouTubeRepository.trending(callback);
        } else {
            YouTubeRepository.search(lastQuery, callback);
        }
    }

    private void showLoading(boolean loading) {
        boolean hasContent = adapter != null && adapter.getItemCount() > 0;
        swipeRefresh.setRefreshing(loading && hasContent);
        progressBar.setVisibility(loading && !hasContent ? View.VISIBLE : View.GONE);
        if (loading) {
            emptyView.setVisibility(View.GONE);
        }
    }
}
