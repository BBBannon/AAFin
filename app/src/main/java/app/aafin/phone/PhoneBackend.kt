package app.aafin.phone

import android.content.Context
import androidx.datastore.core.DataStore
import app.aafin.AppGraph
import app.aafin.BuildConfig
import app.aafin.data.accounts.AccountStore
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.AuthRepository
import app.aafin.data.jellyfin.ImageKind
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.Page
import app.aafin.data.jellyfin.SourceInfo
import app.aafin.data.profiles.CarProfileStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable

/** Phone-only settings. */
@Serializable
data class PhoneSettings(
    /** True once the first-run setup checklist has been read. */
    val setupSeen: Boolean = false,
)

class PhoneSettingsStore(private val store: DataStore<PhoneSettings>) {
    val setupSeen: Flow<Boolean> = store.data.map { it.setupSeen }.distinctUntilChanged()

    suspend fun setSetupSeen() {
        store.updateData { it.copy(setupSeen = true) }
    }
}

/** Browsing the active account's library. Every call throws `JellyfinException` on failure. */
interface PhoneLibrary {
    suspend fun libraries(): List<Library>
    suspend fun libraryItems(library: Library, startIndex: Int, limit: Int): Page<MediaEntry>
    suspend fun children(parentId: String, startIndex: Int, limit: Int): Page<MediaEntry>
    suspend fun seasons(seriesId: String): List<MediaEntry>
    suspend fun episodes(seriesId: String, seasonId: String?): List<MediaEntry>
    suspend fun item(itemId: String): MediaEntry
    suspend fun search(query: String, limit: Int): List<MediaEntry>
    suspend fun sourceInfo(itemId: String): SourceInfo
    suspend fun posterUrl(entry: MediaEntry, maxWidth: Int): String?
    suspend fun libraryImageUrl(library: Library, maxWidth: Int): String?
}

/** Downloads, as the phone screens use them. Call from the main thread. */
interface PhoneDownloads {
    val downloads: StateFlow<List<DownloadedItem>>

    /** Throws `JellyfinException` or `IOException`. */
    suspend fun enqueue(entry: MediaEntry, quality: DownloadQuality)
    suspend fun delete(itemId: String)
    fun pause(itemId: String)
    fun resume(itemId: String)

    /** Throws `JellyfinException` or `IOException`. */
    suspend fun retry(itemId: String)

    /** Throws `JellyfinException` or `IOException`. Returns how many downloads were restarted. */
    suspend fun restartStale(): Int
    suspend fun storageUsedBytes(): Long
    fun freeSpaceBytes(): Long
}

/**
 * Everything the phone screens need from the data layer. The stores and the sign-in repository
 * are the real Stage 1 classes (tests give them in-memory storage and a fake gateway); browsing
 * and downloads sit behind small interfaces so they can be faked.
 */
class PhoneBackend(
    val accounts: AccountStore,
    val auth: AuthRepository,
    val carProfiles: CarProfileStore,
    val settings: PhoneSettingsStore,
    val library: PhoneLibrary,
    val downloads: PhoneDownloads,
    val appVersion: String,
)

/** The real [PhoneBackend], on top of [AppGraph]. Create on the main thread. */
fun graphPhoneBackend(context: Context, graph: AppGraph): PhoneBackend {
    val appContext = context.applicationContext
    val repo = graph.downloads
    return PhoneBackend(
        accounts = graph.accountStore,
        auth = graph.auth,
        carProfiles = graph.carProfiles,
        settings = graph.phoneSettings,
        library = object : PhoneLibrary {
            override suspend fun libraries() = graph.jellyfin.libraries()
            override suspend fun libraryItems(library: Library, startIndex: Int, limit: Int) =
                graph.jellyfin.libraryItems(library, startIndex, limit)
            override suspend fun children(parentId: String, startIndex: Int, limit: Int) =
                graph.jellyfin.children(parentId, startIndex, limit)
            override suspend fun seasons(seriesId: String) = graph.jellyfin.seasons(seriesId)
            override suspend fun episodes(seriesId: String, seasonId: String?) = graph.jellyfin.episodes(seriesId, seasonId)
            override suspend fun item(itemId: String) = graph.jellyfin.item(itemId)
            override suspend fun search(query: String, limit: Int) = graph.jellyfin.search(query, limit)
            override suspend fun sourceInfo(itemId: String) = graph.jellyfin.sourceInfo(itemId)
            override suspend fun posterUrl(entry: MediaEntry, maxWidth: Int) = graph.jellyfin.posterUrl(entry, maxWidth)
            override suspend fun libraryImageUrl(library: Library, maxWidth: Int) =
                library.primaryImageTag?.let { graph.jellyfin.imageUrl(library.id, ImageKind.PRIMARY, maxWidth, it) }
        },
        downloads = object : PhoneDownloads {
            override val downloads = repo.downloads
            override suspend fun enqueue(entry: MediaEntry, quality: DownloadQuality) = repo.enqueue(entry, quality)
            override suspend fun delete(itemId: String) = repo.delete(itemId)
            override fun pause(itemId: String) = repo.pause(itemId)
            override fun resume(itemId: String) = repo.resume(itemId)
            override suspend fun retry(itemId: String) = repo.retry(itemId)
            override suspend fun restartStale() = repo.restartStale()
            override suspend fun storageUsedBytes() = repo.storageUsedBytes()
            override fun freeSpaceBytes() = appContext.filesDir.usableSpace
        },
        appVersion = BuildConfig.VERSION_NAME,
    )
}
