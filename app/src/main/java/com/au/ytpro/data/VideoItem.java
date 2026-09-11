package com.au.ytpro.data;

import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.au.ytpro.util.FormatUtils;

import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.Serializable;

/**
 * UI-friendly snapshot of a YouTube video / Short / live stream.
 *
 * <p>Mapped from NewPipeExtractor {@link StreamInfoItem}s on a background
 * thread; the UI layer never touches extractor objects directly.
 */
public final class VideoItem implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String url;
    private final String videoId;
    private final String title;
    private final String channelName;
    private final String channelUrl;
    /** Raw view count, or -1 when the service did not report one. */
    private final long viewCount;
    /** Duration in seconds, or -1 for live / unknown. */
    private final long durationSec;
    private final String thumbnailUrl;
    private final String avatarUrl;
    private final boolean shortForm;
    private final String uploadedText;

    public VideoItem(String url,
                     String videoId,
                     String title,
                     String channelName,
                     String channelUrl,
                     long viewCount,
                     long durationSec,
                     String thumbnailUrl,
                     String avatarUrl,
                     boolean shortForm,
                     String uploadedText) {
        this.url = url == null ? "" : url;
        this.videoId = videoId == null ? "" : videoId;
        this.title = title == null ? "" : title;
        this.channelName = channelName == null ? "" : channelName;
        this.channelUrl = channelUrl == null ? "" : channelUrl;
        this.viewCount = viewCount;
        this.durationSec = durationSec;
        this.thumbnailUrl = thumbnailUrl == null ? "" : thumbnailUrl;
        this.avatarUrl = avatarUrl == null ? "" : avatarUrl;
        this.shortForm = shortForm;
        this.uploadedText = uploadedText == null ? "" : uploadedText;
    }

    /** Maps an extractor search/trending/related item to a {@link VideoItem}. */
    @NonNull
    public static VideoItem from(@NonNull StreamInfoItem item) {
        String url = item.getUrl() == null ? "" : item.getUrl();
        String uploaded = item.getTextualUploadDate() == null ? "" : item.getTextualUploadDate();
        return new VideoItem(
                url,
                extractVideoId(url),
                item.getName(),
                item.getUploaderName(),
                item.getUploaderUrl(),
                item.getViewCount(),
                item.getDuration(),
                FormatUtils.bestImageUrl(item.getThumbnails()),
                FormatUtils.bestImageUrl(item.getUploaderAvatars()),
                item.isShortFormContent() || url.contains("/shorts/"),
                uploaded);
    }

    /**
     * Extracts the 11-char video id from watch / shorts / youtu.be URLs.
     * Returns "" when the URL has no recognisable id.
     */
    @NonNull
    public static String extractVideoId(@Nullable String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        try {
            Uri uri = Uri.parse(url);
            String v = uri.getQueryParameter("v");
            if (v != null && !v.isEmpty()) {
                return v;
            }
            String path = uri.getPath();
            if (path != null) {
                String[] segments = path.split("/");
                for (int i = segments.length - 1; i >= 0; i--) {
                    if (!segments[i].isEmpty()
                            && !"watch".equals(segments[i])
                            && !"shorts".equals(segments[i])
                            && !"live".equals(segments[i])
                            && !"embed".equals(segments[i])) {
                        return segments[i];
                    }
                }
            }
        } catch (RuntimeException ignored) {
            // Malformed URL: fall through to "".
        }
        return "";
    }

    @NonNull
    public String getUrl() {
        return url;
    }

    @NonNull
    public String getVideoId() {
        return videoId;
    }

    @NonNull
    public String getTitle() {
        return title;
    }

    @NonNull
    public String getChannelName() {
        return channelName;
    }

    @NonNull
    public String getChannelUrl() {
        return channelUrl;
    }

    public long getViewCount() {
        return viewCount;
    }

    public long getDurationSec() {
        return durationSec;
    }

    @NonNull
    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    @NonNull
    public String getAvatarUrl() {
        return avatarUrl;
    }

    public boolean isShortForm() {
        return shortForm;
    }

    @NonNull
    public String getUploadedText() {
        return uploadedText;
    }
}
