# YT Pro - ProGuard / R8 rules.

# ---------------------------------------------------------------------------
# JavaScript bridge.
# The retry button in assets/offline.html calls YTPro.retry(); R8 must keep the
# bridge class, its public methods, and the @JavascriptInterface annotation.
# ---------------------------------------------------------------------------
-keepclassmembers class com.au.ytpro.MainActivity$OfflineBridge {
    @android.webkit.JavascriptInterface <methods>;
}

# ---------------------------------------------------------------------------
# Android framework classes that the WebView reaches into via reflection.
# ---------------------------------------------------------------------------
-keepclassmembers class * extends android.webkit.WebViewClient {
    public void *(android.webkit.WebView, java.lang.String, android.graphics.Bitmap);
    public boolean *(android.webkit.WebView, java.lang.String);
    public void *(android.webkit.WebView, java.lang.String);
}

-keepclassmembers class * extends android.webkit.WebChromeClient {
    public void onShowCustomView(android.view.View, android.webkit.WebChromeClient$CustomViewCallback);
    public void onHideCustomView();
}

# Keep the generated BuildConfig so the About sheet can read VERSION_NAME.
-keep class com.au.ytpro.BuildConfig { *; }

# Preserve annotations (the bridge rule relies on @JavascriptInterface).
-keepattributes *Annotation*
-keepattributes JavascriptInterface
