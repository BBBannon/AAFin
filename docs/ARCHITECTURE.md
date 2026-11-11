# AAFin — Architecture (Stages 1–2: foundation + car app)

**What exists now:** build, signing, manifest, logo, the whole data layer (Stage 1) and the **Android Auto car app** (Stage 2, §11). The phone UI is still a placeholder.
**What comes next:** Stage 3 = phone UI (`app.aafin.phone`).

Read `docs/SPEC.md` first. This file is the **contract between the data layer and the UI stages**.

---

## 1. Ground rules for Stage 2 and 3

- **Get everything from the graph:** `AAFinApp.graph(context)` → `AppGraph`. No DI framework.
- **UI never touches the Jellyfin SDK.** Use the app models in `data/jellyfin/Models.kt` (`Library`, `MediaEntry`, `Page`, `ItemKind`).
- **One error type:** every data-layer call throws `JellyfinException`. Show `e.userMessage` (UK English, no URLs). Branch on `e.kind` (e.g. `NOT_SIGNED_IN` → "Sign in on your phone first").
- **IDs are strings** (Jellyfin GUIDs). **Times are milliseconds.** Ticks only exist inside the data layer (`Ticks`).
- **Never log a URL, a `PlaybackPlan`'s `url`, or an `Account`'s token.** Server-issued stream and subtitle URLs embed the access token (`ApiKey=`). `Account.toString()` and `PlaybackPlan.toString()` are already safe.
- **Main thread:** `graph.playback`, `graph.downloads`, `graph.downloadEnvironment` must be used from the main thread (Media3 rule). Stores and repositories are safe from any coroutine.
- **Do not edit the build** unless a new library is truly needed — every dependency for all three stages is already declared.

---

## 2. Package map

```
app.aafin
├── AAFinApp            Application; AAFinApp.graph(context)
├── AppGraph            lazy singletons (see §3)
├── car/                AAFinCarAppService, AAFinSession, CarEnv (CarBackend…), CarLogic, CarProfileSession, Posters
│   ├── render/         VideoSurfaceRenderer, VideoPresentation                 ← video on the car surface
│   └── screens/        CarScreen (base), Home, Library, Series, EntryList, Downloads, Search, Accounts, Player, Options
├── phone/              MainActivity (+ AAFinTheme), placeholder               ← Stage 3 builds here
└── data/
    ├── Ticks, JsonDataStore (AppJson, JsonSerializer, jsonDataStore)
    ├── accounts/       Account, AccountsState, AccountStore
    ├── jellyfin/       ServerUrl, AuthGateway(+Sdk), AuthRepository, QuickConnectState,
    │                   JellyfinClients, JellyfinRepository, JellyfinUrls, Models, JellyfinException
    ├── playback/       QualityTier, DeviceProfileBuilder, PlaybackPlan, PlaybackDecision,
    │                   PlaybackResolver, MediaItems, AuthHeaderInterceptor, ProgressReporter,
    │                   PositionSyncWorker, PlaybackController
    ├── downloads/      DownloadMetadata(+Store), DownloadEnvironment, DownloadRepo, AAFinDownloadService
    └── profiles/       CarProfile, CarProfileStore
```

---

## 3. `AppGraph` — what to ask for

| Property | Type | Use it for |
|---|---|---|
| `accountStore` | `AccountStore` | list / switch / remove accounts, `activeAccount` flow |
| `auth` | `AuthRepository` | find server, Quick Connect, password sign-in |
| `jellyfin` | `JellyfinRepository` | browse, search, artwork URLs |
| `carProfiles` | `CarProfileStore` | per-car quality, default quality |
| `playback` | `PlaybackController` | the single player + its state |
| `downloads` | `DownloadRepo` | enqueue / list / delete downloads |
| `appScope` | `CoroutineScope` | process-lifetime, main dispatcher |
| `playbackResolver`, `progressReporter`, `downloadEnvironment`, `jellyfinClients`, `mediaHttpClient`, `codecCapabilities` | — | plumbing; UI should not need them |

---

## 4. Interfaces, by job

### 4.1 Accounts — `AccountStore`

- `activeAccount: Flow<Account?>` — **null = signed out**. Car Home screen should collect this.
- `accounts: Flow<List<Account>>`, `suspend active()`, `suspend all()`, `suspend get(id)`
- `suspend setActive(id)`, `suspend remove(id)`, `suspend upsert(account, makeActive)`
- `suspend deviceId()` — stable per install (random UUID, created on first use).
- `Account(id = "serverId:userId", serverUrl, serverName, userId, userName, accessToken)`

### 4.2 Sign-in — `AuthRepository`

1. `suspend findServer(input: String): ServerInfo` — normalises what the user typed and checks `/System/Info/Public`.
   - `ServerInfo.compatibility` → `SUPPORTED` / `TOO_OLD` / `NEWER_UNTESTED` / `UNKNOWN`. **Show a warning for anything but `SUPPORTED`** (see §7 risk 1).
   - `ServerInfo.isSecure` → false for plain HTTP. Show a warning.
2. **Quick Connect:** `quickConnect(server): Flow<QuickConnectState>` — cold flow. **Collect to start, cancel collection to abandon.**
   - `Starting` → `AwaitingApproval(code)` → `Authenticating` → `Success(account)`
   - or terminal `TimedOut` (5 min default) / `Failed(error)` (`QUICK_CONNECT_DISABLED` → offer password).
   - Polls every 4 s. Network blips while polling are tolerated.
3. **Password:** `suspend signInWithPassword(server, username, password): Account`
4. Success **saves the account and makes it active** — nothing else to do.

### 4.3 Browsing — `JellyfinRepository` (all `suspend`, all for the active account)

| Function | Returns | Notes |
|---|---|---|
| `libraries()` | `List<Library>` | films / TV / collections / mixed only |
| `libraryItems(library, startIndex, limit)` | `Page<MediaEntry>` | sorted by name; `page.hasMore`, `page.nextStartIndex` |
| `children(parentId, startIndex, limit)` | `Page<MediaEntry>` | inside a collection (box set) or folder |
| `resumeItems(limit)` | `List<MediaEntry>` | Continue watching |
| `nextUp(limit)` | `List<MediaEntry>` | Next up |
| `seasons(seriesId)` | `List<MediaEntry>` | |
| `episodes(seriesId, seasonId?)` | `List<MediaEntry>` | |
| `search(query, limit)` | `List<MediaEntry>` | films, series, episodes |
| `item(itemId)` | `MediaEntry` | fresh resume position |
| `posterUrl(entry, maxWidth)` | `String?` | falls back to the series poster for episodes |
| `imageUrl(itemId, kind, maxWidth, tag)` | `String?` | `ImageKind.PRIMARY / BACKDROP / THUMB` |

- `MediaEntry` helpers: `displayTitle` ("Series · S2:E5 · Name"), `episodeLabel`, `kind.isPlayable`, `resumePositionMs`, `playedPercentage`, `canDownload`.
- **Image URLs need no auth** — hand them straight to Coil. Car: keep `maxWidth ≤ 256`.

### 4.4 Playback — `PlaybackController` (`graph.playback`, main thread)

- **`player: ExoPlayer`** — created on first access. Stage 2: `playerView.player = graph.playback.player`.
- **`state: StateFlow<PlaybackState>`** — `item`, `title`, `positionMs`, `durationMs`, `isPlaying`, `isBuffering`, `isLoading`, `isEnded`, `error: PlaybackError?`, `playMethod`, `tier`, `audioTracks`, `subtitleTracks`, `audioStreamIndex`, `subtitleStreamIndex`, `isOffline`. Position refreshes once a second.
- **Start:** `suspend play(entry, tier, startPositionMs = entry.resumePositionMs): Boolean`
  - Plays a **completed download if one exists**, else streams within `tier`.
  - Returns false and sets `state.error` on failure (does not throw).
- **Transport:** `pause()`, `resume()`, `togglePlayPause()`, `seekBy(±ms)`, `seekTo(ms)`, `stop()`.
- **Options (each `suspend`, each throws `JellyfinException` and leaves playback untouched on failure):**
  - `selectAudio(streamIndex)` — index from `state.audioTracks`.
  - `selectSubtitle(streamIndex?)` — null = off. `StreamTrack.delivery` tells the UI the cost: `SIDE_LOADED` = instant, `BURN_IN` = stream restarts.
  - `setQuality(tier)` — re-resolves and carries on from the same position.
  - `retry()` — after an error.
- **Automatic behaviour (already done, do not duplicate):**
  - Audio focus, pause on unplug, wake lock, `MediaSession` (no `MediaSessionService`).
  - Progress reports: start, every 10 s, on pause/resume/seek, on stop, on end.
  - **Decoder failure on direct play → one automatic retry as a transcode.**
  - Reaching the end reports the full length (server marks it watched).
- **Stage 2 must call `stop()` when the car session is destroyed.**

### 4.5 Quality — `QualityTier`

`P480_2M`, `P720_4M` (**`DEFAULT`**), `P1080_8M`, `P1080_15M`, `ORIGINAL`. Each has `label`, `maxBitrate`, `maxWidth`.

### 4.6 Car profiles — `CarProfileStore`

- `suspend getOrCreate(key, suggestedName): CarProfile` — call when the car is identified.
- Keys: `CarProfileStore.keyForModel(manufacturer, model, year)` (null if the car reports nothing) → else `keyForScreen(widthPx, heightPx, dpi)`.
- `profile(key): Flow<CarProfile?>`, `profiles: Flow<List<CarProfile>>`, `rename`, `setQuality`, `remove`.
- `defaultQuality: Flow<QualityTier>` / `setDefaultQuality` — the Settings screen's "default quality"; applied to newly seen cars.

### 4.7 Downloads — `DownloadRepo` (`graph.downloads`, main thread)

- `downloads: StateFlow<List<DownloadedItem>>` — `metadata`, `status` (`QUEUED / DOWNLOADING / PAUSED / COMPLETED / FAILED / REMOVING`), `percent`, `bytesDownloaded`, `posterFile`, `entry` (with the locally saved resume position).
- `suspend enqueue(entry, DownloadQuality.ORIGINAL | P720)` — throws `JellyfinException` / `IOException`.
- `suspend delete(itemId)`, `suspend find(itemId)`, `suspend storageUsedBytes()`.
- **`resumePending()` — Stage 3 must call this in `MainActivity.onStart`** (restarts unfinished downloads; there is no background scheduler by design).
- **Stage 3 must request `POST_NOTIFICATIONS`** before the first download (Android 13+).
- `metadata.playableOnThisPhone == false` → warn: an Original download may not play on this phone.
- Offline browsing in the car = `downloads.value.filter { it.status == COMPLETED }`, then `playback.play(item.entry, tier)`.
- Disk layout: `files/downloads/media/` (Media3 cache) and `files/downloads/items/<itemId>/` (`metadata.json`, `poster.jpg`, `subtitle-N.srt`).

---

## 5. How playback is resolved

1. `PlaybackResolver.resolveOnline` → `POST /Items/{id}/PlaybackInfo` with the `DeviceProfile` from `DeviceProfileBuilder` and `MaxStreamingBitrate` from the tier.
   - `planOnline(request)` is the same without building a Media3 item (used by downloads and JVM tests).
2. `PlaybackDecision.decide` (pure, unit-tested):
   - `SupportsDirectPlay` → `/Videos/{id}/stream?static=true&mediaSourceId=…` (**DIRECT_PLAY**)
   - else `TranscodingUrl` → HLS (**DIRECT_STREAM** if the server is only remuxing, else **TRANSCODE**)
   - else error `NO_COMPATIBLE_STREAM`.
3. `MediaItems.online` builds the Media3 `MediaItem` (+ a `SubtitleConfiguration` per text subtitle, id `aafin-sub-<index>`).
4. Auth: `AuthHeaderInterceptor` adds `Authorization: MediaBrowser …` to media requests **only when the request goes to a stored account's server**.

**Device profile**

- Direct play: `mp4,m4v,mov,mkv,webm,ts,mpegts`; h264/hevc/vp9/av1 and aac/mp3/opus/flac/vorbis/ac3/eac3 **only if `MediaCodecList` has a decoder**.
- Transcode: **HLS, MPEG-TS segments, h264 + stereo aac** (same as the official Android TV client).
- Caps: bitrate from the tier; width via a `CodecProfile` condition (`Width ≤ maxWidth`); h264 limited to 8-bit.
- Subtitles: `srt/subrip/vtt/webvtt/ass/ssa` → External (side-loaded); `pgs/pgssub/dvdsub/dvbsub` → Encode (burned in).

**Progress reports** (`ProgressReporter`)

- Live: `/Sessions/Playing`, `/Progress`, `/Stopped`.
- A failed progress/stop report is queued in **WorkManager** (unique per account + item, newest wins, needs network).
- A queued report is replayed as a **stop report with no `PlaySessionId`** — so it can never kill a transcode that started later. A later successful live report cancels the stale queued one.
- Offline playback: no live reports; position is queued on stop and also saved locally in the download metadata.

---

## 6. Versions and why

**Hard constraints:** Gradle 8.11.1, JDK 17, **compileSdk 35** (android-36 not installed). Every AAR's `minCompileSdk` was read from its metadata; `checkDebugAarMetadata` passes.

| Library | Version | Why this one |
|---|---|---|
| AGP | 8.10.1 | newest line that runs on Gradle 8.11.1 (8.11+ needs Gradle 8.13) |
| Kotlin + Compose compiler plugin + serialization plugin | 2.2.21 | matches the Jellyfin SDK (built with 2.2.10); known-good with AGP 8.10 |
| Jellyfin SDK `jellyfin-core` | **1.8.12** | newest for **Jellyfin 10.11**; 1.9.x needs server 12 **and** Kotlin 2.4 |
| Media3 | **1.9.2** | newest with `minCompileSdk` 35 (1.10+ needs 36) |
| Car App Library `app`, `app-projected`, `app-testing` | 1.7.0 | newest stable (1.8 is still rc) |
| Compose BOM | 2025.12.01 | Compose 1.10.0 / Material3 1.4.0, `minCompileSdk` 35 |
| core-ktx / activity-compose | 1.16.0 / 1.10.1 | newest with `minCompileSdk` 35 (next ones need 36) |
| lifecycle / navigation-compose | 2.9.4 / 2.9.7 | compile against 35 |
| DataStore / WorkManager | 1.1.7 / 2.10.5 | stable, compile against 35 |
| OkHttp | 5.3.2 | the version the Jellyfin SDK depends on (Media3's OkHttp data source runs on it) |
| Coil | 3.3.0 (`coil-compose`, `coil-network-okhttp`) | Kotlin 2.2 compatible |
| coroutines / serialization-json | 1.10.2 / 1.9.0 | what the Jellyfin SDK depends on |
| SLF4J | `slf4j-nop` 2.0.17 | required at run time by the Jellyfin SDK's logging (§7.10) |
| Tests | JUnit 4.13.2, coroutines-test, Turbine 1.2.1, OkHttp MockWebServer 5.3.2, Robolectric 4.16.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0 | Robolectric + `app-testing` are there for Stage 2 screen tests; runner + ext-junit for the on-device smoke test |

- **Release:** R8/minify **off** (v1), signed from `keystore.properties` (git-ignored). Keystore: `C:\Users\Ben\.android-keys\aafin-release.jks`, alias `aafin`.
- **To move to newer libraries later:** install platform android-36 and a newer Gradle first.

---

## 7. Surprises found while verifying APIs

1. **Jellyfin jumped from 10.11 to 12.x**, and SDK 1.9 says it is "no longer compatible with 10.11 and earlier". 1.8.12 (10.11) was chosen because it is the only line that fits this toolchain. The SDK's JSON is lenient (`ignoreUnknownKeys`, unknown enum values coerced), and AAFin only uses long-standing endpoints.
2. **Server-issued URLs contain the token** (`TranscodingUrl`, subtitle `DeliveryUrl` → `ApiKey=`). Hence the no-logging rule.
3. **A stop report with a `PlaySessionId` kills the transcode** (server source, `PlaystateController`). That shaped the queued-report design.
4. **The start position is not sent to the server.** HLS playlists cover the whole runtime, so the player seeks instead (`player.setMediaItem(item, positionMs)`). Avoids offset bugs.
5. **`SubtitleStreamIndex = -1` is always sent when subtitles are off.** Leaving it unset lets the server pick one, which can silently force a burn-in transcode.
6. **Media3 marks most of what we use `@UnstableApi`** — classes are annotated `@OptIn(UnstableApi::class)`.
7. **Media3 prefixes side-loaded subtitle track ids** (`"1:aafin-sub-3"`) when merging sources — the controller matches on the suffix.
8. **DataStore's default file storage cannot overwrite on Windows** (`File.renameTo`). Unit tests therefore use the Okio storage with the same serializer (`testing/TestDataStores.kt`). Production on Android is unaffected.
9. **Gradle 8.11.1 on Windows occasionally fails with "Could not move temporary workspace"** on a cold cache. Re-running the same command fixes it.
10. **The Jellyfin SDK crashes on its first HTTP call without SLF4J.** It logs through kotlin-logging, whose Android build defaults to SLF4J but does not bring it in (`NoClassDefFoundError: org.slf4j.LoggerFactory`). Found by the on-device smoke test. Fixed with `org.slf4j:slf4j-nop` — which also keeps the SDK's request URLs (Quick Connect secrets, search terms) out of logcat. **Do not remove that dependency.**
11. **Car manifest names were checked against the library's own constants** (`androidx.car.app.CarAppService`, `androidx.car.app.category.NAVIGATION`, `androidx.car.app.minCarApiLevel`, `androidx.car.app.NAVIGATION_TEMPLATES`, `androidx.car.app.ACCESS_SURFACE`).

---

## 8. Deviations from SPEC (and why)

| SPEC | What was built | Why |
|---|---|---|
| "POST PlaybackInfo … start position" | start position applied by the player, not sent | §7.4 |
| `DownloadService` with scheduler implied | **no scheduler**; `resumePending()` on app open | Android 12+ blocks starting a foreground service from a background job |
| Cleartext not mentioned | `usesCleartextTraffic="true"`; HTTP fallback **only** for local addresses when no scheme is typed | LAN servers are often `http://192.168.x.x:8096`; public hostnames never downgrade |
| Audio transcode target unspecified | stereo AAC | car speakers; keeps the 4 Mbps budget for picture |
| — | extra: `ServerCompatibility`, automatic transcode fallback on decoder errors, local resume position for downloads, `data_extraction_rules.xml` | robustness / privacy |

---

## 9. Open risks

**For everyone**

1. **Server version.** Untested against the real server (no credentials in Stage 1). If it runs **Jellyfin 12.x**, the 1.8.12 SDK is outside its supported range — basic calls should still work, but check sign-in, browse and PlaybackInfo first. Fix if needed: SDK 1.9.x → needs Kotlin 2.4 → probably a newer Gradle/AGP.
2. **Nothing has yet played a real stream.** The SDK calls are tested against a fake server with handcrafted (Jellyfin-shaped) JSON, and the player/download objects are created on a real Android runtime — but ExoPlayer, HLS and downloads have not run end-to-end against a real server.
3. **Two users of the same server on one phone:** media requests use the active account's token. Fine unless the two users have different library access.

**Stage 2 (car)** — see §11.9 for the full, current list.

4. **Rendering core:** now proven on an Android 14 emulator (§11.4), **not yet on a real head unit or the DHU**.
5. **NAVIGATION category without navigating.** `navigationStarted()` is never called. Android Auto may still treat AAFin as the "default navigation app" after launch (it is the last nav app opened).
6. **Template restrictions** are handled by design (§11.3) but the host's exact counting is not public.
7. **`MediaSession` in the car:** steering-wheel routing with an active session but no media browser is unverified.
8. **HDR / Dolby Vision** sources that direct-play may look wrong on the car surface. The decoder-error fallback will not catch a picture that merely looks wrong — consider a "force transcode" option.

**Stage 3 (phone)**

9. **720p downloads are HLS transcodes fetched segment by segment.** Speed is limited by the server's transcoder; a paused download relies on the server restarting the transcode at the right segment. Signing out and back in changes the token and **breaks unfinished 720p downloads** (delete and re-queue).
10. **Original downloads need the "allow media download" permission** on the Jellyfin user (`/Items/{id}/Download`). `MediaEntry.canDownload` tells the UI.
11. **Android 15+ limits `dataSync` foreground services to 6 hours a day.** Very large queues may stop and need the app reopening.
12. **Material icons:** only `material-icons-core` is bundled (the extended set adds ~10 MB with R8 off).

---

## 10. Tests

`gradlew.bat testDebugUnitTest` — JVM only, no emulator.

| File | Covers |
|---|---|
| `ServerUrlTest` | URL normalisation, HTTPS/HTTP rule, server compatibility |
| `QualityAndTicksTest` | tier → bitrate/width, ticks ↔ ms, device profile |
| `PlaybackDecisionTest` | PlaybackInfo → direct play / direct stream / transcode, subtitles, errors |
| `AuthRepositoryTest` | Quick Connect state machine (success, disabled, timeout, cancel, blips), password, `findServer` |
| `StoresTest` | car profiles get-or-create, accounts, download metadata JSON |
| `ProgressReporterTest` | report queueing, URL building, auth-header host rule |
| `JellyfinSdkIntegrationTest` | the **real SDK against a local fake server** (MockWebServer): request paths, auth header, request bodies (device profile, ticks), JSON decoding into app models |

| `car/CarLogicTest` | template budget, paging, row text, series layout, next episode, car key, seek gesture, surface insets |
| `car/CarProfileSessionTest` | car detection: model → screen → unknown, timeouts, quality read/write |
| `car/CarBrowseScreensTest` | **Robolectric + `app-testing`**: Home (signed out, error, overflow), Library (empty, paging, error), Series, Downloads, Search, Accounts, budget restart |
| `car/CarPlayerScreensTest` | player strips, error/retry, stop on exit, next-episode countdown, options and choices |

- Car screen tests run against `FakeBackend` (`car/CarFakes.kt`) — no server, no head unit.
- **166 unit tests** in total (83 from Stage 1 + 83 for the car).

**On-device tests:** `gradlew.bat connectedDebugAndroidTest` — `GraphSmokeTest` (Stage 1) and **`VideoSurfaceRendererTest`** (§11.4).

- Creates every `AppGraph` singleton on a real Android runtime: DataStore files, ExoPlayer + `MediaSession`, download cache/manager, WorkManager queue, the SDK's HTTP stack.
- Needs no Jellyfin server. Leaves real accounts alone.
- **It installs the debug build**, which is signed differently from the release build — on a phone that has the release APK it will refuse to install rather than overwrite. Use an emulator, or uninstall first.

---

## 11. Car app (Stage 2)

### 11.1 How it is put together

- `AAFinCarAppService` → `AAFinSession` (one per car connection).
- **On connect** the session:
  1. builds a `CarEnv` — the one object every screen is given (`backend`, `limits`, `posters`, `quality`, `overlay`);
  2. registers `VideoSurfaceRenderer` with `AppManager.setSurfaceCallback`;
  3. starts `CarProfileSession` (which car is this?);
  4. shows `HomeScreen`.
- **On disconnect:** `graph.playback.stop()` (reports "stopped" to Jellyfin) and `renderer.release()`.
- **Screens never touch `AppGraph`.** They use `CarBackend` (`car/CarEnv.kt`), a thin interface over Stage 1; `GraphCarBackend` is the real one, `FakeBackend` the test one.
- **Never called:** `NavigationManager.navigationStarted()`. **Not declared:** any `MediaBrowserService` / `MediaSessionService`.

### 11.2 Screen flow

| Screen | Template | Opens |
|---|---|---|
| `HomeScreen` | `ListTemplate` (or `MessageTemplate` when signed out / server unreachable) | Continue watching, Next up, each library, Downloads, Search, Switch account (only with 2+ accounts) |
| `EntryListScreen` | `ListTemplate` with posters | Player |
| `LibraryScreen` | `GridTemplate` with posters | films → Player; series → Series; collections → Library |
| `SeriesScreen` | `ListTemplate`: sections per season, **or** a season list, **or** a run of episodes | Player |
| `DownloadsScreen` | `ListTemplate` (no network needed) | Player |
| `SearchScreen` | `SearchTemplate` | Player / Series |
| `AccountsScreen` | `ListTemplate` (radio list) | back to Home |
| `PlayerScreen` | `NavigationTemplate` (or `MessageTemplate` on a playback error) | Options |
| `PlayerOptionsScreen` → `ChoiceScreen` | `ListTemplate` → radio list | Audio / Subtitles (incl. Off) / Quality |

**Player buttons** (limits checked in the library's `ActionsConstraints`):

- **Action strip** — max 4, titles or icons: **Back, −10 s, Play/Pause, +30 s**.
- **Map action strip** — max 4, icons only, car API 2+: **Options** and the standard **pan** action.
  - The pan action is not decoration: without it the host does **not** send taps or drags on the picture to the app.
- Car API 1 hosts (no map strip): one strip of Back, Play/Pause, +30 s, Options.

**After an episode ends:** "Up next in 8 s · S1 E4 · …" on the picture, then it plays. Back cancels. After a film or the last episode the player closes.
The next episode comes from the server's episode list, or from the downloads when the server cannot be reached.

**Quality:** chosen in Options → saved to **this car's profile** (`CarProfileStore.setQuality`) → `PlaybackController.setQuality` re-resolves at the same position.

### 11.3 Android Auto's limits, and how each is handled

| Limit | Handling |
|---|---|
| **5 templates per task; the 5th must be a message or the player** | `CarScreen` (base class) knows which **step** each screen is and counts a step whenever what it shows changes in a way the host would not call a refresh. `FlowBudget` (pure, unit-tested) decides what may be offered. |
| Going deeper than the budget | Never offered: a "More…" page or Search only appears if what it leads to still fits. |
| Budget used up anyway (e.g. error → retry → content adds steps) | **Flow restart:** `FlowResetScreen` shows a `NavigationTemplate` for an instant (that template resets the host's count), then the stack is replaced by a fresh Home at step 2. No crash, one extra tap. |
| Home must leave room for Library → Series → Player | Home restarts the flow itself if it would otherwise pass **step 2**. |
| Rows per list / items per grid (`ConstraintManager.getContentLimit`, often **6**) | Read on every build (`HostContentLimits`); capped at 100 rows / 48 posters; falls back to 6. |
| More items than fit | Last slot becomes **"More…"** (opens the next page as a new screen, so Back returns to the previous page). Title shows "Films · 1–5 of 300". |
| Series longer than one screen | Season list → one screen per season; a long season **starts at the next episode to watch**. |
| Template refresh quota | Posters arrive in batches: at most one redraw every 750 ms (`PosterBoard`). The player template is redrawn **only** when the play/pause button or the error state changes — position, title and clock are drawn on the surface instead. |
| Images are copied to the head unit | Posters are ≤ 144 × 216 px, RGB 565 (~60 KB each). |
| Old hosts | Every newer API is guarded by `carContext.carAppApiLevel` (map strip/pan ≥ 2, content limits ≥ 2, car model ≥ 3). Touch callbacks are only ever *called* by hosts that support them. |

**Error handling**

- Every load failure is a `JellyfinException` → `MessageTemplate` with its `userMessage` and **Retry**.
- While a retry runs, the **same** message stays up with a spinner (a different message would cost a step).
- A refresh that fails over content already on screen keeps the content and shows a toast.
- Home's error and signed-out messages also offer **Downloads**, so offline playback is always reachable.

### 11.4 Video rendering — the design and the evidence

**Design** (`car/render/`):

1. `onSurfaceAvailable` → `DisplayManager.createVirtualDisplay(name, w, h, dpi, carSurface, VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY)`.
2. `VideoPresentation(carContext, virtualDisplay.display).show()` — a normal window whose layout (`res/layout/car_presentation.xml`) holds:
   - a Media3 **`PlayerView`**: `use_controller=false`, `resize_mode=fit`, subtitles, buffering spinner;
   - the **idle screen** (logo + "AAFin") whenever nothing is loaded;
   - the **overlay** (title, position / progress bar / duration, clock) and a one-line **notice** (loading, errors, "Up next…", seek preview).
3. `onSurfaceDestroyed` → detach the player, dismiss the window, release the display, release the `Surface`.

**Why not `ExoPlayer.setVideoSurface(carSurface)`?** It stretches the picture to the screen's shape and leaves nowhere to draw subtitles or an overlay.

**Why this is a proven pattern, not a guess**

| Evidence | What it shows |
|---|---|
| Google's Car App Library guide, *Draw maps → "Use a virtual display to render content"* (developer.android.com/training/cars/apps/library/draw-maps) | The exact `VirtualDisplay` + `Presentation` recipe, with `carContext` as the Presentation's context |
| Google's **Navigation SDK for Android Auto** guide (developers.google.com/maps/documentation/navigation/android-sdk/android-auto) | The same recipe in a shipping product, with `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` — the flag used here |
| **`VideoSurfaceRendererTest`** (on-device, Android 14 emulator, real ExoPlayer, real H.264 file) | See below |

`VideoSurfaceRendererTest` hands the renderer a surface exactly as Android Auto would (backed by an `ImageReader`, so the test reads the pixels back) and checks:

- the idle logo is drawn;
- a 4:3 video on a 5:3 surface is **letterboxed, not stretched** (green in the middle, black bars at the sides);
- the picture moves inside the visible area and grows back when the area does;
- destroy → recreate at another size → replace without destroy → late "destroyed" for the old surface → release: all clean, playback carries on;
- **it keeps laying out and drawing with the phone's screen off**;
- taps and drags drive play/pause and seek.

**Fermata Auto** was checked and is **not** a precedent: it uses the legacy `com.google.android.apps.auto.sdk.CarActivity` projection SDK, not the Car App Library.

**`SurfaceView` or `TextureView`?** → **`SurfaceView`** (`app:surface_type="surface_view"`).

- It is ExoPlayer's recommended output and the one the device test proves is composited into the virtual display.
- Its video layer is composed by the system, not drawn by the app's window, so it is the cheaper path and the one least affected by anything that slows window drawing.
- **Fallback:** if a head unit ever gives sound but no picture, change that one attribute to `texture_view`. Nothing else changes.

**Visible area**

- `onVisibleAreaChanged` → the content is padded so the picture, logo and overlay sit inside the area the host is not drawing over (`SurfaceInsets`, unit-tested).
- Falls back to the stable area if the visible area is unknown; ignores an area smaller than a quarter of the surface in either direction.
- Consequence: the picture is full-size while the host's strips are hidden and shrinks slightly while they are showing.

### 11.5 Threading

- **Everything in `car/` runs on the main thread.** The Car App Library delivers screen and surface callbacks there; windows, the player and `graph.playback` / `graph.downloads` must be used there.
- Coroutines use `lifecycleScope` of the session or screen (`Dispatchers.Main.immediate`), so they are cancelled when the car disconnects or the screen is popped.
- Network and disk work suspends inside Stage 1 (OkHttp, DataStore, Coil) — nothing in `car/` blocks.

### 11.6 Surface callbacks in any order

| Situation | Behaviour |
|---|---|
| Area change before any surface | Remembered, applied when the surface arrives |
| Second "available" with no "destroyed" (size or DPI change) | Old window and display torn down, new ones built |
| "Destroyed" for a surface that is not the current one | Its `Surface` copy is released; the current window stays |
| "Destroyed" twice / before any "available" | No-op |
| Surface invalid or zero-sized | Skipped; wait for the next one |
| Window creation throws (surface vanished mid-setup) | Logged, cleaned up, wait for the next one |
| Android dismisses the Presentation itself (display changed) | Rebuilt on the same display, at most 3 times |
| Anything after the session ended | Ignored; any `Surface` passed in is released |

### 11.7 Touch

- **Tap** (`onClick`, car API 5+): overlay hidden → show it. Overlay showing → play/pause. The overlay stays up while paused.
- **Sideways drag** (`onScroll`, car API 2+): seeks — a full screen width is 3 minutes; a live "+0:45" preview shows; applied when the finger stops (or on `onFling`). Mostly-vertical drags are ignored.
- Nothing happens unless a video is loaded. All of it is optional: the strip buttons do the same jobs.

### 11.8 Car profiles

- `CarProfileSession` runs once per connection:
  1. `CarInfo.fetchModel` (car API 3+, 2 s timeout) → key `model:make|model|year`;
  2. else the first surface (3 s timeout) → key `screen:WxH@dpi`;
  3. else `screen:unknown`.
- `CarProfileStore.getOrCreate(key, name)` → the profile's quality tier is read **each time something is played**, so a change on the phone applies to the next item.
- **Wired and wireless are identical:** both the model and the surface come from the head unit, not the transport.

### 11.9 Known risks (check these on the DHU and the car first)

1. **Not yet run on a real Android Auto host.** Unit and device tests cover the app's side; the host's side (surface delivery, strips, template counting) is only as documented. See `docs/DHU-TEST-PLAN.md`.
2. **Phone screen off:** proven on Android 14 (emulator). The owner's phone runs Android 16 — check that the picture keeps moving with the phone locked.
3. **Template step counting is the host's.** If the host is stricter than documented, the symptom is an Android Auto error screen naming the step limit; the log line `Template budget used up` shows when the app's own guard fires.
4. **Drag direction** follows Android's gesture convention (`distanceX` positive when the finger moves left). If dragging right seeks backwards on a real head unit, flip the sign in `VideoSurfaceRenderer.onScroll`.
5. **Car model** may be reported on some connections and not others → two profiles for one car. Rename/delete on the phone (Stage 3).
6. **Small content limits** (6): a long season only shows from the next unwatched episode onwards; earlier ones are reachable via Search.
7. **The player and media session are created the first time something is played** and live until the process ends (Stage 1 design).
8. Items 5, 7 and 8 of §9 still stand.

### 11.10 Stage 1 changes made in Stage 2

| Change | Why |
|---|---|
| `app/build.gradle.kts`: `unitTests.isIncludeAndroidResources = true` | Robolectric needs the app's resources for the car screen tests |
| `car/PlaceholderScreen.kt` and `car_placeholder_message` removed | Replaced by the real screens |
| `AAFinSession` rewritten | It was a stub |
| New resources: `layout/car_presentation.xml`, `drawable/ic_car_*.xml`, `drawable/car_*_background.xml` | The video window and the car icons |
| New test asset: `androidTest/assets/green-4x3.mp4` (5 KB) | The device test's video |

**No data-layer code was changed.**

### 11.11 Logging

| Tag | What |
|---|---|
| `AAFin/Car` | session start/end, car profile, account changes, load failures, budget restarts |
| `AAFin/Surface` | surface available/destroyed with size, visible/stable area, window shown, taps, drags |
| `AAFin/Playback` | what is asked to play and at which quality, next episode, option changes |
| `AAFin.Playback` | Stage 1's `PlaybackController` (player errors by code) |

`adb logcat -s AAFin/Car AAFin/Surface AAFin/Playback AAFin.Playback`

**Never logged:** tokens, stream URLs, poster URLs, search text.
