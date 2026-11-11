package app.aafin

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.dataStoreFile
import app.aafin.data.accounts.AccountStore
import app.aafin.data.accounts.AccountsState
import app.aafin.data.downloads.DownloadEnvironment
import app.aafin.data.downloads.DownloadRepo
import app.aafin.data.jellyfin.AuthRepository
import app.aafin.data.jellyfin.ClientIdentity
import app.aafin.data.jellyfin.JellyfinClients
import app.aafin.data.jellyfin.JellyfinRepository
import app.aafin.data.jellyfin.SdkAuthGateway
import app.aafin.data.jsonDataStore
import app.aafin.data.playback.AuthHeaderInterceptor
import app.aafin.data.playback.CodecCapabilities
import app.aafin.data.playback.MediaCodecCapabilities
import app.aafin.data.playback.PlaybackController
import app.aafin.data.playback.PlaybackResolver
import app.aafin.data.playback.ProgressReporter
import app.aafin.data.playback.SdkPlaystateGateway
import app.aafin.data.playback.WorkManagerPositionQueue
import app.aafin.data.profiles.CarProfileStore
import app.aafin.data.profiles.CarProfilesState
import app.aafin.phone.PhoneSettings
import app.aafin.phone.PhoneSettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The app's singletons, created lazily and wired by hand (no DI framework).
 *
 * Obtain it with `AAFinApp.graph(context)`. The stores and repositories may be used from any
 * coroutine; [downloadEnvironment], [downloads] and [playback] must first be touched on the main
 * thread (Media3 requirement) - which is where the car and phone UI run anyway.
 */
class AppGraph(context: Context) {
    private val appContext: Context = context.applicationContext

    /** Lives as long as the process. Main-thread dispatcher, so Media3 objects can be used from it. */
    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    val accountStore: AccountStore by lazy {
        AccountStore(
            jsonDataStore(appContext.dataStoreFile("accounts.json"), AccountsState.serializer(), AccountsState()) {
                Log.e(TAG, "Saved accounts were unreadable and have been reset")
            },
        )
    }

    val carProfiles: CarProfileStore by lazy {
        CarProfileStore(
            jsonDataStore(appContext.dataStoreFile("car-profiles.json"), CarProfilesState.serializer(), CarProfilesState()) {
                Log.e(TAG, "Saved car profiles were unreadable and have been reset")
            },
        )
    }

    /** Phone-only settings (whether the setup checklist has been read). */
    val phoneSettings: PhoneSettingsStore by lazy {
        PhoneSettingsStore(
            jsonDataStore(appContext.dataStoreFile("phone-settings.json"), PhoneSettings.serializer(), PhoneSettings()) {
                Log.e(TAG, "Saved phone settings were unreadable and have been reset")
            },
        )
    }

    /** Base HTTP client; the Jellyfin SDK and the media client share its connection pool. */
    private val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val jellyfinClients: JellyfinClients by lazy {
        JellyfinClients(
            identity = ClientIdentity(clientName = "AAFin", clientVersion = BuildConfig.VERSION_NAME, deviceName = Build.MODEL),
            accountStore = accountStore,
            httpClient = httpClient,
        )
    }

    /** HTTP client for video, subtitles and downloads: adds the Jellyfin `Authorization` header. */
    val mediaHttpClient: OkHttpClient by lazy {
        httpClient.newBuilder().addInterceptor(AuthHeaderInterceptor(accountStore, jellyfinClients)).build()
    }

    /** Server discovery and sign-in (Quick Connect and password). */
    val auth: AuthRepository by lazy { AuthRepository(SdkAuthGateway(jellyfinClients), accountStore) }

    /** Browsing: libraries, items, series, search, artwork URLs. */
    val jellyfin: JellyfinRepository by lazy { JellyfinRepository(jellyfinClients, accountStore) }

    val codecCapabilities: CodecCapabilities by lazy { MediaCodecCapabilities() }

    val playbackResolver: PlaybackResolver by lazy { PlaybackResolver(jellyfinClients, accountStore, codecCapabilities) }

    val progressReporter: ProgressReporter by lazy {
        ProgressReporter(SdkPlaystateGateway(jellyfinClients, accountStore), WorkManagerPositionQueue(appContext))
    }

    /** Media3 cache + download manager. Main thread. */
    val downloadEnvironment: DownloadEnvironment by lazy { DownloadEnvironment(appContext, mediaHttpClient) }

    /** Downloads for offline playback. Main thread. */
    val downloads: DownloadRepo by lazy {
        DownloadRepo(appContext, downloadEnvironment, playbackResolver, accountStore, mediaHttpClient, appScope)
    }

    /** The single player. Main thread. */
    val playback: PlaybackController by lazy {
        PlaybackController(
            context = appContext,
            resolver = playbackResolver,
            reporter = progressReporter,
            downloads = downloads,
            dataSourceFactory = downloadEnvironment.playbackDataSourceFactory,
            scope = appScope,
        )
    }

    private companion object {
        const val TAG = "AAFin"
    }
}
