package com.au.ytpro.ui;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.au.ytpro.BuildConfig;
import com.au.ytpro.R;
import com.au.ytpro.WatchActivity;
import com.au.ytpro.data.CacheManager;
import com.au.ytpro.data.LikesStore;
import com.au.ytpro.data.SubscriptionsStore;
import com.au.ytpro.data.VideoItem;

import java.util.List;

/**
 * Library tab: About (Developer / Credits), liked videos, subscription
 * summary, the Settings dialog and Clear App Cache (extractor + ExoPlayer
 * + image caches).
 */
public class LibraryFragment extends Fragment {

    private VideoAdapter likedAdapter;
    private TextView likedEmpty;
    private TextView subscriptionsSummary;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_library, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        TextView versionView = view.findViewById(R.id.library_version);
        versionView.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        likedEmpty = view.findViewById(R.id.liked_empty);
        subscriptionsSummary = view.findViewById(R.id.subscriptions_summary);

        RecyclerView likedList = view.findViewById(R.id.liked_list);
        likedList.setLayoutManager(new LinearLayoutManager(requireContext()));
        likedAdapter = new VideoAdapter(R.layout.item_video_card,
                new VideoAdapter.Listener() {
                    @Override
                    public void onVideoClicked(VideoItem item) {
                        WatchActivity.start(requireContext(), item);
                    }
                });
        likedList.setAdapter(likedAdapter);

        view.findViewById(R.id.button_settings).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        showSettingsDialog();
                    }
                });
        view.findViewById(R.id.button_clear_cache).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        clearCache();
                    }
                });
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        if (!isAdded()) {
            return;
        }
        List<VideoItem> liked = LikesStore.likedVideos(requireContext());
        likedAdapter.setItems(liked);
        likedEmpty.setVisibility(liked.isEmpty() ? View.VISIBLE : View.GONE);

        List<SubscriptionsStore.Channel> channels =
                SubscriptionsStore.all(requireContext());
        if (channels.isEmpty()) {
            subscriptionsSummary.setText(R.string.library_empty_subscriptions);
        } else {
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < channels.size() && i < 5; i++) {
                if (names.length() > 0) {
                    names.append(", ");
                }
                names.append(channels.get(i).name);
            }
            if (channels.size() > 5) {
                names.append(", …");
            }
            subscriptionsSummary.setText(names.toString());
        }
    }

    private void showSettingsDialog() {
        View content = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_settings, null);

        TextView versionView = content.findViewById(R.id.settings_version);
        versionView.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        TextView developerView = content.findViewById(R.id.settings_developer);
        developerView.setText(R.string.attribution_developer);

        TextView creditsView = content.findViewById(R.id.settings_credits);
        creditsView.setText(R.string.attribution_credits);

        final AlertDialog dialog = new AlertDialog.Builder(
                requireContext(), R.style.Theme_YTPro_Dialog)
                .setView(content)
                .create();

        content.findViewById(R.id.button_clear_cache).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                        clearCache();
                    }
                });
        content.findViewById(R.id.button_close).setOnClickListener(
                new View.OnClickListener() {
                    @Override
                    public void onClick(View v) {
                        dialog.dismiss();
                    }
                });

        dialog.show();
    }

    private void clearCache() {
        boolean cleared = CacheManager.clearAll(requireContext());
        Toast.makeText(requireContext(),
                cleared ? R.string.cache_cleared : R.string.cache_clear_failed,
                Toast.LENGTH_SHORT).show();
    }
}
