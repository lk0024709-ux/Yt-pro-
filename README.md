# YT Pro

A **100% native Android client** for YouTube — no WebView. Playback runs on
ExoPlayer (AndroidX Media3) and every feed, search result, channel lookup and
stream URL is resolved directly through NewPipeExtractor, with no Google API
quota — on every device back to Android 5.0 (API 21).

## Feature summary

| Feature | Where |
|---|---|
| Native bottom navigation: Home, Shorts, Subscriptions, Library | `MainActivity` + `res/menu/bottom_nav_menu.xml` |
| Home feed: trending + search in a `RecyclerView` of `CardView` rows (thumbnail, duration, channel avatar, title, views) | `ui/HomeFragment`, `ui/VideoAdapter` |
| Shorts: vertical `ViewPager2` with one integrated `PlayerView` per page, auto-play/pause on swipe | `ui/ShortsFragment`, `ui/ShortsAdapter` |
| Shorts double-tap-to-like with animated floating heart pop-up | `ShortsAdapter.Holder.popHeart()` |
| Watch screen: native `PlayerView` with styled controls + seekbar, double-tap −10s / +10s seek, play/pause, fullscreen toggle | `WatchActivity` |
| Watch "Up next" recommendation list below the player | `WatchActivity` + `item_related_video.xml` |
| Subtitles/CC disabled by default on every player | `player/PlayerManager.createPlayer()` |
| H.264-first stream selection with decoder fallback for legacy chipsets; DASH/HLS adaptive fallbacks | `data/YouTubeRepository`, `player/PlayerManager` |
| Shared 200 MB ExoPlayer disk cache + bounded extractor HTTP cache | `player/PlayerManager`, `data/DownloaderImpl` |
| Local subscriptions (no sign-in) with merged uploads feed; long-press to unsubscribe | `data/SubscriptionsStore`, `ui/SubscriptionsFragment` |
| Local likes backing Shorts + Watch hearts; liked list in Library | `data/LikesStore`, `ui/LibraryFragment` |
| Settings dialog with About (Developer: Google / Credits: AU) + Clear App Cache (extractor + player + image caches) | `ui/LibraryFragment`, `data/CacheManager` |
| Splash launcher screen | `SplashActivity` |
| GitHub Actions APK builds | `.github/workflows/build-apk.yml` |

## Branding (locked)

* **App name:** `YT Pro` (`res/values/strings.xml`)
* **Launcher icon:** the neon-red YouTube mark across all `res/mipmap-*` densities
* **Splash screen & Settings dialog:**
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

The release variant is unsigned by default. NewPipeExtractor is fetched from
JitPack (see `settings.gradle`); everything else comes from Google / Maven
Central.

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
├── AndroidManifest.xml          # permissions, app class, activities, launcher
├── java/com/au/ytpro/
│   ├── YTProApp.java            # Application: NewPipe.init(DownloaderImpl)
│   ├── SplashActivity.java      # launcher / brand screen
│   ├── MainActivity.java        # bottom nav + 4 native tabs
│   ├── WatchActivity.java       # native player + gestures + Up next
│   ├── data/
│   │   ├── VideoItem.java       # UI snapshot of a video / Short / stream
│   │   ├── DownloaderImpl.java  # extractor HTTP client (OkHttp + disk cache)
│   │   ├── YouTubeRepository.java # trending/search/shorts/stream/channel
│   │   ├── SubscriptionsStore.java# local channel subscriptions
│   │   ├── LikesStore.java      # local likes
│   │   └── CacheManager.java    # Clear App Cache (all native caches)
│   ├── player/
│   │   └── PlayerManager.java   # ExoPlayer factory: H.264 fallback, no CC,
│   │                            # shared disk cache, DASH/HLS/progressive
│   ├── ui/
│   │   ├── HomeFragment.java    # trending + search feed
│   │   ├── ShortsFragment.java  # vertical pager host
│   │   ├── ShortsAdapter.java   # per-page player + double-tap heart
│   │   ├── SubscriptionsFragment.java
│   │   ├── LibraryFragment.java # about + settings dialog + liked list
│   │   └── VideoAdapter.java    # shared CardView feed rows
│   └── util/
│       └── FormatUtils.java     # thumbnails, views, durations, meta lines
└── res/
    ├── layout/                  # activities, fragments, cards, shorts pages
    ├── menu/bottom_nav_menu.xml # 4 native tabs
    ├── color/nav_item_tint.xml  # bottom-nav checked/unchecked tint
    ├── drawable/                # tab/action vectors + player overlays
    ├── drawable-*/              # density-specific splash logo PNGs
    ├── mipmap-anydpi-v26/       # adaptive icon (API 26+), PNG layers
    ├── mipmap-*/                # legacy launcher icons + adaptive layers
    └── values/                  # strings, colors, styles
brand/ic_logo_reference.png      # source artwork for icons + splash
tools/generate_icons.py          # regenerates every raster brand asset
tools/verify_project.py          # static verification vs android-34.jar
```

## Rebranding the icons / splash

All raster brand assets are generated from `brand/ic_logo_reference.png`
(the neon-red mark). Drop a replacement artwork with the same name and run:

```bash
python3 tools/generate_icons.py   # needs Pillow + numpy
```

It rewrites the legacy squircle/round launcher PNGs, the adaptive
background/foreground layers (108dp, all densities) and the splash logo PNGs.

## Verification

```bash
# XML well-formedness, resource linking, Java syntax, and Android API checks
# against the real framework jar (from
# https://github.com/Sable/android-platforms, android-34/android.jar).
python3 tools/verify_project.py --android-jar /path/to/android-34.jar
```

## Developer notes

* **No WebView, no quotas:** `YouTubeRepository` talks to YouTube through
  NewPipeExtractor (`ServiceList.YouTube`), and `DownloaderImpl` is the
  OkHttp bridge the extractor requires. Core-library desugaring
  (`desugar_jdk_libs_nio`) backports the `java.time`/streams APIs the
  extractor needs down to API 21.
* **Legacy chipsets:** playback candidates are ordered progressive-H.264
  first, then other progressive URLs, then DASH/HLS manifests; the
  renderers factory additionally enables decoder fallback, and every
  player disables text tracks (CC) by default.
* **Back-navigation order:** leave fullscreen → back to the Home tab →
  double-press to exit.
* **Background audio:** `WatchActivity.onPause()` pauses the player and
  `ShortsFragment.onPause()` pauses every page, so audio never plays on
  after the app is backgrounded.
* ** Rotation:** activities declare `configChanges` and the Watch screen
  re-asserts immersive mode, so playback survives rotation and fullscreen
  toggling without rebuffering.
