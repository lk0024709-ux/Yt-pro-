package com.au.ytpro;

import android.app.Application;

import com.au.ytpro.data.DownloaderImpl;

import org.schabi.newpipe.extractor.NewPipe;

/**
 * Application entry point for the 100% native YT Pro client.
 *
 * <p>Initialises NewPipeExtractor exactly once. The extractor ships no HTTP
 * client of its own, so the app provides an OkHttp-backed
 * {@link DownloaderImpl} with a bounded on-disk cache (API 21+ safe).
 */
public class YTProApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        NewPipe.init(DownloaderImpl.init(this));
    }
}
