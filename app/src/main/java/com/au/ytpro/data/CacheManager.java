package com.au.ytpro.data;

import android.content.Context;

import androidx.annotation.Nullable;

import com.au.ytpro.player.PlayerManager;
import com.bumptech.glide.Glide;

import java.io.File;

/**
 * "Clear App Cache": evicts the NewPipeExtractor HTTP cache, the ExoPlayer
 * disk cache, the Glide image caches and the generic app cache directories.
 * Subscriptions and likes (SharedPreferences) are intentionally preserved.
 */
public final class CacheManager {

    private CacheManager() {
    }

    /**
     * Clears every cache. Must be called on the main thread (Glide's memory
     * cache requires it); Glide's disk cache is cleared on a worker thread.
     *
     * @return true when every cache reported success.
     */
    public static boolean clearAll(Context context) {
        final Context app = context.getApplicationContext();
        boolean cleared = true;

        try {
            DownloaderImpl.get().clearCache();
        } catch (RuntimeException e) {
            cleared = false;
        }

        try {
            cleared &= PlayerManager.clearCache(app);
        } catch (RuntimeException e) {
            cleared = false;
        }

        try {
            Glide.get(app).clearMemory();
        } catch (RuntimeException e) {
            cleared = false;
        }
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Glide.get(app).clearDiskCache();
                } catch (RuntimeException ignored) {
                    // Best effort; the memory cache is already gone.
                }
            }
        }).start();

        cleared &= deleteChildren(app.getCacheDir());
        cleared &= deleteChildren(app.getExternalCacheDir());
        return cleared;
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
