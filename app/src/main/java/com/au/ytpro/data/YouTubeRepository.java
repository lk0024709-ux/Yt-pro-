package com.au.ytpro.data;

import android.os.Handler;
import android.os.Looper;

import com.au.ytpro.player.PlayerManager;
import com.au.ytpro.util.FormatUtils;

import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ServiceList;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelExtractor;
import org.schabi.newpipe.extractor.channel.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs;
import org.schabi.newpipe.extractor.kiosk.KioskExtractor;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.stream.Description;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.schabi.newpipe.extractor.stream.VideoStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * All NewPipeExtractor access lives here, off the main thread.
 *
 * <p>Resolves trending / search / Shorts feeds, per-video playback URLs and
 * related videos, and channel uploads — directly from YouTube, with no
 * Google API quota. Callbacks always fire on the main thread.
 */
public final class YouTubeRepository {

    /** Result of {@link #stream(String, Callback)}. */
    public static final class StreamData {
        public final List<PlayerManager.Candidate> candidates;
        public final List<VideoItem> related;
        public final String title;
        public final String channelName;
        public final String channelUrl;
        public final String avatarUrl;
        public final String description;
        public final String uploadedText;
        public final long viewCount;
        public final long durationSec;

        StreamData(List<PlayerManager.Candidate> candidates,
                   List<VideoItem> related,
                   String title,
                   String channelName,
                   String channelUrl,
                   String avatarUrl,
                   String description,
                   String uploadedText,
                   long viewCount,
                   long durationSec) {
            this.candidates = candidates;
            this.related = related;
            this.title = title;
            this.channelName = channelName;
            this.channelUrl = channelUrl;
            this.avatarUrl = avatarUrl;
            this.description = description;
            this.uploadedText = uploadedText;
            this.viewCount = viewCount;
            this.durationSec = durationSec;
        }
    }

    /** Main-thread callback for every repository call. */
    public interface Callback<T> {
        void onSuccess(T value);

        void onError(Exception e);
    }

    private static final ExecutorService IO = Executors.newFixedThreadPool(4);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());

    private static final int MAX_SHORTS = 25;
    private static final int MAX_RELATED = 25;

    private YouTubeRepository() {
    }

    private static StreamingService youtube() {
        return ServiceList.YouTube;
    }

    /** Trending feed for the Home tab. */
    public static void trending(final Callback<List<VideoItem>> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    KioskExtractor kiosk =
                            youtube().getKioskList().getDefaultKioskExtractor();
                    kiosk.fetchPage();
                    postSuccess(callback, toVideoItems(kiosk.getInitialPage().getItems()));
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /** YouTube search for the Home tab. */
    public static void search(final String query, final Callback<List<VideoItem>> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    SearchExtractor extractor = youtube().getSearchExtractor(query);
                    extractor.fetchPage();
                    postSuccess(callback, toVideoItems(extractor.getInitialPage().getItems()));
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Vertical Shorts feed: short-form search hits (falling back to short
     * trending videos), capped so each page can own a player.
     */
    public static void shortsFeed(final Callback<List<VideoItem>> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<VideoItem> out = new ArrayList<>();
                    try {
                        SearchExtractor extractor = youtube().getSearchExtractor("#Shorts");
                        extractor.fetchPage();
                        for (InfoItem item : extractor.getInitialPage().getItems()) {
                            if (item instanceof StreamInfoItem) {
                                StreamInfoItem stream = (StreamInfoItem) item;
                                if (stream.isShortFormContent()
                                        || (stream.getDuration() > 0 && stream.getDuration() <= 70)) {
                                    out.add(VideoItem.from(stream));
                                }
                            }
                        }
                    } catch (Exception ignored) {
                        // Fall through to the trending fallback below.
                    }
                    if (out.isEmpty()) {
                        KioskExtractor kiosk =
                                youtube().getKioskList().getDefaultKioskExtractor();
                        kiosk.fetchPage();
                        for (InfoItem item : kiosk.getInitialPage().getItems()) {
                            if (item instanceof StreamInfoItem) {
                                StreamInfoItem stream = (StreamInfoItem) item;
                                if (stream.isShortFormContent()
                                        || (stream.getDuration() > 0 && stream.getDuration() <= 90)) {
                                    out.add(VideoItem.from(stream));
                                }
                            }
                        }
                    }
                    if (out.size() > MAX_SHORTS) {
                        out = new ArrayList<>(out.subList(0, MAX_SHORTS));
                    }
                    postSuccess(callback, out);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /**
     * Full stream info for one video: ordered playback candidates plus the
     * metadata and related videos the Watch screen needs.
     */
    public static void stream(final String videoUrl, final Callback<StreamData> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    StreamInfo info = StreamInfo.getInfo(videoUrl);
                    List<PlayerManager.Candidate> candidates = selectCandidates(info);
                    if (candidates.isEmpty()) {
                        throw new IllegalStateException("No playable streams found");
                    }
                    List<VideoItem> related = toVideoItems(info.getRelatedItems());
                    if (related.size() > MAX_RELATED) {
                        related = new ArrayList<>(related.subList(0, MAX_RELATED));
                    }
                    Description description = info.getDescription();
                    postSuccess(callback, new StreamData(
                            candidates,
                            related,
                            info.getName() == null ? "" : info.getName(),
                            info.getUploaderName() == null ? "" : info.getUploaderName(),
                            info.getUploaderUrl() == null ? "" : info.getUploaderUrl(),
                            FormatUtils.bestImageUrl(info.getUploaderAvatars()),
                            description == null || description.getContent() == null
                                    ? "" : description.getContent(),
                            info.getTextualUploadDate() == null ? "" : info.getTextualUploadDate(),
                            info.getViewCount(),
                            info.getDuration()));
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /** Latest uploads of one channel (Subscriptions tab). */
    public static void channelVideos(final String channelUrl,
                                     final Callback<List<VideoItem>> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    postSuccess(callback, fetchChannelVideos(channelUrl));
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    /** Merged uploads of every subscribed channel (Subscriptions tab). */
    public static void subscriptionsFeed(final List<SubscriptionsStore.Channel> channels,
                                         final int perChannel,
                                         final Callback<List<VideoItem>> callback) {
        IO.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    List<VideoItem> out = new ArrayList<>();
                    for (SubscriptionsStore.Channel channel : channels) {
                        try {
                            List<VideoItem> videos = fetchChannelVideos(channel.url);
                            for (int i = 0; i < videos.size() && i < perChannel; i++) {
                                out.add(videos.get(i));
                            }
                        } catch (Exception ignored) {
                            // One failing channel must not blank the feed.
                        }
                    }
                    postSuccess(callback, out);
                } catch (Exception e) {
                    postError(callback, e);
                }
            }
        });
    }

    private static List<VideoItem> fetchChannelVideos(String channelUrl) throws Exception {
        ChannelExtractor channel = youtube().getChannelExtractor(channelUrl);
        channel.fetchPage();
        ChannelTabExtractor tab =
                youtube().getChannelTabExtractorFromId(channel.getId(), ChannelTabs.VIDEOS);
        tab.fetchPage();
        return toVideoItems(tab.getInitialPage().getItems());
    }

    /**
     * Orders playback URLs best-first: progressive H.264 first (guaranteed
     * software-decodable on legacy chipsets), then other progressive URLs,
     * then the adaptive DASH / HLS manifests.
     */
    private static List<PlayerManager.Candidate> selectCandidates(StreamInfo info) {
        List<VideoStream> h264 = new ArrayList<>();
        List<VideoStream> other = new ArrayList<>();
        if (info.getVideoStreams() != null) {
            for (VideoStream stream : info.getVideoStreams()) {
                if (stream == null || !stream.isUrl() || stream.isVideoOnly()) {
                    continue;
                }
                if (stream.getUrl() == null || stream.getUrl().isEmpty()) {
                    continue;
                }
                if (isH264(stream)) {
                    h264.add(stream);
                } else {
                    other.add(stream);
                }
            }
        }
        Comparator<VideoStream> byHeight = new Comparator<VideoStream>() {
            @Override
            public int compare(VideoStream a, VideoStream b) {
                return Integer.compare(heightOf(b), heightOf(a));
            }
        };
        Collections.sort(h264, byHeight);
        Collections.sort(other, byHeight);

        List<PlayerManager.Candidate> out = new ArrayList<>();
        for (int i = 0; i < h264.size() && out.size() < 3; i++) {
            VideoStream stream = h264.get(i);
            out.add(new PlayerManager.Candidate(stream.getUrl(),
                    PlayerManager.SourceType.PROGRESSIVE, "H.264 " + stream.getResolution()));
        }
        if (!other.isEmpty() && out.size() < 4) {
            VideoStream stream = other.get(0);
            out.add(new PlayerManager.Candidate(stream.getUrl(),
                    PlayerManager.SourceType.PROGRESSIVE, stream.getResolution()));
        }
        if (info.getDashMpdUrl() != null && !info.getDashMpdUrl().isEmpty()) {
            out.add(new PlayerManager.Candidate(info.getDashMpdUrl(),
                    PlayerManager.SourceType.DASH, "Adaptive"));
        }
        if (info.getHlsUrl() != null && !info.getHlsUrl().isEmpty()) {
            out.add(new PlayerManager.Candidate(info.getHlsUrl(),
                    PlayerManager.SourceType.HLS, "Live"));
        }
        return out;
    }

    private static boolean isH264(VideoStream stream) {
        String codec = stream.getCodec();
        return codec != null && codec.toLowerCase(Locale.US).contains("avc");
    }

    private static int heightOf(VideoStream stream) {
        int height = stream.getHeight();
        return height > 0 ? height : 0;
    }

    private static List<VideoItem> toVideoItems(List<InfoItem> items) {
        List<VideoItem> out = new ArrayList<>();
        if (items == null) {
            return out;
        }
        for (InfoItem item : items) {
            if (item instanceof StreamInfoItem) {
                out.add(VideoItem.from((StreamInfoItem) item));
            }
        }
        return out;
    }

    private static <T> void postSuccess(final Callback<T> callback, final T value) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onSuccess(value);
            }
        });
    }

    private static <T> void postError(final Callback<T> callback, final Exception e) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                callback.onError(e);
            }
        });
    }
}
