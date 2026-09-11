package com.au.ytpro.util;

import androidx.annotation.NonNull;

import com.au.ytpro.data.VideoItem;

import org.schabi.newpipe.extractor.Image;

import java.util.List;
import java.util.Locale;

/**
 * Small formatting helpers shared by every feed: thumbnails, view counts,
 * durations and the "channel • views • age" meta line.
 */
public final class FormatUtils {

    private FormatUtils() {
    }

    /** Picks the highest-resolution image URL from an extractor list. */
    @NonNull
    public static String bestImageUrl(List<Image> images) {
        if (images == null || images.isEmpty()) {
            return "";
        }
        Image best = null;
        long bestScore = -1;
        for (int i = 0; i < images.size(); i++) {
            Image image = images.get(i);
            if (image == null || image.getUrl() == null || image.getUrl().isEmpty()) {
                continue;
            }
            int width = image.getWidth();
            int height = image.getHeight();
            // Unknown dimensions sort by position (later = larger on YouTube).
            long score = width > 0 && height > 0 ? (long) width * height : i;
            if (score >= bestScore) {
                bestScore = score;
                best = image;
            }
        }
        return best == null ? "" : best.getUrl();
    }

    /** 1234 -> "1.2K views"; negative (unknown) -> "". */
    @NonNull
    public static String formatViews(long viewCount) {
        if (viewCount < 0) {
            return "";
        }
        if (viewCount >= 1_000_000_000L) {
            return trim(viewCount / 1_000_000_000.0) + "B views";
        }
        if (viewCount >= 1_000_000L) {
            return trim(viewCount / 1_000_000.0) + "M views";
        }
        if (viewCount >= 1_000L) {
            return trim(viewCount / 1_000.0) + "K views";
        }
        return viewCount + (viewCount == 1 ? " view" : " views");
    }

    private static String trim(double value) {
        if (value >= 100) {
            return String.valueOf(Math.round(value));
        }
        String text = String.format(Locale.US, "%.1f", value);
        return text.endsWith(".0") ? text.substring(0, text.length() - 2) : text;
    }

    /** 75 -> "1:15"; 3700 -> "1:01:40"; negative (live/unknown) -> "LIVE". */
    @NonNull
    public static String formatDuration(long durationSec) {
        if (durationSec < 0) {
            return "LIVE";
        }
        long hours = durationSec / 3600;
        long minutes = (durationSec % 3600) / 60;
        long seconds = durationSec % 60;
        if (hours > 0) {
            return String.format(Locale.US, "%d:%02d:%02d", hours, minutes, seconds);
        }
        return String.format(Locale.US, "%d:%02d", minutes, seconds);
    }

    /** "Channel • 1.2M views • 3 days ago" (skips blanks). */
    @NonNull
    public static String metaLine(@NonNull VideoItem item) {
        return metaLine(item.getChannelName(), item.getViewCount(), item.getUploadedText());
    }

    /** Joins the non-empty parts of a video meta line. */
    @NonNull
    public static String metaLine(String channel, long viewCount, String uploadedText) {
        StringBuilder out = new StringBuilder();
        appendPart(out, channel);
        appendPart(out, formatViews(viewCount));
        appendPart(out, uploadedText);
        return out.toString();
    }

    private static void appendPart(StringBuilder out, String part) {
        if (part == null || part.isEmpty()) {
            return;
        }
        if (out.length() > 0) {
            out.append(" • ");
        }
        out.append(part);
    }
}
