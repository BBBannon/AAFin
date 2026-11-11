package app.aafin.car

import android.graphics.Bitmap
import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.constraints.ConstraintManager
import androidx.car.app.versioning.CarAppApiLevels
import app.aafin.AppGraph
import app.aafin.data.accounts.Account
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.Page
import app.aafin.data.playback.PlaybackState
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.ScreenSize
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Everything the car screens need from the data layer, as one small interface so the screens can
 * be tested with a fake. The real one ([GraphCarBackend]) forwards to Stage 1's `AppGraph`.
 *
 * Browsing calls throw `JellyfinException`. Call from the main thread.
 */
interface CarBackend {
    /** Null = signed out. Emits again whenever the active account changes. */
    val activeAccount: Flow<Account?>
    suspend fun accounts(): List<Account>
    suspend fun setActiveAccount(id: String)

    suspend fun libraries(): List<Library>
    suspend fun libraryItems(library: Library, startIndex: Int, limit: Int): Page<MediaEntry>
    suspend fun children(parentId: String, startIndex: Int, limit: Int): Page<MediaEntry>
    suspend fun resumeItems(limit: Int): List<MediaEntry>
    suspend fun nextUp(limit: Int): List<MediaEntry>
    suspend fun episodes(seriesId: String): List<MediaEntry>
    suspend fun search(query: String, limit: Int): List<MediaEntry>
    suspend fun posterUrl(entry: MediaEntry, maxWidth: Int): String?

    /** Every download; the car lists the completed ones. Needs no network. */
    val downloads: StateFlow<List<DownloadedItem>>

    val playback: StateFlow<PlaybackState>
    /** @param screen the car's screen size if known, so the stream is no larger than it can show. */
    suspend fun play(entry: MediaEntry, tier: QualityTier, screen: ScreenSize?): Boolean
    fun togglePlayPause()
    fun seekBy(deltaMs: Long)
    fun stop()
    suspend fun selectAudio(streamIndex: Int)
    suspend fun selectSubtitle(streamIndex: Int?)
    suspend fun setQuality(tier: QualityTier)
    suspend fun retry()
}

/** [CarBackend] on top of the app's real singletons. Create on the main thread. */
class GraphCarBackend(private val graph: AppGraph) : CarBackend {
    override val activeAccount: Flow<Account?> get() = graph.accountStore.activeAccount
    override suspend fun accounts(): List<Account> = graph.accountStore.all()
    override suspend fun setActiveAccount(id: String) = graph.accountStore.setActive(id)

    override suspend fun libraries() = graph.jellyfin.libraries()
    override suspend fun libraryItems(library: Library, startIndex: Int, limit: Int) = graph.jellyfin.libraryItems(library, startIndex, limit)
    override suspend fun children(parentId: String, startIndex: Int, limit: Int) = graph.jellyfin.children(parentId, startIndex, limit)
    override suspend fun resumeItems(limit: Int) = graph.jellyfin.resumeItems(limit)
    override suspend fun nextUp(limit: Int) = graph.jellyfin.nextUp(limit)
    override suspend fun episodes(seriesId: String) = graph.jellyfin.episodes(seriesId)
    override suspend fun search(query: String, limit: Int) = graph.jellyfin.search(query, limit)
    override suspend fun posterUrl(entry: MediaEntry, maxWidth: Int) = graph.jellyfin.posterUrl(entry, maxWidth)

    // Touched as soon as the car connects, so the downloads list is ready before anyone opens it.
    override val downloads: StateFlow<List<DownloadedItem>> = graph.downloads.downloads

    override val playback: StateFlow<PlaybackState> get() = graph.playback.state
    override suspend fun play(entry: MediaEntry, tier: QualityTier, screen: ScreenSize?) = graph.playback.play(entry, tier, screen = screen)
    override fun togglePlayPause() = graph.playback.togglePlayPause()
    override fun seekBy(deltaMs: Long) = graph.playback.seekBy(deltaMs)
    override fun stop() = graph.playback.stop()
    override suspend fun selectAudio(streamIndex: Int) = graph.playback.selectAudio(streamIndex)
    override suspend fun selectSubtitle(streamIndex: Int?) = graph.playback.selectSubtitle(streamIndex)
    override suspend fun setQuality(tier: QualityTier) = graph.playback.setQuality(tier)
    override suspend fun retry() = graph.playback.retry()
}

/** How many entries the car allows on one screen right now (it can change, e.g. when driving). */
interface ContentLimits {
    val list: Int
    val grid: Int
}

/** Asks the Android Auto host; falls back to the library's documented minimum of six. */
class HostContentLimits(private val carContext: CarContext) : ContentLimits {
    override val list: Int get() = limit(ConstraintManager.CONTENT_LIMIT_TYPE_LIST)
    override val grid: Int get() = limit(ConstraintManager.CONTENT_LIMIT_TYPE_GRID)

    private fun limit(type: Int): Int {
        if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_2) return FALLBACK
        return try {
            carContext.getCarService(ConstraintManager::class.java).getContentLimit(type)
        } catch (e: RuntimeException) {
            // HostException: the head unit went away mid-call. The next template will fail too,
            // but a list that is too short is better than a crash here.
            Log.w(CarLog.CAR, "Could not read the content limit: ${e.javaClass.simpleName}")
            FALLBACK
        }
    }

    private companion object {
        const val FALLBACK = 6
    }
}

/** Small poster images for rows and grids. Null when there is no artwork or it cannot be loaded. */
interface PosterSource {
    suspend fun poster(entry: MediaEntry): Bitmap?
    suspend fun file(file: File): Bitmap?
}

/** The quality tier and screen size of the car we are connected to (its car profile). */
interface CarQuality {
    suspend fun tier(): QualityTier
    suspend fun setTier(tier: QualityTier)

    /** The car's drawing surface: this connection's if one has arrived, else the size saved last time; null if never seen. */
    suspend fun screen(): ScreenSize?
}

/** The part of the video surface the screens talk to. */
interface VideoOverlay {
    /** A short line over the picture ("Up next in 5 s…"), or null to clear it. */
    fun setNotice(text: String?)

    /** Brings the title and progress bar back for a few seconds. */
    fun showOverlay()
}

/** What every car screen is given. */
class CarEnv(
    val backend: CarBackend,
    val limits: ContentLimits,
    val posters: PosterSource,
    val quality: CarQuality,
    val overlay: VideoOverlay,
)

/** Logcat tags. Filter with: `adb logcat -s AAFin/Car AAFin/Surface AAFin/Playback` */
object CarLog {
    const val CAR = "AAFin/Car"
    const val SURFACE = "AAFin/Surface"
    const val PLAYBACK = "AAFin/Playback"
}
