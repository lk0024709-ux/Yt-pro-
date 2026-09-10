package com.au.ytpro;

import android.annotation.SuppressLint;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.net.Uri;
import android.os.Bundle;
import android.os.SystemClock;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.CookieManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Fullscreen WebView container for the YouTube mobile web client.
 *
 * <p>Responsibilities:
 * <ul>
 *   <li>Load {@value #HOME_URL} with DOM storage and hardware acceleration.</li>
 *   <li>Rewrite the User-Agent so Google sign-in does not reject the client
 *       with {@code disallowed_useragent}.</li>
 *   <li>Provide smart back navigation (watch pages jump straight home).</li>
 *   <li>Host fullscreen video playback in a {@link FrameLayout} overlay.</li>
 *   <li>Show an offline fallback page when the main frame fails to load.</li>
 *   <li>Expose the in-app settings sheet (About + Clear App Cache).</li>
 * </ul>
 */
public class MainActivity extends AppCompatActivity {

    /** Entry point of the wrapped client. */
    private static final String HOME_URL = "https://m.youtube.com/";

    /** Any URL containing this fragment is treated as a video watch page. */
    private static final String WATCH_PATH_FRAGMENT = "/watch";

    /** Asset rendered when the network is unavailable. */
    private static final String OFFLINE_ASSET = "offline.html";

    /** JavaScript bridge name used by {@code offline.html}. */
    private static final String BRIDGE_NAME = "YTPro";

    private static final long EXIT_CONFIRM_WINDOW_MS = 2000L;

    private WebView webView;
    private ProgressBar progressBar;
    private FrameLayout fullscreenContainer;
    private ImageButton settingsButton;

    /** Non-null while a video is playing in fullscreen. */
    @Nullable
    private View customView;
    @Nullable
    private WebChromeClient.CustomViewCallback customViewCallback;
    private int orientationBeforeFullscreen = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;

    /** True while the offline fallback page is on screen (prevents reload loops). */
    private boolean offlinePageShown;
    private String offlineRetryUrl = HOME_URL;

    private long lastBackPressAt;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        webView = findViewById(R.id.web_view);
        progressBar = findViewById(R.id.progress_bar);
        fullscreenContainer = findViewById(R.id.fullscreen_container);
        settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> showSettingsDialog());

        configureWebView();

        if (savedInstanceState != null && webView.restoreState(savedInstanceState) != null) {
            // Restored a previous session (e.g. process death): keep that page.
            return;
        }
        loadHome();
    }

    // ------------------------------------------------------------------
    // WebView configuration
    // ------------------------------------------------------------------

    @SuppressLint({"SetJavaScriptEnabled", "JavascriptInterface"})
    private void configureWebView() {
        WebSettings settings = webView.getSettings();

        // Required for the YouTube web client.
        settings.setJavaScriptEnabled(true);
        settings.setDomStorageEnabled(true);
        settings.setDatabaseEnabled(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);

        // Playback should be able to start from an inline player without a tap.
        settings.setMediaPlaybackRequiresUserGesture(false);

        // Mobile viewport handling: fit the page, no desktop zoom controls.
        settings.setUseWideViewPort(true);
        settings.setLoadWithOverviewMode(true);
        settings.setSupportZoom(false);
        settings.setBuiltInZoomControls(false);
        settings.setDisplayZoomControls(false);

        // Cached assets are fine; YouTube busts its own cache with query strings.
        settings.setCacheMode(WebSettings.LOAD_DEFAULT);
        settings.setSaveFormData(false);

        // This wrapper never needs local file or content access.
        settings.setAllowFileAccess(false);
        settings.setAllowContentAccess(false);
        settings.setGeolocationEnabled(false);

        // Embedded players are frequently served over a different origin.
        settings.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);

        applySignInSafeUserAgent(settings);
        configureCookies();

        webView.addJavascriptInterface(new OfflineBridge(), BRIDGE_NAME);
        webView.setWebViewClient(new AppWebViewClient());
        webView.setWebChromeClient(new AppWebChromeClient());
        webView.setBackgroundColor(getResources().getColor(R.color.black));
        webView.setOverScrollMode(View.OVER_SCROLL_NEVER);
    }

    /**
     * Google sign-in refuses WebView clients: their User-Agent contains the
     * {@code "; wv"} marker and the OAuth flow answers with
     * {@code disallowed_useragent}. Stripping that marker makes the client
     * indistinguishable from a normal mobile browser.
     */
    private void applySignInSafeUserAgent(@NonNull WebSettings settings) {
        String userAgent = settings.getUserAgentString();
        if (userAgent == null || userAgent.isEmpty()) {
            return;
        }
        String patched = userAgent.replace("; wv", "");
        if (!patched.equals(userAgent)) {
            settings.setUserAgentString(patched);
        }
    }

    private void configureCookies() {
        CookieManager cookieManager = CookieManager.getInstance();
        cookieManager.setAcceptCookie(true);
        cookieManager.setAcceptThirdPartyCookies(webView, true);
    }

    // ------------------------------------------------------------------
    // Navigation
    // ------------------------------------------------------------------

    private void loadHome() {
        offlinePageShown = false;
        if (webView != null) {
            webView.loadUrl(HOME_URL);
        }
    }

    /**
     * Smart back navigation.
     *
     * <p>Order matters: leave fullscreen first, then collapse a watch page
     * straight to the home feed (walking back through video history one entry
     * at a time is the behaviour users complain about), then fall back to the
     * WebView history, and finally require a double press to exit.
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (customView != null) {
            hideFullscreenVideo();
            return;
        }

        String currentUrl = webView == null ? null : webView.getUrl();

        if (currentUrl != null && currentUrl.contains(WATCH_PATH_FRAGMENT)) {
            loadHome();
            return;
        }

        if (webView != null && webView.canGoBack()) {
            webView.goBack();
            return;
        }

        long now = SystemClock.elapsedRealtime();
        if (now - lastBackPressAt < EXIT_CONFIRM_WINDOW_MS) {
            super.onBackPressed();
            return;
        }
        lastBackPressAt = now;
        Toast.makeText(this, R.string.press_back_again_to_exit, Toast.LENGTH_SHORT).show();
    }

    /**
     * Non http(s) schemes ({@code intent://}, {@code market://}, deep links)
     * would otherwise throw inside the WebView. Hand them to the platform and
     * swallow the failure when nothing can handle them.
     *
     * @return true when the WebView must not load the URL itself.
     */
    private boolean handleExternalUrl(@Nullable String url) {
        if (url == null) {
            return false;
        }
        String lowerCase = url.toLowerCase();
        if (lowerCase.startsWith("http://") || lowerCase.startsWith("https://")) {
            return false;
        }
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
        } catch (ActivityNotFoundException ignored) {
            // No app can handle the scheme: stay on the current page.
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Fullscreen video
    // ------------------------------------------------------------------

    private void showFullscreenVideo(@NonNull View view, @Nullable WebChromeClient.CustomViewCallback callback) {
        if (customView != null) {
            // The client asked for fullscreen twice without hiding first.
            if (callback != null) {
                callback.onCustomViewHidden();
            }
            return;
        }
        customView = view;
        customViewCallback = callback;

        fullscreenContainer.setVisibility(View.VISIBLE);
        fullscreenContainer.addView(view, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        webView.setVisibility(View.GONE);
        settingsButton.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);

        orientationBeforeFullscreen = getRequestedOrientation();
        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(true);
    }

    private void hideFullscreenVideo() {
        if (customView == null) {
            return;
        }
        fullscreenContainer.removeView(customView);
        customView = null;
        fullscreenContainer.setVisibility(View.GONE);
        webView.setVisibility(View.VISIBLE);
        settingsButton.setVisibility(View.VISIBLE);

        setRequestedOrientation(orientationBeforeFullscreen);
        getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        applyImmersiveMode(false);

        if (customViewCallback != null) {
            customViewCallback.onCustomViewHidden();
            customViewCallback = null;
        }
    }

    private void applyImmersiveMode(boolean enabled) {
        View decorView = getWindow().getDecorView();
        if (enabled) {
            decorView.setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                            | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                            | View.SYSTEM_UI_FLAG_FULLSCREEN
                            | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        } else {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_VISIBLE);
        }
    }

    @Override
    public void onConfigurationChanged(@NonNull Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        // Rotation is handled in-place (see android:configChanges); keep the
        // system bars hidden for the duration of a fullscreen video.
        if (customView != null) {
            applyImmersiveMode(true);
        }
    }

    // ------------------------------------------------------------------
    // Settings sheet
    // ------------------------------------------------------------------

    private void showSettingsDialog() {
        View content = LayoutInflater.from(this).inflate(R.layout.dialog_settings, null);

        TextView versionView = content.findViewById(R.id.settings_version);
        versionView.setText(getString(R.string.version_format, BuildConfig.VERSION_NAME));

        TextView developerView = content.findViewById(R.id.settings_developer);
        developerView.setText(R.string.attribution_developer);

        TextView creditsView = content.findViewById(R.id.settings_credits);
        creditsView.setText(R.string.attribution_credits);

        AlertDialog dialog = new AlertDialog.Builder(this, R.style.Theme_YTPro_Dialog)
                .setView(content)
                .create();

        content.findViewById(R.id.button_clear_cache).setOnClickListener(v -> {
            dialog.dismiss();
            clearAppCache();
        });
        content.findViewById(R.id.button_reload).setOnClickListener(v -> {
            dialog.dismiss();
            reloadCurrentPage();
        });
        content.findViewById(R.id.button_home).setOnClickListener(v -> {
            dialog.dismiss();
            loadHome();
        });
        content.findViewById(R.id.button_close).setOnClickListener(v -> dialog.dismiss());

        dialog.show();
    }

    private void reloadCurrentPage() {
        if (webView == null) {
            return;
        }
        String url = webView.getUrl();
        offlinePageShown = false;
        webView.loadUrl(url != null ? url : HOME_URL);
    }

    /**
     * Clears the WebView caches plus the on-disk cache directories. Cookies and
     * DOM storage are intentionally preserved so the user stays signed in.
     */
    private void clearAppCache() {
        boolean cleared = true;
        if (webView != null) {
            webView.clearCache(true);
            webView.clearHistory();
            webView.clearFormData();
        }
        cleared &= deleteRecursively(getCacheDir());
        cleared &= deleteRecursively(getExternalCacheDir());

        Toast.makeText(this,
                cleared ? R.string.cache_cleared : R.string.cache_clear_failed,
                Toast.LENGTH_SHORT).show();
        loadHome();
    }

    private boolean deleteRecursively(@Nullable File file) {
        if (file == null || !file.exists()) {
            // Nothing to delete counts as success.
            return true;
        }
        boolean deleted = true;
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleted &= deleteRecursively(child);
                }
            }
        }
        return file.delete() && deleted;
    }

    // ------------------------------------------------------------------
    // Offline fallback
    // ------------------------------------------------------------------

    private void showOfflinePage(@Nullable String failedUrl) {
        if (webView == null || offlinePageShown) {
            return;
        }
        offlineRetryUrl = failedUrl != null && failedUrl.startsWith("http") ? failedUrl : HOME_URL;
        offlinePageShown = true;

        String html = readAsset(OFFLINE_ASSET);
        if (html != null) {
            // A base URL keeps the page's links absolute and lets the retry
            // button talk back through the JavaScript bridge.
            webView.loadDataWithBaseURL(HOME_URL, html, "text/html", "UTF-8", null);
        } else {
            webView.loadUrl("about:blank");
        }
    }

    @Nullable
    private String readAsset(@NonNull String name) {
        try (InputStream stream = getAssets().open(name);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder builder = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                builder.append(line).append('\n');
            }
            return builder.toString();
        } catch (IOException e) {
            return null;
        }
    }

    /** Bridge used by the retry button in {@code offline.html}. */
    private final class OfflineBridge {
        @JavascriptInterface
        public void retry() {
            runOnUiThread(() -> {
                if (webView == null) {
                    return;
                }
                offlinePageShown = false;
                webView.loadUrl(offlineRetryUrl);
            });
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    protected void onResume() {
        super.onResume();
        if (webView != null) {
            webView.onResume();
            webView.resumeTimers();
            webView.requestFocus();
        }
    }

    @Override
    protected void onPause() {
        // Leave fullscreen and freeze the engine: this is what stops audio from
        // playing on after the app is backgrounded.
        hideFullscreenVideo();
        if (webView != null) {
            webView.onPause();
            webView.pauseTimers();
        }
        super.onPause();
    }

    @Override
    protected void onStop() {
        CookieManager.getInstance().flush();
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        if (webView != null) {
            webView.saveState(outState);
        }
    }

    @Override
    protected void onDestroy() {
        hideFullscreenVideo();
        if (webView != null) {
            webView.stopLoading();
            webView.loadUrl("about:blank");
            webView.clearHistory();
            ViewGroup parent = (ViewGroup) webView.getParent();
            if (parent != null) {
                parent.removeView(webView);
            }
            webView.removeAllViews();
            webView.destroy();
            webView = null;
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------------
    // WebView clients
    // ------------------------------------------------------------------

    private final class AppWebViewClient extends WebViewClient {

        @Override
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull WebResourceRequest request) {
            Uri uri = request.getUrl();
            return uri != null && handleExternalUrl(uri.toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public boolean shouldOverrideUrlLoading(@NonNull WebView view, @NonNull String url) {
            return handleExternalUrl(url);
        }

        @Override
        public void onPageStarted(@NonNull WebView view, String url, @Nullable android.graphics.Bitmap favicon) {
            progressBar.setVisibility(View.VISIBLE);
        }

        @Override
        public void onPageFinished(@NonNull WebView view, String url) {
            progressBar.setVisibility(View.GONE);
        }

        @Override
        public void onReceivedError(@NonNull WebView view, @NonNull WebResourceRequest request,
                                    @NonNull WebResourceError error) {
            // Only the main document matters; a failed thumbnail must not blank
            // the page.
            if (!request.isForMainFrame()) {
                return;
            }
            Uri uri = request.getUrl();
            showOfflinePage(uri == null ? null : uri.toString());
        }

        @Override
        @SuppressWarnings("deprecation")
        public void onReceivedError(@NonNull WebView view, int errorCode, String description, String failingUrl) {
            // Pre-API 23 devices only get this overload.
            showOfflinePage(failingUrl);
        }

        @Override
        public void onReceivedSslError(@NonNull WebView view,
                                       @NonNull android.webkit.SslErrorHandler handler,
                                       @NonNull android.net.http.SslError error) {
            // Never continue past a broken certificate.
            handler.cancel();
        }
    }

    private final class AppWebChromeClient extends WebChromeClient {

        @Override
        public void onProgressChanged(@Nullable WebView view, int newProgress) {
            if (customView != null) {
                return;
            }
            progressBar.setProgress(newProgress);
            progressBar.setVisibility(newProgress >= 100 ? View.GONE : View.VISIBLE);
        }

        @Override
        public void onShowCustomView(View view, CustomViewCallback callback) {
            if (view == null) {
                if (callback != null) {
                    callback.onCustomViewHidden();
                }
                return;
            }
            showFullscreenVideo(view, callback);
        }

        @Override
        public void onHideCustomView() {
            hideFullscreenVideo();
        }
    }
}
