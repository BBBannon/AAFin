# AAFin — Architecture (Stages 1–3: foundation, car app, phone app)

**What exists now:** build, signing, manifest, logo, the whole data layer (Stage 1), the **Android Auto car app** (Stage 2, §11) and the **phone app** (Stage 3, §12).

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
├── phone/              MainActivity, PhoneBackend, PhoneLogic, view models     ← phone app (§12)
│   └── ui/             PhoneApp (navigation), Theme, Components, and the screens
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
| `phoneSettings` | `PhoneSettingsStore` | phone-only settings (setup checklist seen) |
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
   - `ServerInfo.compatibility` → `SUPPORTED` (**10.10 to 12.x**) / `TOO_OLD` (below 10.10) / `NEWER_UNTESTED` (13+) / `UNKNOWN`. **Show a warning for anything but `SUPPORTED`** (see §7.1).
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
| `sourceInfo(itemId)` | `SourceInfo` | file size and bitrate, for download size estimates (Stage 3) |
| `posterUrl(entry, maxWidth)` | `String?` | falls back to the series poster for episodes |
| `imageUrl(itemId, kind, maxWidth, tag)` | `String?` | `ImageKind.PRIMARY / BACKDROP / THUMB` |

- `MediaEntry` helpers: `displayTitle` ("Series · S2:E5 · Name"), `episodeLabel`, `kind.isPlayable`, `resumePositionMs`, `playedPercentage`, `canDownload`.
- **Image URLs need no auth** — hand them straight to Coil. Car: keep `maxWidth ≤ 256`.

### 4.4 Playback — `PlaybackController` (`graph.playback`, main thread)

- **`player: ExoPlayer`** — created on first access. Stage 2: `playerView.player = graph.playback.player`.
- **`state: StateFlow<PlaybackState>`** — `item`, `title`, `positionMs`, `durationMs`, `isPlaying`, `playWhenReady` (what a play/pause button should show), `isBuffering`, `isLoading`, `isEnded`, `error: PlaybackError?`, `playMethod`, `tier`, `audioTracks`, `subtitleTracks`, `audioStreamIndex`, `subtitleStreamIndex`, `isOffline`. Position refreshes once a second.
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
- `markUsed(key)` — stamps `CarProfile.lastUsedAtMs`; the car session calls it on every connection ("Last used" on the phone).
- `defaultQuality: Flow<QualityTier>` / `setDefaultQuality` — the Settings screen's "default quality"; applied to newly seen cars.

### 4.7 Downloads — `DownloadRepo` (`graph.downloads`, main thread)

- `downloads: StateFlow<List<DownloadedItem>>` — `metadata`, `status` (`QUEUED / DOWNLOADING / PAUSED / COMPLETED / FAILED / REMOVING`), `percent`, `bytesDownloaded`, `posterFile`, `entry` (with the locally saved resume position).
- `suspend enqueue(entry, DownloadQuality.ORIGINAL | P720)` — throws `JellyfinException` / `IOException`.
  - An existing download of the same item is **discarded and replaced** (clean restart with the new address).
- `suspend delete(itemId)`, `suspend find(itemId)`, `suspend storageUsedBytes()`.
- `pause(itemId)` / `resume(itemId)` — Media3 stop reason; a paused download stays paused across restarts.
- `suspend retry(itemId)` — starts again from scratch with a **fresh address** from the server (for failed downloads).
- `suspend restartStale(): Int` — restarts the active account's unfinished 720p downloads whose address embeds an **old access token** (§12.6).
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
| Jellyfin SDK `jellyfin-core` | **1.8.12** | generated for **Jellyfin 10.11**; audited as wire-compatible with **12.4** for every call AAFin makes (§7.1). 1.9.x targets server 12 only and needs Kotlin 2.4.20 — which **does** run on this Gradle/AGP (fallback recipe in §7.1) |
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
| Tests | JUnit 4.13.2, coroutines-test, Turbine 1.2.1, OkHttp MockWebServer 5.3.2, Robolectric 4.16.1, androidx.test core/runner 1.7.0, ext-junit 1.3.0, Compose `ui-test-junit4` + `ui-test-manifest` (from the BOM) | Robolectric + `app-testing` are there for Stage 2 screen tests; runner + ext-junit for the on-device smoke test; the Compose test artefacts for the phone UI tests (Stage 3) |

- **Release:** R8/minify **off** (v1), signed from `keystore.properties` (git-ignored). Keystore: `C:\Users\Ben\.android-keys\aafin-release.jks`, alias `aafin`.
- **To move to newer libraries later:** install platform android-36 and a newer Gradle first.

---

## 7. Surprises found while verifying APIs

1. **Jellyfin jumped from 10.11 to 12.x** (the owner's server runs **12.4.0**), and SDK 1.9 says it is "no longer compatible with 10.11 and earlier".
   - **AAFin stays on SDK 1.8.12.** An audit of the 10.11.11 and 12.4 OpenAPI specs and server source found **every endpoint AAFin calls unchanged on the wire** (same routes, same bodies, `Authorization: MediaBrowser` header auth still accepted). The SDK's JSON is lenient (`ignoreUnknownKeys`, unknown enum values coerced). `ServerCompatibility` therefore treats **10.10–12.x as supported**.
   - **Fallback if a later 12.x breaks decoding** (the trigger would be sign-in failing on `UserDto`): move to SDK **1.9.4**. It needs **Kotlin 2.4.20**, coroutines/serialization 1.11.0, the three JVM modules `jellyfin-api`, `jellyfin-api-okhttp`, `jellyfin-model` instead of `jellyfin-core` (whose AAR demands compileSdk 37), and OkHttp pinned `strictly("5.3.2")`. **Gradle 8.11.1, AGP 8.10.1 and compileSdk 35 stay as they are** — proven with a scratch build. API accessors are renamed (`quickConnectApi`/`userApi` → `authenticationApi`, `itemsApi`/`userLibraryApi` → `libraryApi`, `tvShowsApi` → `showApi`, `playStateApi` → `sessionApi`, `userViewsApi` → `userViewApi`).
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
12. **The Jellyfin SDK reads the response body on the calling thread.** It waits for the headers without blocking, then calls `body.bytes()` wherever the coroutine resumes.
    - From the main thread (where the car and phone screens call from) that is a `NetworkOnMainThreadException` whenever the body has not already arrived — always possible over HTTP/1.1; HTTP/2 hides it.
    - Found in Stage 3 by running the release build against a local HTTP/1.1 stand-in server: the app crashed while polling Quick Connect.
    - **Fix:** `jellyfinCall` is now `suspend` and runs every SDK call on `Dispatchers.IO`.
    - Regression test: `MainThreadNetworkTest` (on-device; fails without the fix).

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

1. **Server version.** The real server runs **Jellyfin 12.4.0**; no signed-in call has been made against it yet. The audit (§7.1) says SDK 1.8.12 works unchanged, but check in this order: Quick Connect sign-in → libraries → an item → PlaybackInfo → progress report → download. Fix if needed: SDK 1.9.4 with Kotlin 2.4.20 — **no Gradle/AGP/compileSdk change** (recipe in §7.1).
2. **Nothing has yet played a real stream.** The SDK calls are tested against a fake server with handcrafted (Jellyfin-shaped) JSON, and the player/download objects are created on a real Android runtime — but ExoPlayer, HLS and downloads have not run end-to-end against a real server.
3. **Two users of the same server on one phone:** media requests use the active account's token. Fine unless the two users have different library access.

**Stage 2 (car)** — see §11.9 for the full, current list.

4. **Rendering core:** now proven on an Android 14 emulator (§11.4), **not yet on a real head unit or the DHU**.
5. **NAVIGATION category without navigating.** `navigationStarted()` is never called. Android Auto may still treat AAFin as the "default navigation app" after launch (it is the last nav app opened).
6. **Template restrictions** are handled by design (§11.3) but the host's exact counting is not public.
7. **`MediaSession` in the car:** steering-wheel routing with an active session but no media browser is unverified.
8. **HDR / Dolby Vision** sources that direct-play may look wrong on the car surface. The decoder-error fallback will not catch a picture that merely looks wrong — consider a "force transcode" option.

**Stage 3 (phone)**

9. **720p downloads are HLS transcodes fetched segment by segment.** Speed is limited by the server's transcoder; a paused download relies on the server restarting the transcode at the right segment. A new access token would strand unfinished 720p downloads — **now handled**: they are restarted automatically, and anything that still fails shows **Retry** (§12.6).
10. **Original downloads need the "allow media download" permission** on the Jellyfin user (`/Items/{id}/Download`). `MediaEntry.canDownload` tells the UI.
11. **Android 15+ limits `dataSync` foreground services to 6 hours a day.** Very large queues may stop and need the app reopening.
12. **Material icons:** only `material-icons-core` is bundled (the extended set adds ~10 MB with R8 off). The phone UI reuses the car's vector drawables for download / pause / play / film / TV / folder / search / tune, plus `ic_phone_car`.
13. More phone-specific risks: §12.9.

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
| `car/VideoSurfaceLifecycleTest` | **Robolectric**, the real renderer: surface available → destroyed (as a real host sends it) → available again; destroyed twice / first; nothing after release |

| `phone/PhoneLogicTest` | download size estimates, the Original / 720p options, formatting, car key wording, server warnings, stale-token detection |
| `phone/AccountViewModelsTest` | add-account flow (server check, **Quick Connect states**, timeout, disabled, cancel, password), app state, **account switching** and removal, stale-download messages |
| `phone/BrowseAndManageViewModelsTest` | **download choice** (720p default, Original permission, season, failures), paging, series, search, downloads manager (retry rules, storage), cars, settings |

- Car screen tests run against `FakeBackend` (`car/CarFakes.kt`) — no server, no head unit.
- Phone tests run against `FakePhone` (`src/sharedTest/…/phone/PhoneFakes.kt`, shared with the on-device tests).
- **230 unit tests** in total (84 data layer + 89 car + 57 phone).

**On-device tests:** `gradlew.bat connectedDebugAndroidTest` — `GraphSmokeTest` (Stage 1), **`VideoSurfaceRendererTest`** (§11.4), **`PhoneUiTest`** (§12.8) and **`MainThreadNetworkTest`** (§7.12) — 24 in total.

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
| Template refresh quota | Posters arrive in batches: at most one redraw every 750 ms (`PosterBoard`). The player template is redrawn **only** when the play/pause button or the error state changes (plus one repeat a second later, in case the host lost the first) — position, title and clock are drawn on the surface instead. |
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
- destroy → recreate at another size → replace without destroy → destroy → release: all clean, playback carries on;
- **it keeps laying out and drawing with the phone's screen off**;
- taps show and hide the overlay (never pause); drags seek.

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
| "Destroyed", whatever container comes with it | **Always** takes the window and display down and releases both `Surface` copies. The container cannot be matched to the one from "available": it crosses a process boundary (new `Surface` object) and the desktop head unit sends a different dpi (Stage 4 fix; `VideoSurfaceLifecycleTest`) |
| Host shows a list / grid / message over the player (e.g. Options) | The host may destroy the surface: the window goes, **sound carries on**; the next "available" (back on the player) rebuilds the window and re-attaches the player |
| "Destroyed" twice / before any "available" | No-op |
| Surface invalid or zero-sized | Skipped; wait for the next one |
| Window creation throws (surface vanished mid-setup) | Logged, cleaned up, wait for the next one |
| Android dismisses the Presentation itself (display changed) | Rebuilt on the same display, at most 3 times |
| Anything after the session ended | Ignored; any `Surface` passed in is released |

### 11.7 Touch

- **Tap** (`onClick`, car API 5+): shows the overlay, or hides it if it is showing. **A tap never plays or pauses** (Stage 4: taps aimed at the host's buttons landed on the picture and paused the video) — that is the strip button's job. The overlay stays up while paused.
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

---

## 12. Phone app (Stage 3)

### 12.1 What it is for

- **Accounts, browse-to-download, downloads, car profiles, settings.** Nothing plays on the phone — item screens say "Play from your car screen".
- Compose + Material 3, **dark only**, colours from the logo (#AA5CC3 → #00A4DC on #101018). UK English. Strings are inline Kotlin (single-language personal app, same as the car screens).
- Setup steps for a new phone: `docs/PHONE-SETUP.md` (the same words as the in-app checklist, `SetupSteps`).
- Screenshots of every main screen: `docs/screenshots/`.

### 12.2 How it is put together

```
MainActivity ── graphPhoneBackend(graph) ──► PhoneApp(backend)        ui/PhoneApp.kt
                                                │
   loading → SetupScreen (first run) → AddAccountScreen (signed out) → MainScreen
                                                                         │
                                         Scaffold: top bar (title, Back, account switcher)
                                                   bottom bar (Browse · Downloads · Cars · Settings)
                                                   NavHost (type-safe routes, navigation-compose)
```

- **Screens never touch `AppGraph`.** They get a `PhoneBackend` (`phone/PhoneBackend.kt`):
  - the **real** `AccountStore`, `AuthRepository`, `CarProfileStore`, `PhoneSettingsStore` (tests give them in-memory storage and a fake `AuthGateway`);
  - two small interfaces, `PhoneLibrary` (browse) and `PhoneDownloads`, so the SDK and Media3 can be faked.
- **One view model per screen**, created with `viewModel { … }` and scoped to its navigation entry. State is a `StateFlow`; one-off messages are a `Flow<String>` shown in the shared snackbar.
- **Pure logic lives in `PhoneLogic.kt`** (no Android): size estimates, download options, status wording, formatting, server warnings.
- **Root state is data-driven** (`AppViewModel`): setup not seen → checklist; no active account → add account; else the tabs. Signing in, or removing the last account, switches screens by itself.

| Route | Screen | View model |
|---|---|---|
| `BrowseRoute` (start) | libraries + Search button | `LibrariesViewModel` |
| `GridRoute(id, name, libraryType?)` | poster grid: a library, or inside a collection/folder | `GridViewModel` (pages of 60; loads more as the end scrolls into view) |
| `SeriesRoute` | season chips, "Download season", episodes | `SeriesViewModel` |
| `ItemRoute` | one film: poster, details, Download | `ItemViewModel` |
| `SearchRoute` | search as you type (400 ms pause) | `SearchViewModel` |
| `DownloadsRoute` | downloads manager | `DownloadsViewModel` |
| `CarsRoute` | car profiles | `CarsViewModel` |
| `SettingsRoute` → `AccountsRoute`, `AddAccountRoute`, `SetupGuideRoute`, `LicencesRoute` | settings and friends | `SettingsViewModel`, `AddAccountViewModel` |

- **Tabs:** Browse is home; Back from another tab returns to it. Tapping a tab goes to that tab's first screen.
- **Switching account** (top-bar menu, or after adding one) resets navigation to a fresh Browse — a different account is a different library.

### 12.3 Add account

1. **Server:** whatever is typed goes to `AuthRepository.findServer` (normalise + `/System/Info/Public`). The server's **name, version and address** are shown; `serverWarnings()` adds a warning for old/unknown versions or plain HTTP. **Jellyfin 12.4 shows no warning.**
2. **Quick Connect** (`AddAccountViewModel.startQuickConnect`): collects `AuthRepository.quickConnect` and maps it to `QuickConnectUi`:
   - `Starting` → `Code` (56 sp monospace; read out digit by digit) → `Authenticating`;
   - `TimedOut` (5 min) → "Get a new code";
   - `Failed` → the reason, and **"Use username and password"** when Quick Connect is switched off on the server.
   - **Cancel** cancels the collection, which stops the polling.
3. **Password:** `signInWithPassword`. 401 → "Wrong username or password." The password is held in memory only (never in saved state).
4. Success resets the form and emits `signedIn`; the saved, active account does the rest.

### 12.4 Download flow (`DownloadRequester`, shared by the film and series screens)

1. **Download** (or **Download season**) → anything already in the downloads is left out → the dialog opens with **720p selected** (`DownloadChoice.DEFAULT`).
2. **Sizes** (`DownloadChoice`):
   - 720p ≈ **min(4 Mbps, file bitrate) × running time** — shown at once from the running time.
   - Original = the **file size the server reports** (`JellyfinRepository.sourceInfo`), else bitrate × running time. "Checking size…" until it arrives; "Size unknown" if it cannot be read.
   - A season shows the total, or "Size unknown" if any episode's is.
3. **Original needs permission:** if `MediaEntry.canDownload` is false the option is **disabled and says why** (ask the server owner for "Allow media downloads", or use 720p).
4. **Confirm** → on Android 13+ the **`POST_NOTIFICATIONS`** prompt first (only while not granted). The download goes ahead **whatever the answer** — the permission only decides whether the progress notification shows; a refusal is mentioned in the result message.
5. Downloads are queued one at a time (`DownloadRepo.enqueue`); a season **stops at the first failure** and says how far it got.
6. Browse then shows a badge ("Downloading 42%", "Downloaded") instead of the button.

### 12.5 Downloads manager

- Live list from `DownloadRepo.downloads` (newest first): poster, title, quality, server, status line, progress bar.
- **Pause / Resume** (queued, downloading, paused) · **Retry** (failed) · **Delete** (always, with a confirmation).
- **Storage:** "Downloads use X · Y free", measured whenever a download starts, finishes or goes, and every 5 s while one is running.
- Original downloads the server said this phone cannot direct-play carry a **warning** (`playableOnThisPhone == false`).
- `MainActivity.onStart` calls **`graph.downloads.resumePending()`**.

### 12.6 When the access token changes

Stage 1 risk: a 720p download's address has the access token inside it (`ApiKey=`), so a new token strands unfinished downloads.

| Layer | What happens |
|---|---|
| `DownloadRepo.restartStale()` | Finds the active account's **unfinished 720p** downloads whose address carries a token that is **not** the account's current one (`hasStaleToken`, unit-tested) and re-queues each from scratch with a fresh address. |
| `AppViewModel.checkDownloadsAfterSignIn()` | Runs it whenever the active account changes while the app is on screen (so: after every sign-in, and at start-up). Says "Restarted N unfinished downloads…", or — if the server cannot be reached — tells the user to use **Retry**. |
| Retry button | `DownloadRepo.retry` → `enqueue`, which **discards the old partial data first** and asks the server for a new address. Only as the account that made the download; otherwise the message says which account to switch to. |

- Original downloads authenticate by header (`AuthHeaderInterceptor`) and are unaffected.
- In practice Jellyfin hands the **same token** back to the same device and user, so this is a safety net.

### 12.7 Layout and accessibility

- **Edge-to-edge** (`enableEdgeToEdge`, dark system bars). The scaffold uses `WindowInsets.safeDrawing`; forms use `imePadding`; the activity has `windowSoftInputMode="adjustResize"`.
- **Portrait and landscape:** every screen scrolls; grids are adaptive (`GridCells.Adaptive(112.dp)`); content is capped at 840 dp wide and centred.
- **Large text:** no fixed heights on text containers — `heightIn(min = …)` only.
- **Touch targets ≥ 48 dp;** primary buttons 56 dp. Radio rows are selectable as a whole row.
- **Screen readers:** icons that carry meaning have descriptions (decorative ones are null); headings are marked; status lines are live regions; the Quick Connect code is spelt out.

### 12.8 Tests

- **JVM:** see §10 (`phone/…`), all on `FakePhone`.
- **On device — `PhoneUiTest`** (Compose UI test; the whole `PhoneApp` on the fake data layer):
  - first run → checklist → server check → **Quick Connect** code → approval → tabs;
  - Quick Connect **cancel**, wrong then right **password**; unreachable server; **account switcher**;
  - **download dialog: 720p pre-selected**, sizes, Original chosen, Original disabled with the explanation;
  - **downloads list:** storage line, downloading / downloaded / failed rows, Pause, Retry, Delete with confirmation;
  - Cars and Settings tabs, setup checklist from Settings.
- Fakes shared by both: `app/src/sharedTest/java` (added to the `test` and `androidTest` source sets).

### 12.9 Risks to check on the real phone

1. **Nothing here has talked to the real server.** The release build was driven end to end on the emulator against a **local stand-in server** (real SDK, real Media3 downloads: Quick Connect sign-in, browse, a 720p HLS download, an Original download, pause/resume, app restart, remove account) — but the stand-in is hand-written, not Jellyfin. First real run: Quick Connect on Jellyfin 12.4, then browse, then one 720p download and one Original.
2. **`sourceInfo`** relies on `GET /Items/{id}` returning `MediaSources` with `Size`/`Bitrate` (it does on 10.11 and 12.4 per the spec). If missing, the dialog shows "Size unknown" — nothing breaks.
3. **Pause/Resume of a 720p download** depends on the server restarting the transcode mid-way (§9 risk 9). If a resumed download fails, Retry starts it again.
4. **Notification prompt:** only on Android 13+, only before a download. If "Don't allow" is chosen twice Android stops asking — downloads still work; progress is in the Downloads tab.
5. **"Open Android Auto app info"** opens the system page for `com.google.android.projection.gearhead`; on a phone without Android Auto it shows a note instead.
6. **Search** finds films, series and episodes by name; a found episode opens its series.
7. **Removing an account keeps its downloads** (they still play offline). Watch progress for them cannot be sent until that account is added again.

### 12.10 Stage 1/2 changes made in Stage 3

| Change | Why |
|---|---|
| `ServerCompatibility.of`: 10.10–12.x `SUPPORTED`, below 10.10 `TOO_OLD`, 13+ `NEWER_UNTESTED` (+ `ServerUrlTest`) | The owner's server is 12.4 and the audit found it compatible; it was wrongly warned about |
| **`jellyfinCall` runs every SDK call on `Dispatchers.IO`** (+ `MainThreadNetworkTest`) | Crash fix for car and phone: §7.12 |
| `JellyfinRepository.sourceInfo` + `SourceInfo` model (+ fake-server test) | Download size estimates |
| `DownloadRepo`: `pause`, `resume`, `retry`, `restartStale`, `hasStaleToken`; `enqueue` now replaces an existing download of the same item | Downloads manager; clean restart after a token change |
| `CarProfile.lastUsedAtMs`, `CarProfileStore.markUsed`; `CarProfileSession` calls it on connect | "Last used" in the Cars tab |
| `AppGraph.phoneSettings` (`phone-settings.json`) | Remembers that the setup checklist has been read |
| `MainActivity` rewritten; `AAFinTheme` moved to `phone/ui/Theme.kt`; `phone_placeholder_message` removed; manifest `windowSoftInputMode="adjustResize"` | The real phone app |
| Build: `ui-test-junit4` (androidTest), `ui-test-manifest` (debug only), `src/sharedTest/java` source dir; `InMemoryDataStore` moved there | Compose UI tests and shared fakes |
| New drawable `ic_phone_car` | Cars tab icon |
