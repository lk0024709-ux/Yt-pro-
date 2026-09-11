package com.au.ytpro.data;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Local (on-device) likes backing double-tap-to-like in Shorts and the
 * Like button on the Watch screen. Enough metadata is stored per like that
 * the Library tab can list liked videos without re-fetching them.
 */
public final class LikesStore {

    private static final String PREFS = "likes";
    private static final String KEY_VIDEOS = "videos";
    private static final String SEP = "\u0001";

    private LikesStore() {
    }

    public static boolean isLiked(Context context, String videoId) {
        if (videoId == null || videoId.isEmpty()) {
            return false;
        }
        for (VideoItem item : likedVideos(context)) {
            if (videoId.equals(item.getVideoId())) {
                return true;
            }
        }
        return false;
    }

    /** Toggles the like state; returns the new state. */
    public static boolean toggle(Context context, @NonNull VideoItem item) {
        if (item.getVideoId().isEmpty()) {
            return false;
        }
        Set<String> entries = stored(context);
        boolean nowLiked = true;
        for (String entry : new HashSet<>(entries)) {
            VideoItem liked = decode(entry);
            if (liked != null && item.getVideoId().equals(liked.getVideoId())) {
                entries.remove(entry);
                nowLiked = false;
            }
        }
        if (nowLiked) {
            entries.add(encode(item));
        }
        prefs(context).edit().putStringSet(KEY_VIDEOS, entries).apply();
        return nowLiked;
    }

    @NonNull
    public static List<VideoItem> likedVideos(Context context) {
        List<VideoItem> out = new ArrayList<>();
        for (String entry : stored(context)) {
            VideoItem item = decode(entry);
            if (item != null && !item.getVideoId().isEmpty()) {
                out.add(item);
            }
        }
        return out;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Defensive copy: SharedPreferences returns its live set. */
    private static Set<String> stored(Context context) {
        Set<String> live = prefs(context).getStringSet(KEY_VIDEOS, null);
        return live == null ? new HashSet<String>() : new HashSet<>(live);
    }

    private static String encode(VideoItem item) {
        return item.getVideoId() + SEP
                + item.getTitle() + SEP
                + item.getChannelName() + SEP
                + item.getThumbnailUrl() + SEP
                + item.getUrl();
    }

    private static VideoItem decode(String entry) {
        if (entry == null) {
            return null;
        }
        String[] parts = entry.split(SEP, -1);
        if (parts.length < 1 || parts[0].isEmpty()) {
            return null;
        }
        String title = parts.length > 1 ? parts[1] : "";
        String channel = parts.length > 2 ? parts[2] : "";
        String thumb = parts.length > 3 ? parts[3] : "";
        String url = parts.length > 4 ? parts[4] : "";
        return new VideoItem(url, parts[0], title, channel, "",
                -1, -1, thumb, "", url.contains("/shorts/"), "");
    }
}
