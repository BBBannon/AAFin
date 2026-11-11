package app.aafin.car

import android.graphics.Bitmap
import app.aafin.data.accounts.Account
import app.aafin.data.downloads.DownloadMetadata
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.Page
import app.aafin.data.playback.PlaybackState
import app.aafin.data.playback.QualityTier
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** An in-memory stand-in for the data layer. Set [failure] to make every server call fail. */
class FakeBackend : CarBackend {
    val alice = Account("server:alice", "https://jellyfin.example", "Home server", "alice", "Alice", "token-a")
    val bob = Account("server:bob", "https://jellyfin.example", "Home server", "bob", "Bob", "token-b")

    override val activeAccount = MutableStateFlow<Account?>(alice)
    var accountList: List<Account> = listOf(alice)

    var failure: JellyfinException? = null
    var libraryList: List<Library> = listOf(
        Library("lib-films", "Films", LibraryType.MOVIES, null),
        Library("lib-tv", "TV", LibraryType.TV_SHOWS, null),
    )
    var items: List<MediaEntry> = emptyList()
    var resume: List<MediaEntry> = emptyList()
    var next: List<MediaEntry> = emptyList()
    var episodeList: List<MediaEntry> = emptyList()
    var searchResults: List<MediaEntry> = emptyList()
    val searches = mutableListOf<String>()
    val pageRequests = mutableListOf<Pair<Int, Int>>()

    private fun <T> server(value: () -> T): T = failure?.let { throw it } ?: value()

    override suspend fun accounts() = accountList
    override suspend fun setActiveAccount(id: String) {
        activeAccount.value = accountList.first { it.id == id }
    }

    override suspend fun libraries() = server { libraryList }
    override suspend fun libraryItems(library: Library, startIndex: Int, limit: Int) = page(startIndex, limit)
    override suspend fun children(parentId: String, startIndex: Int, limit: Int) = page(startIndex, limit)
    private fun page(startIndex: Int, limit: Int): Page<MediaEntry> = server {
        pageRequests += startIndex to limit
        Page(items.drop(startIndex).take(limit), startIndex, items.size)
    }

    override suspend fun resumeItems(limit: Int) = server { resume.take(limit) }
    override suspend fun nextUp(limit: Int) = server { next.take(limit) }
    override suspend fun episodes(seriesId: String) = server { episodeList }
    override suspend fun search(query: String, limit: Int) = server {
        searches += query
        searchResults.take(limit)
    }

    override suspend fun posterUrl(entry: MediaEntry, maxWidth: Int): String? = null

    override val downloads = MutableStateFlow<List<DownloadedItem>>(emptyList())

    override val playback = MutableStateFlow(PlaybackState())
    val played = mutableListOf<Pair<MediaEntry, QualityTier>>()
    var playSucceeds = true
    var stops = 0
    var toggles = 0
    val seeks = mutableListOf<Long>()
    val audioChoices = mutableListOf<Int>()
    val subtitleChoices = mutableListOf<Int?>()
    val qualityChoices = mutableListOf<QualityTier>()
    var retries = 0

    override suspend fun play(entry: MediaEntry, tier: QualityTier): Boolean {
        played += entry to tier
        playback.value = PlaybackState(item = entry, title = entry.displayTitle, isPlaying = playSucceeds, playWhenReady = playSucceeds, tier = tier)
        return playSucceeds
    }

    override fun togglePlayPause() {
        toggles++
    }

    override fun seekBy(deltaMs: Long) {
        seeks += deltaMs
    }

    override fun stop() {
        stops++
        playback.value = PlaybackState()
    }

    override suspend fun selectAudio(streamIndex: Int) {
        failure?.let { throw it }
        audioChoices += streamIndex
    }

    override suspend fun selectSubtitle(streamIndex: Int?) {
        failure?.let { throw it }
        subtitleChoices += streamIndex
    }

    override suspend fun setQuality(tier: QualityTier) {
        failure?.let { throw it }
        qualityChoices += tier
    }

    override suspend fun retry() {
        retries++
    }
}

class FakeLimits(override var list: Int = 6, override var grid: Int = 6) : ContentLimits

object NoPosters : PosterSource {
    override suspend fun poster(entry: MediaEntry): Bitmap? = null
    override suspend fun file(file: File): Bitmap? = null
}

class FakeQuality(var current: QualityTier = QualityTier.P720_4M) : CarQuality {
    override suspend fun tier() = current
    override suspend fun setTier(tier: QualityTier) {
        current = tier
    }
}

class FakeOverlay : VideoOverlay {
    val notices = mutableListOf<String?>()
    var shows = 0
    override fun setNotice(text: String?) {
        notices += text
    }

    override fun showOverlay() {
        shows++
    }
}

fun film(index: Int) = MediaEntry("film-$index", "Film $index", ItemKind.MOVIE, productionYear = 2000 + index, runtimeMs = 100 * 60_000L)

fun series(index: Int) = MediaEntry("series-$index", "Series $index", ItemKind.SERIES, unplayedCount = 3)

fun episode(season: Int, number: Int, played: Boolean = false) = MediaEntry(
    id = "s${season}e$number",
    name = "Episode $number",
    kind = ItemKind.EPISODE,
    played = played,
    seriesId = "series-1",
    seriesName = "Series 1",
    seasonId = "season-$season",
    seasonName = "Season $season",
    indexNumber = number,
    parentIndexNumber = season,
)

fun download(entry: MediaEntry, status: DownloadStatus = DownloadStatus.COMPLETED) = DownloadedItem(
    metadata = DownloadMetadata(
        itemId = entry.id,
        accountId = "server:alice",
        serverName = "Home server",
        entry = entry,
        quality = DownloadQuality.P720,
        mediaSourceId = entry.id,
        createdAtMs = 0,
        resumePositionMs = entry.resumePositionMs,
    ),
    status = status,
    percent = if (status == DownloadStatus.COMPLETED) 100f else 40f,
    bytesDownloaded = 1_000,
    posterFile = null,
)

fun networkError() = JellyfinException(JellyfinException.Kind.NETWORK)
