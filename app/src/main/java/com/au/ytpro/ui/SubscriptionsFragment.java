package com.au.ytpro.ui;

import android.content.DialogInterface;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.au.ytpro.R;
import com.au.ytpro.WatchActivity;
import com.au.ytpro.data.SubscriptionsStore;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.data.YouTubeRepository;

import java.util.List;

/**
 * Subscriptions tab: merged latest uploads of every locally-subscribed
 * channel. Long-press a card to unsubscribe from its channel.
 */
public class SubscriptionsFragment extends Fragment {

    private static final int VIDEOS_PER_CHANNEL = 6;

    private VideoAdapter adapter;
    private SwipeRefreshLayout swipeRefresh;
    private ProgressBar progressBar;
    private TextView emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_subscriptions, container, false);
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
        adapter.setLongListener(new VideoAdapter.LongListener() {
            @Override
            public void onVideoLongClicked(VideoItem item) {
                confirmUnsubscribe(item);
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
    }

    @Override
    public void onResume() {
        super.onResume();
        load();
    }

    private void load() {
        if (!isAdded()) {
            return;
        }
        List<SubscriptionsStore.Channel> channels =
                SubscriptionsStore.all(requireContext());
        if (channels.isEmpty()) {
            swipeRefresh.setRefreshing(false);
            progressBar.setVisibility(View.GONE);
            adapter.setItems(null);
            emptyView.setVisibility(View.VISIBLE);
            return;
        }
        showLoading(true);
        YouTubeRepository.subscriptionsFeed(channels, VIDEOS_PER_CHANNEL,
                new YouTubeRepository.Callback<List<VideoItem>>() {
                    @Override
                    public void onSuccess(List<VideoItem> value) {
                        if (!isAdded()) {
                            return;
                        }
                        showLoading(false);
                        adapter.setItems(value);
                        emptyView.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onError(Exception e) {
                        if (!isAdded()) {
                            return;
                        }
                        showLoading(false);
                        adapter.setItems(null);
                        emptyView.setVisibility(View.VISIBLE);
                    }
                });
    }

    private void showLoading(boolean loading) {
        boolean hasContent = adapter != null && adapter.getItemCount() > 0;
        swipeRefresh.setRefreshing(loading && hasContent);
        progressBar.setVisibility(loading && !hasContent ? View.VISIBLE : View.GONE);
        if (loading) {
            emptyView.setVisibility(View.GONE);
        }
    }

    private void confirmUnsubscribe(final VideoItem item) {
        if (!isAdded() || item.getChannelUrl().isEmpty()) {
            return;
        }
        String channel = item.getChannelName().isEmpty()
                ? item.getChannelUrl() : item.getChannelName();
        new AlertDialog.Builder(requireContext(), R.style.Theme_YTPro_Dialog)
                .setMessage(getString(R.string.unsubscribe_confirm, channel))
                .setPositiveButton(android.R.string.ok,
                        new DialogInterface.OnClickListener() {
                            @Override
                            public void onClick(DialogInterface dialog, int which) {
                                SubscriptionsStore.unsubscribe(
                                        requireContext(), item.getChannelUrl());
                                load();
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
