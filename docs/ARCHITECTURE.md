# AAFin — Architecture (Stage 1: foundation)

**What exists now:** build, signing, manifest, logo, the whole data layer, unit tests, two placeholder screens.
**What comes next:** Stage 2 = car UI (`app.aafin.car`). Stage 3 = phone UI (`app.aafin.phone`).

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
├── car/                AAFinCarAppService, AAFinSession, PlaceholderScreen   ← Stage 2 builds here
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

**Stage 2 (car)**

4. **`PlayerView` in a `Presentation` on a `VirtualDisplay`** is the unproven core. Test early on the real head unit, and on the DHU.
5. **NAVIGATION category without navigating.** Do not call `navigationStarted()`. Android Auto may still treat AAFin as the "default navigation app" after launch (it is the last nav app opened).
6. **Template restrictions while driving** (list lengths, step count ≤ 5, refresh quota). Position updates every second must go to the Presentation overlay, **not** through `invalidate()`.
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

**On-device smoke test:** `gradlew.bat connectedDebugAndroidTest` (`app/src/androidTest/.../GraphSmokeTest.kt`).

- Creates every `AppGraph` singleton on a real Android runtime: DataStore files, ExoPlayer + `MediaSession`, download cache/manager, WorkManager queue, the SDK's HTTP stack.
- Needs no Jellyfin server. Leaves real accounts alone.
- **It installs the debug build**, which is signed differently from the release build — on a phone that has the release APK it will refuse to install rather than overwrite. Use an emulator, or uninstall first.
