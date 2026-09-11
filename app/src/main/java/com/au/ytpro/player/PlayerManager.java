package com.au.ytpro.player;

import android.content.Context;

import androidx.annotation.Nullable;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.database.StandaloneDatabaseProvider;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.cache.CacheDataSource;
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor;
import androidx.media3.datasource.cache.SimpleCache;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.dash.DashMediaSource;
import androidx.media3.exoplayer.hls.HlsMediaSource;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.ProgressiveMediaSource;
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector;

import java.io.File;
import java.io.Serializable;
import java.util.HashSet;
import java.util.List;

/**
 * Single place that builds every ExoPlayer in the app.
 *
 * <ul>
 *   <li>H.264 software/hardware fallback: the renderers factory enables
 *       decoder fallback, and stream selection prefers progressive H.264
 *       (see {@code YouTubeRepository}), so legacy chipsets keep playing.</li>
 *   <li>Subtitles/CC are disabled by default on every player.</li>
 *   <li>All players share one on-disk cache ({@code exo-cache}, 200 MB LRU)
 *       which "Clear App Cache" evicts.</li>
 * </ul>
 */
public final class PlayerManager {

    /** Delivery type of one playback URL. */
    public enum SourceType {
        PROGRESSIVE,
        DASH,
        HLS
    }

    /** One playable URL plus its delivery type, ordered best-first. */
    public static final class Candidate implements Serializable {
        private static final long serialVersionUID = 1L;

        public final String url;
        public final SourceType type;
        public final String label;

        public Candidate(String url, SourceType type, String label) {
            this.url = url;
            this.type = type;
            this.label = label == null ? "" : label;
        }
    }

    /** Fatal playback errors after every candidate was tried. */
    public interface ErrorHooks {
        void onFatalError(PlaybackException error);
    }

    private static final String USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 YTPro/2.0";

    private static final long DISK_CACHE_BYTES = 200L * 1024L * 1024L;

    @Nullable
    private static SimpleCache cache;

    private PlayerManager() {
    }

    /** Shared on-disk playback cache (created once per process). */
    public static synchronized SimpleCache sharedCache(Context context) {
        if (cache == null) {
            Context app = context.getApplicationContext();
            File dir = new File(app.getCacheDir(), "exo-cache");
            //noinspection ResultOfMethodCallIgnored
            dir.mkdirs();
            cache = new SimpleCache(
                    dir,
                    new LeastRecentlyUsedCacheEvictor(DISK_CACHE_BYTES),
                    new StandaloneDatabaseProvider(app));
        }
        return cache;
    }

    /**
     * Creates a player with decoder fallback (MediaCodec failure falls back
     * to a software decoder on legacy chipsets) and subtitles disabled.
     */
    public static ExoPlayer createPlayer(Context context) {
        Context app = context.getApplicationContext();
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(app)
                .setEnableDecoderFallback(true)
                .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_OFF);

        DefaultTrackSelector selector = new DefaultTrackSelector(app);
        selector.setParameters(selector.buildUponParameters()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true));

        ExoPlayer player = new ExoPlayer.Builder(app)
                .setRenderersFactory(renderers)
                .setTrackSelector(selector)
                .setMediaSourceFactory(mediaSourceFactory(app))
                .build();
        // Belt-and-braces: subtitles/CC stay off even if a track sneaks in.
        player.setTrackSelectionParameters(player.getTrackSelectionParameters()
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                .build());
        return player;
    }

    /** Media-source factory reading through the shared disk cache. */
    public static MediaSource.Factory mediaSourceFactory(Context context) {
        Context app = context.getApplicationContext();
        return new DefaultMediaSourceFactory(app).setDataSourceFactory(cachedFactory(app));
    }

    private static CacheDataSource.Factory cachedFactory(Context app) {
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setUserAgent(USER_AGENT)
                .setConnectTimeoutMs(15_000)
                .setReadTimeoutMs(15_000)
                .setAllowCrossProtocolRedirects(true);
        return new CacheDataSource.Factory()
                .setCache(sharedCache(app))
                .setUpstreamDataSourceFactory(http)
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR);
    }

    /** Builds the right source (progressive / DASH / HLS) for a candidate. */
    public static MediaSource sourceFor(Context context, Candidate candidate) {
        Context app = context.getApplicationContext();
        MediaItem item = MediaItem.fromUri(candidate.url);
        CacheDataSource.Factory dataSource = cachedFactory(app);
        switch (candidate.type) {
            case DASH:
                return new DashMediaSource.Factory(dataSource).createMediaSource(item);
            case HLS:
                return new HlsMediaSource.Factory(dataSource).createMediaSource(item);
            case PROGRESSIVE:
            default:
                return new ProgressiveMediaSource.Factory(dataSource).createMediaSource(item);
        }
    }

    /**
     * Prepares the first candidate and automatically advances through the
     * list when a URL fails; {@code hooks} fires only when every candidate
     * is exhausted.
     */
    public static void playCandidates(final ExoPlayer player,
                                      final Context context,
                                      final List<Candidate> candidates,
                                      @Nullable final ErrorHooks hooks) {
        if (candidates == null || candidates.isEmpty()) {
            return;
        }
        final Context app = context.getApplicationContext();
        final int[] index = {0};
        player.addListener(new Player.Listener() {
            @Override
            public void onPlayerError(PlaybackException error) {
                int next = index[0] + 1;
                if (next < candidates.size()) {
                    index[0] = next;
                    player.setMediaSource(sourceFor(app, candidates.get(next)));
                    player.prepare();
                } else if (hooks != null) {
                    hooks.onFatalError(error);
                }
            }
        });
        player.setMediaSource(sourceFor(app, candidates.get(0)));
        player.prepare();
    }

    /** Evicts the whole ExoPlayer disk cache (used by Clear App Cache). */
    public static synchronized boolean clearCache(Context context) {
        try {
            if (cache != null) {
                for (String key : new HashSet<>(cache.getKeys())) {
                    try {
                        cache.removeResource(key);
                    } catch (RuntimeException ignored) {
                        // Best effort: keep evicting the rest.
                    }
                }
            }
        } finally {
            return deleteChildren(new File(context.getCacheDir(), "exo-cache"));
        }
    }

    private static boolean deleteChildren(@Nullable File dir) {
        if (dir == null || !dir.exists()) {
            return true;
        }
        boolean deleted = true;
        File[] children = dir.listFiles();
        if (children != null) {
            for (File child : children) {
                if (child.isDirectory()) {
                    deleted &= deleteChildren(child);
                }
                if (!child.delete()) {
                    deleted = false;
                }
            }
        }
        return deleted;
    }
}
