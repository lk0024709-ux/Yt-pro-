package com.au.ytpro.data;

import android.content.Context;

import org.schabi.newpipe.extractor.downloader.Downloader;
import org.schabi.newpipe.extractor.downloader.Request;
import org.schabi.newpipe.extractor.downloader.Response;
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import okhttp3.Cache;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;

/**
 * NewPipeExtractor {@link Downloader} backed by OkHttp 4.x (Android 5.0+ /
 * API 21+ compatible) with a bounded on-disk HTTP cache.
 *
 * <p>All extractor traffic (trending, search, stream URLs, channel feeds)
 * flows through {@link #execute(Request)}. The cache is evicted by
 * {@link CacheManager#clearAll(Context)} together with the ExoPlayer cache.
 */
public final class DownloaderImpl extends Downloader {

    /** Mobile Chrome UA so YouTube serves the standard client responses. */
    private static final String DEFAULT_USER_AGENT =
            "Mozilla/5.0 (Linux; Android 10; Mobile) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36";

    private static final long HTTP_CACHE_BYTES = 20L * 1024L * 1024L;

    private static volatile DownloaderImpl instance;

    private final OkHttpClient client;
    private final File cacheDir;

    private DownloaderImpl(Context context) {
        Context app = context.getApplicationContext();
        cacheDir = new File(app.getCacheDir(), "newpipe-http");
        client = new OkHttpClient.Builder()
                .cache(new Cache(cacheDir, HTTP_CACHE_BYTES))
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(30, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .followRedirects(true)
                .build();
    }

    /** Creates (once) and returns the shared downloader. */
    public static synchronized DownloaderImpl init(Context context) {
        if (instance == null) {
            instance = new DownloaderImpl(context);
        }
        return instance;
    }

    /** Returns the shared downloader after {@link #init(Context)}. */
    public static synchronized DownloaderImpl get() {
        if (instance == null) {
            throw new IllegalStateException("DownloaderImpl.init() was not called");
        }
        return instance;
    }

    @Override
    public Response execute(Request request) throws IOException, ReCaptchaException {
        okhttp3.Request.Builder builder = new okhttp3.Request.Builder().url(request.url());

        boolean hasUserAgent = false;
        String contentType = null;
        Map<String, List<String>> headers = request.headers();
        if (headers != null) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() == null || entry.getValue() == null) {
                    continue;
                }
                for (String value : entry.getValue()) {
                    if (value == null) {
                        continue;
                    }
                    builder.addHeader(entry.getKey(), value);
                    if ("User-Agent".equalsIgnoreCase(entry.getKey())) {
                        hasUserAgent = true;
                    }
                    if ("Content-Type".equalsIgnoreCase(entry.getKey())) {
                        contentType = value;
                    }
                }
            }
        }
        if (!hasUserAgent) {
            builder.header("User-Agent", DEFAULT_USER_AGENT);
        }

        // The extractor's own helper maps its Localization to Accept-Language.
        Map<String, List<String>> localizationHeaders =
                Request.getHeadersFromLocalization(request.localization());
        if (localizationHeaders != null) {
            for (Map.Entry<String, List<String>> entry : localizationHeaders.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    for (String value : entry.getValue()) {
                        if (value != null) {
                            builder.addHeader(entry.getKey(), value);
                        }
                    }
                }
            }
        }

        String method = request.httpMethod() == null ? "GET" : request.httpMethod();
        if ("POST".equalsIgnoreCase(method)) {
            byte[] data = request.dataToSend() != null ? request.dataToSend() : new byte[0];
            MediaType mediaType = contentType != null ? MediaType.parse(contentType) : null;
            builder.post(RequestBody.create(data, mediaType));
        } else if ("HEAD".equalsIgnoreCase(method)) {
            builder.head();
        } else {
            builder.get();
        }

        okhttp3.Response raw = client.newCall(builder.build()).execute();
        try {
            String body = raw.body() != null ? raw.body().string() : "";
            Map<String, List<String>> responseHeaders = new LinkedHashMap<>();
            for (String name : raw.headers().names()) {
                responseHeaders.put(name, raw.headers().values(name));
            }
            return new Response(
                    raw.code(),
                    raw.message(),
                    responseHeaders,
                    body,
                    raw.request().url().toString());
        } finally {
            raw.close();
        }
    }

    /** Evicts the whole extractor HTTP cache (used by Clear App Cache). */
    public void clearCache() {
        try {
            if (client.cache() != null) {
                client.cache().evictAll();
            }
        } catch (IOException ignored) {
            // Fall through to deleting the directory below.
        }
        deleteRecursively(cacheDir);
    }

    private static void deleteRecursively(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursively(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
