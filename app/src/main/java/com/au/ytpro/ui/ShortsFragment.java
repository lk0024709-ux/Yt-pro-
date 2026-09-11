package com.au.ytpro.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.widget.ViewPager2;

import com.au.ytpro.R;
import com.au.ytpro.data.VideoItem;
import com.au.ytpro.data.YouTubeRepository;

import java.util.List;

/**
 * Shorts tab: a vertical {@link ViewPager2} snapping video-by-video, each
 * page owning an integrated player with auto-play/pause on swipe.
 */
public class ShortsFragment extends Fragment {

    private ShortsAdapter adapter;
    private ViewPager2 pager;
    private ProgressBar progressBar;
    private TextView emptyView;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_shorts, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        pager = view.findViewById(R.id.shorts_pager);
        pager.setOrientation(ViewPager2.ORIENTATION_VERTICAL);
        adapter = new ShortsAdapter();
        pager.setAdapter(adapter);
        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                adapter.setActive(position);
            }
        });

        progressBar = view.findViewById(R.id.progress_bar);
        emptyView = view.findViewById(R.id.empty_view);

        load();
    }

    @Override
    public void onPause() {
        if (adapter != null) {
            adapter.pauseAll();
        }
        super.onPause();
    }

    @Override
    public void onResume() {
        super.onResume();
        if (adapter != null) {
            adapter.resumeActive();
        }
    }

    @Override
    public void onDestroyView() {
        if (adapter != null) {
            adapter.releaseAll();
        }
        super.onDestroyView();
    }

    private void load() {
        progressBar.setVisibility(View.VISIBLE);
        emptyView.setVisibility(View.GONE);
        YouTubeRepository.shortsFeed(new YouTubeRepository.Callback<List<VideoItem>>() {
            @Override
            public void onSuccess(List<VideoItem> value) {
                if (!isAdded()) {
                    return;
                }
                progressBar.setVisibility(View.GONE);
                adapter.setItems(value);
                emptyView.setVisibility(value.isEmpty() ? View.VISIBLE : View.GONE);
            }

            @Override
            public void onError(Exception e) {
                if (!isAdded()) {
                    return;
                }
                progressBar.setVisibility(View.GONE);
                adapter.setItems(null);
                emptyView.setVisibility(View.VISIBLE);
            }
        });
    }
}
