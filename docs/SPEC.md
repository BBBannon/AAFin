# AAFin — Specification & Architecture (v1)

Personal Android app: watch the owner's **Jellyfin** server on an **Android Auto** car screen.
Sideloaded, signed, never published to Play. Owner enables "Unknown sources" in Android Auto developer settings.

## 1. Agreed decisions (from requirements interview, 11/11/2026)

| Area | Decision |
|---|---|
| Services | Jellyfin only. Netflix/Prime out of scope (Widevine DRM blacks out non-secure surfaces). |
| Projection | Native Android Auto app via **Car App Library**, declared in the **NAVIGATION** category to obtain a drawing `Surface`; video rendered onto that surface. No screen mirroring. |
| Devices | Owner: Pixel 10 Pro XL (Android 16). Second user: spouse's phone, model unknown — support Android 10+ (minSdk 29). |
| Cars | 2025 Dacia Duster (wireless AA, touchscreen) + a second car (wired AA). No wired/wireless setting — AA abstracts transport. **Per-car profiles**, auto-detected. |
| Accounts | **Multiple Jellyfin accounts** (server + user) stored; one active; switch on phone and in car. |
| Login | **Quick Connect primary** (show code, user approves in another Jellyfin client), username/password fallback. Server behind **nginx reverse proxy, HTTPS**, plain Jellyfin auth (no SSO layer). |
| Streaming | Default cap **720p / 4 Mbps**, adjustable per car profile (options: 480p/2 Mbps, 720p/4 Mbps, 1080p/8 Mbps, 1080p/15 Mbps, Original). Server transcodes when needed. |
| Downloads | Per download choose **Original** or **720p** (720p pre-selected). Offline playback in car. |
| Progress | Sync watch progress to Jellyfin (start/progress/stop reports). Offline progress queued and sent later. |
| Content | **Films and TV only** (collection types `movies`, `tvshows`, plus `mixed`/boxsets acceptable). No music, no live TV. |
| Phone UI | **No playback on phone.** Phone app = accounts/login, settings, car profiles, browse-to-download, downloads manager. |
| Driving | Video allowed at all times (owner's explicit decision). Do not add a parked lock. |
| Updates | Local signed release build, installed via `adb install -r`. GitHub later. |
| Signing | New release keystore, outside the repo. Passwords in git-ignored `keystore.properties`. |
| Logo | Original artwork blending Jellyfin's rounded-triangle + purple→blue gradient (#AA5CC3 → #00A4DC) with an Android-Auto-style arrow/chevron. Not a copy of either logo. |

## 2. Toolchain (this PC — nothing on PATH)

- JDK 17: `C:\Users\Ben\Android\jdk17\jdk-17.0.20.1+1` → set `JAVA_HOME`.
- Android SDK: `C:\Users\Ben\Android\sdk` (platform android-35, build-tools 35.0.0, platform-tools). → `local.properties` `sdk.dir=C\:/Users/Ben/Android/sdk`.
- Gradle 8.11.1: `C:\Users\Ben\Android\gradle-8.11.1\bin\gradle.bat` (use it once to generate the wrapper; then use `gradlew.bat`).
- `adb`: `C:\Users\Ben\Android\sdk\platform-tools\adb.exe`. Owner's Pixel is connected by USB with debugging on.
- **Do not install SDK packages without asking the lead.** compileSdk/targetSdk = 35 unless a dependency forces 36.

## 3. Tech stack

- Kotlin, single `:app` module, package `app.aafin`. minSdk 29, target/compile 35.
- **Car App Library** `androidx.car.app:app` (+ `app-projected` for Android Auto, + `app-testing` for tests).
- **Media3**: `exoplayer`, `exoplayer-hls`, `datasource-okhttp`, `ui` (PlayerView in Presentation), `session` (steering-wheel controls), `exoplayer-workmanager` optional.
- **Jellyfin Kotlin SDK** `org.jellyfin.sdk:jellyfin-core` (handles auth header, Quick Connect, typed API). Verify current version + API names from source/docs — do not guess.
- Jetpack Compose (Material 3) for phone UI. Coil for images. DataStore for settings/accounts. WorkManager for queued progress reports. Kotlin coroutines/Flow. No DI framework — a simple `AppGraph` singleton created in `Application`.
- Pick mutually compatible, stable versions that work with compileSdk 35 and AGP compatible with Gradle 8.11.1.

## 4. Architecture

```
app.aafin
├── AAFinApp (Application) → AppGraph (singletons)
├── data/
│   ├── accounts/   AccountStore (DataStore JSON), Account(id, serverUrl, serverName, userId, userName, accessToken)
│   ├── jellyfin/   JellyfinRepository (browse, item detail, resume, next up, search), AuthRepository (QuickConnect + password)
│   ├── playback/   PlaybackResolver (online PlaybackInfo → MediaItem | offline download → MediaItem), DeviceProfileBuilder (MediaCodecList-driven), ProgressReporter (+ queued WorkManager fallback)
│   ├── downloads/  DownloadRepo (Media3 DownloadManager + SimpleCache, item metadata + subtitle files stored alongside)
│   └── profiles/   CarProfileStore (profile per detected car → quality settings)
├── car/            AAFinCarAppService, AAFinSession, screens (Home, Library, Series, Search, Accounts, Downloads, Player), VideoSurfaceRenderer
└── phone/          MainActivity (Compose): Accounts/Login, Browse→Download, Downloads, Car profiles, Settings, Setup checklist
```

### 4.1 Car app (critical path)

- `CarAppService` with intent-filter category `androidx.car.app.category.NAVIGATION`; permissions `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`. `createHostValidator()` → `HostValidator.ALLOW_ALL_HOSTS_VALIDATOR` (personal sideload). `minCarApiLevel` meta-data = 1; guard newer APIs with `carContext.carAppApiLevel`.
- **Do NOT call `NavigationManager.navigationStarted()`** — we are not navigating and must not kick out Google Maps.
- **Video rendering:** `AppManager.setSurfaceCallback`. On `onSurfaceAvailable(SurfaceContainer)`: create a private `VirtualDisplay` (`DisplayManager.createVirtualDisplay(name, w, h, dpi, surface, 0)`) and show a `Presentation` on it containing a Media3 `PlayerView` (resize mode FIT → correct letterboxing, subtitles via SubtitleView, buffering spinner, title/clock overlay). Rationale: `ExoPlayer.setVideoSurface(carSurface)` alone stretches video. Tear down on `onSurfaceDestroyed`; recreate on size change. When not on the player screen, the presentation shows a simple branded background (logo + "AAFin").
- Respect `onStableAreaChanged`/`onVisibleAreaChanged` — pad the PlayerView so action strips don't hide the picture where possible (optional; FIT inside visible area).
- **Templates & task limit (max 5 deep):** Home (ListTemplate: Continue watching, Next up, Libraries…, Downloads, Search, Account switcher) → Library (GridTemplate with posters, paged) → Series (ListTemplate, sectioned by season, episodes as rows) → Player (NavigationTemplate). Films go Library → Player. Respect `ConstraintManager.getContentLimit(CONTENT_LIMIT_TYPE_LIST / GRID)`; add "More…" row for paging.
- **Player screen:** `NavigationTemplate` with `ActionStrip`: Back, Play/Pause, −10 s, +30 s, Options (subtitles / audio track / quality → ListTemplate). `MapActionStrip` optional. Surface `onClick` (car API ≥ 5) toggles play/pause; `onScroll` horizontal = seek (optional, guarded). Show position/duration and title in the Presentation overlay (auto-hide).
- **ExoPlayer lifecycle:** single player owned by a `PlaybackController` in AppGraph; audio attributes `USAGE_MEDIA`/`CONTENT_TYPE_MOVIE` with audio focus handling; a Media3 `MediaSession` (no MediaSessionService/MediaBrowser — must not register as an AA media app) so steering-wheel buttons work. Stop + report on session destroy.
- **Car profile detection:** `CarHardwareManager` → `CarInfo.fetchModel` (manufacturer/name/year) when available; fallback key = surface width×height×dpi. New car → new profile with defaults (720p/4 Mbps). Phone UI can rename/edit profiles.
- Images: posters/thumbs loaded with Coil to Bitmap → `CarIcon`; invalidate screen when loaded; keep small (≤ 256 px).
- Errors: friendly `MessageTemplate` ("Can't reach server", "Sign in on your phone first") with Retry.

### 4.2 Jellyfin

- Device identity: stable random `deviceId` per install; client name "AAFin", version from BuildConfig, device name = `Build.MODEL`.
- Quick Connect: `GET /QuickConnect/Enabled` → `POST /QuickConnect/Initiate` → show Code → poll `GET /QuickConnect/Connect?secret=` until `Authenticated` → `POST /Users/AuthenticateWithQuickConnect` → token. Password: `POST /Users/AuthenticateByName`.
- Server URL normalisation: accept with/without scheme/trailing slash/base path; validate via `GET /System/Info/Public`.
- Playback: `POST /Items/{id}/PlaybackInfo` with `DeviceProfile` + `MaxStreamingBitrate` (from car profile) + `EnableDirectPlay/DirectStream/Transcoding`. Direct play → `/Videos/{id}/stream?static=true&MediaSourceId=…`; else `TranscodingUrl` (HLS) → `HlsMediaSource`. Auth via header (OkHttp data source factory with `Authorization: MediaBrowser … Token="…"`). Keep `PlaySessionId`.
- Reports: `/Sessions/Playing`, `/Sessions/Playing/Progress` (every 10 s + on pause/seek), `/Sessions/Playing/Stopped`. Resume from `UserData.PlaybackPositionTicks` (ticks = 10,000,000/s).
- Subtitles: text external subs as `MediaItem.SubtitleConfiguration`; image-based (PGS/VOBSUB) selected → request burn-in via `SubtitleStreamIndex` + `SubtitleMethod=Encode` (re-resolve playback).

### 4.3 Downloads

- Media3 `DownloadManager` + `SimpleCache` in app-private storage + `DownloadService` (foreground, type `dataSync`; notification with progress). Requires `POST_NOTIFICATIONS` permission request on phone.
- Original: progressive download of `/Items/{id}/Download`. 720p: HLS download of a transcode URL (h264/aac, maxWidth 1280, 4 Mbps).
- Store item metadata (name, series, season/episode, runtime, poster file, external subtitle files) as JSON beside the cache so offline browse works with no network.
- Offline playback: `CacheDataSource` read-only; progress queued via WorkManager and sent when online.

### 4.4 Phone UI (Compose, Material 3, dark theme using logo palette)

Screens: Setup checklist (first run) · Accounts (add via Quick Connect / password, switch, remove) · Browse (libraries → items → episodes, "Download" with Original/720p choice) · Downloads (progress, delete, storage used) · Car profiles (list, rename, quality) · Settings (default quality, about/version).

Setup checklist text (shown in app and in `docs/PHONE-SETUP.md`):
1. Install APK. 2. Android Auto settings → tap Version 10× → developer settings → enable **Unknown sources**. 3. Phone Settings → Apps → Android Auto → Customise launcher: make sure AAFin is shown. 4. Sign in to Jellyfin in AAFin on the phone. 5. Connect to car → open AAFin from the AA launcher.

## 5. Quality bar

- Builds with `gradlew.bat assembleRelease` signed. Unit tests for: URL normalisation, DeviceProfile building, bitrate/profile mapping, ticks conversion, PlaybackInfo → MediaItem resolution (fake responses), Quick Connect polling state machine, download metadata serialisation. Car screens tested with `androidx.car.app:app-testing` where practical.
- No TODO/stub code in live paths. No hard-coded server URLs or credentials. Errors surfaced, never swallowed.
- UK English in all user-facing text.
