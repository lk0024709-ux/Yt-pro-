# YT Pro

A **native Android WebView wrapper** for the YouTube mobile web client
(`https://m.youtube.com`). No Chrome history clutter, smart back navigation,
fullscreen playback, and a small in-app settings sheet — on every device back to
Android 5.0 (API 21).

## Feature summary

| Feature | Where |
|---|---|
| Loads `https://m.youtube.com/` fullscreen, DOM storage + hardware acceleration | `MainActivity.configureWebView()` |
| User-Agent `"; wv"` strip to fix Google sign-in (`disallowed_useragent`) | `MainActivity.applySignInSafeUserAgent()` |
| Smart back: `/watch` pages jump straight home instead of walking video history | `MainActivity.onBackPressed()` |
| Fullscreen video via `WebChromeClient` in a `FrameLayout` overlay | `MainActivity.showFullscreenVideo()` |
| Lifecycle-safe: pauses timers/audio, tears the WebView down on destroy | `MainActivity.onPause()/onDestroy()` |
| Offline fallback error page | `MainActivity.showOfflinePage()` + `assets/offline.html` |
| Settings gear with About (Developer: Google / Credits: AU) + Clear App Cache | `MainActivity.showSettingsDialog()` |
| Splash launcher screen | `SplashActivity` |
| GitHub Actions APK builds | `.github/workflows/build-apk.yml` |

## Branding (locked)

* **App name:** `YT Pro` (`res/values/strings.xml`)
* **Splash screen & About dialog:**
  * Developer: `Google`
  * Credits: `AU (Custom Client Wrapper & Optimizations)`

## Requirements

* JDK 17 (Gradle 8.4 + AGP 8.1.4)
* Android SDK platform 34 (installed automatically by CI)

## Building locally

```bash
./gradlew assembleDebug      # -> app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease    # -> app/build/outputs/apk/release/app-release-unsigned.apk
```

The release variant is unsigned by default.

## Signing a release

1. Create a keystore (keep it out of Git — see `.gitignore`).
2. Add `keystore.properties`:

   ```properties
   storeFile=/absolute/path/to/release.keystore
   storePassword=****
   keyAlias=ytpro
   keyPassword=****
   ```

3. Wire `signingConfigs { release { ... } }` into `app/build.gradle` and set
   `signingConfig signingConfigs.release` on the release build type.

## Continuous integration

`.github/workflows/build-apk.yml` runs on every push and pull request:

* `ubuntu-latest` runner with **JDK 17** (Temurin)
* installs Android SDK platform 34 / build-tools 34.0.0
* `./gradlew assembleDebug assembleRelease`
* uploads both APKs as downloadable artifacts (`ytpro-debug-apk`,
  `ytpro-release-apk`)

## Project structure

```
app/src/main/
├── AndroidManifest.xml          # permissions, activities, launcher
├── java/com/au/ytpro/
│   ├── SplashActivity.java      # launcher / brand screen
│   └── MainActivity.java        # WebView host + fullscreen + settings
├── assets/offline.html          # offline fallback page (JS bridge: YTPro.retry)
└── res/
    ├── layout/                  # activity_main, activity_splash, dialog_settings
    ├── drawable/                # logo, gear, launcher vectors
    ├── mipmap-anydpi-v26/       # adaptive icon (API 26+)
    ├── mipmap-*/                # raster launcher icons (API 21-25)
    └── values/                  # strings, colors, styles
tools/generate_icons.py          # regenerates the raster launcher icons
```

## Developer notes

* **Why the User-Agent patch works:** the OAuth flow rejects any UA containing
  `"; wv"` (a WebView marker). We strip it once, on startup, so sign-in behaves
  like a real browser.
* **Back-navigation order:** leave fullscreen → collapse `/watch` → WebView
  history → double-tap to exit.
* **Background audio:** `onPause()` calls `webView.pauseTimers()` and leaves any
  fullscreen video, which is what stops audio after the app is backgrounded.
* **Offline page:** loaded with `loadDataWithBaseURL()` from `assets/`, and its
  Retry button calls back into the app through a `@JavascriptInterface` bridge.
