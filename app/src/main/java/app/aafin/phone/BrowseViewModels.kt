package app.aafin.phone

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadedItem
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.SourceInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** A film, series, episode… with the address of its artwork (null if it has none). */
data class PosterEntry(val entry: MediaEntry, val posterUrl: String?)

data class LibraryCard(val library: Library, val imageUrl: String?)

/** The Browse tab: the libraries of the active account. */
class LibrariesViewModel(private val backend: PhoneBackend) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<List<LibraryCard>>>(Loadable.Loading)
    val state: StateFlow<Loadable<List<LibraryCard>>> = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            _state.value = try {
                Loadable.Ready(backend.library.libraries().map { LibraryCard(it, backend.library.libraryImageUrl(it, IMAGE_WIDTH)) })
            } catch (e: JellyfinException) {
                Loadable.Failed(e.userMessage)
            }
        }
    }

    private companion object {
        const val IMAGE_WIDTH = 480
    }
}

data class GridUiState(
    val items: List<PosterEntry> = emptyList(),
    val total: Int = 0,
    /** A page is being fetched. */
    val loading: Boolean = false,
    /** The last fetch failed; [items] keeps what was loaded before. */
    val error: String? = null,
    val endReached: Boolean = false,
)

/**
 * A grid of posters, loaded a page at a time: a library (when [libraryType] is given) or the
 * contents of a collection or folder.
 */
class GridViewModel(
    private val backend: PhoneBackend,
    private val parentId: String,
    private val parentName: String,
    private val libraryType: LibraryType?,
    private val pageSize: Int = PAGE_SIZE,
) : ViewModel() {
    private val _state = MutableStateFlow(GridUiState())
    val state: StateFlow<GridUiState> = _state.asStateFlow()

    init {
        loadMore()
    }

    /** Fetches the next page; does nothing while one is loading or when everything is loaded. */
    fun loadMore() {
        val current = _state.value
        if (current.loading || current.endReached) return
        _state.update { it.copy(loading = true, error = null) }
        viewModelScope.launch {
            try {
                val start = current.items.size
                val page = if (libraryType != null) {
                    backend.library.libraryItems(Library(parentId, parentName, libraryType, null), start, pageSize)
                } else {
                    backend.library.children(parentId, start, pageSize)
                }
                val more = page.items.map { PosterEntry(it, backend.library.posterUrl(it, POSTER_WIDTH)) }
                _state.update {
                    it.copy(
                        items = it.items + more,
                        total = page.totalCount,
                        loading = false,
                        endReached = !page.hasMore || page.items.isEmpty(),
                    )
                }
            } catch (e: JellyfinException) {
                _state.update { it.copy(loading = false, error = e.userMessage) }
            }
        }
    }

    companion object {
        const val PAGE_SIZE = 60
        const val POSTER_WIDTH = 300
    }
}

data class SeriesUiState(
    val seasons: Loadable<List<MediaEntry>> = Loadable.Loading,
    val selectedSeasonId: String? = null,
    val episodes: Loadable<List<PosterEntry>> = Loadable.Loading,
)

/** A series: its seasons, and the episodes of the selected one. */
class SeriesViewModel(private val backend: PhoneBackend, private val seriesId: String) : ViewModel() {
    private val _state = MutableStateFlow(SeriesUiState())
    val state: StateFlow<SeriesUiState> = _state.asStateFlow()

    val downloads = DownloadRequester(backend, viewModelScope)

    private var episodesJob: Job? = null

    init {
        load()
    }

    fun load() {
        _state.value = SeriesUiState()
        viewModelScope.launch {
            try {
                val seasons = backend.library.seasons(seriesId)
                _state.update { it.copy(seasons = Loadable.Ready(seasons)) }
                // A series with no seasons on the server still has episodes: list them all.
                selectSeason(firstToWatch(seasons)?.id)
            } catch (e: JellyfinException) {
                _state.update { it.copy(seasons = Loadable.Failed(e.userMessage)) }
            }
        }
    }

    fun selectSeason(seasonId: String?) {
        episodesJob?.cancel()
        _state.update { it.copy(selectedSeasonId = seasonId, episodes = Loadable.Loading) }
        episodesJob = viewModelScope.launch {
            val episodes = try {
                Loadable.Ready(
                    backend.library.episodes(seriesId, seasonId).map { PosterEntry(it, backend.library.posterUrl(it, THUMB_WIDTH)) },
                )
            } catch (e: JellyfinException) {
                Loadable.Failed(e.userMessage)
            }
            _state.update { it.copy(episodes = episodes) }
        }
    }

    /** Asks to download every episode of the selected season that is not downloaded already. */
    fun downloadSeason() {
        val state = _state.value
        val episodes = (state.episodes as? Loadable.Ready)?.value?.map { it.entry } ?: return
        val season = (state.seasons as? Loadable.Ready)?.value?.firstOrNull { it.id == state.selectedSeasonId }
        downloads.request(season?.name ?: "All episodes", episodes)
    }

    /** The first season with something left to watch, else the first season. Skips "Specials" when it can. */
    private fun firstToWatch(seasons: List<MediaEntry>): MediaEntry? {
        val regular = seasons.filter { (it.indexNumber ?: 1) > 0 }.ifEmpty { seasons }
        return regular.firstOrNull { !it.played && (it.unplayedCount ?: 1) > 0 } ?: regular.firstOrNull()
    }

    private companion object {
        const val THUMB_WIDTH = 400
    }
}

data class ItemDetail(val entry: MediaEntry, val posterUrl: String?)

/** One film (or other single video): details and its Download button. */
class ItemViewModel(private val backend: PhoneBackend, private val itemId: String) : ViewModel() {
    private val _state = MutableStateFlow<Loadable<ItemDetail>>(Loadable.Loading)
    val state: StateFlow<Loadable<ItemDetail>> = _state.asStateFlow()

    val downloads = DownloadRequester(backend, viewModelScope)

    init {
        load()
    }

    fun load() {
        _state.value = Loadable.Loading
        viewModelScope.launch {
            _state.value = try {
                val entry = backend.library.item(itemId)
                Loadable.Ready(ItemDetail(entry, backend.library.posterUrl(entry, POSTER_WIDTH)))
            } catch (e: JellyfinException) {
                Loadable.Failed(e.userMessage)
            }
        }
    }

    fun download() {
        val entry = (_state.value as? Loadable.Ready)?.value?.entry ?: return
        downloads.request(entry.displayTitle, listOf(entry))
    }

    private companion object {
        const val POSTER_WIDTH = 600
    }
}

data class SearchUiState(
    val query: String = "",
    /** Null until something has been searched for. */
    val results: Loadable<List<PosterEntry>>? = null,
)

/** Search across the active account's films, series and episodes. */
class SearchViewModel(private val backend: PhoneBackend) : ViewModel() {
    private val _state = MutableStateFlow(SearchUiState())
    val state: StateFlow<SearchUiState> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** Searches for [query] once typing has paused. */
    fun onQueryChange(query: String) {
        searchJob?.cancel()
        val term = query.trim()
        if (term.isEmpty()) {
            _state.value = SearchUiState(query)
            return
        }
        _state.update { it.copy(query = query, results = Loadable.Loading) }
        searchJob = viewModelScope.launch {
            delay(TYPING_PAUSE_MS)
            val results = try {
                Loadable.Ready(backend.library.search(term, LIMIT).map { PosterEntry(it, backend.library.posterUrl(it, POSTER_WIDTH)) })
            } catch (e: JellyfinException) {
                Loadable.Failed(e.userMessage)
            }
            _state.update { it.copy(results = results) }
        }
    }

    companion object {
        const val TYPING_PAUSE_MS = 400L
        private const val LIMIT = 40
        private const val POSTER_WIDTH = 200
    }
}

/** The download dialog: what is about to be downloaded and the Original / 720p choice. */
data class DownloadDialogUi(
    val title: String,
    val entries: List<MediaEntry>,
    val selected: DownloadQuality,
    val options: List<DownloadOption>,
    /** True while the server is being asked how big the files are. */
    val checkingSize: Boolean,
)

/**
 * The "Download" flow shared by the film and series screens: opens the choice dialog
 * (720p pre-selected), looks up sizes, then queues the downloads and reports how it went.
 */
class DownloadRequester(private val backend: PhoneBackend, private val scope: CoroutineScope) {
    private val _dialog = MutableStateFlow<DownloadDialogUi?>(null)
    val dialog: StateFlow<DownloadDialogUi?> = _dialog.asStateFlow()

    private val _progress = MutableStateFlow<String?>(null)

    /** "Adding 2 of 10 to downloads…" while downloads are being queued, else null. */
    val progress: StateFlow<String?> = _progress.asStateFlow()

    private val _messages = Channel<String>(Channel.BUFFERED)
    val messages: Flow<String> = _messages.receiveAsFlow()

    /** Everything already in the downloads, so screens can show "Downloaded" instead of a button. */
    val existing: StateFlow<List<DownloadedItem>> get() = backend.downloads.downloads

    private var sizeJob: Job? = null

    /** Opens the dialog for [entries], leaving out anything already in the downloads. */
    fun request(title: String, entries: List<MediaEntry>) {
        if (_progress.value != null) return
        val existing = backend.downloads.downloads.value.map { it.itemId }.toSet()
        val wanted = entries.filter { it.kind.isPlayable && it.id !in existing }
        if (wanted.isEmpty()) {
            val message = if (entries.size == 1) "That is already in your downloads." else "Everything here is already in your downloads."
            _messages.trySend(message)
            return
        }
        _dialog.value = DownloadDialogUi(
            title = title,
            entries = wanted,
            selected = DownloadChoice.DEFAULT,
            options = DownloadChoice.options(wanted, emptyMap()),
            checkingSize = true,
        )
        sizeJob?.cancel()
        sizeJob = scope.launch {
            val sources = sourcesOf(wanted)
            _dialog.update { current ->
                if (current?.entries == wanted) current.copy(options = DownloadChoice.options(wanted, sources), checkingSize = false) else current
            }
        }
    }

    fun select(quality: DownloadQuality) {
        _dialog.update { current ->
            if (current != null && current.options.any { it.quality == quality && it.enabled }) current.copy(selected = quality) else current
        }
    }

    fun dismiss() {
        sizeJob?.cancel()
        _dialog.value = null
    }

    /**
     * Queues the downloads chosen in the dialog. Stops at the first failure and says why.
     *
     * @param notificationsDenied the user has just refused notifications: the downloads still
     *   run, and the result message says there will be no progress notification.
     */
    fun confirm(notificationsDenied: Boolean = false) {
        val request = _dialog.value ?: return
        dismiss()
        val entries = request.entries
        scope.launch {
            entries.forEachIndexed { index, entry ->
                _progress.value = if (entries.size == 1) "Adding to downloads…" else "Adding ${index + 1} of ${entries.size} to downloads…"
                val error = failureMessage { backend.downloads.enqueue(entry, request.selected) }
                if (error != null) {
                    _progress.value = null
                    _messages.send(if (index > 0) "Added $index of ${entries.size}, then stopped. $error" else error)
                    return@launch
                }
            }
            _progress.value = null
            val what = if (entries.size == 1) entries.single().name else "${entries.size} episodes"
            val note = if (notificationsDenied) " Notifications are off, so progress only shows in the Downloads tab." else " See the Downloads tab."
            _messages.send("Downloading $what (${request.selected.label}).$note")
        }
    }

    /**
     * File details for the size estimates. An item whose details cannot be fetched is simply
     * left out - its size shows as unknown, and the download itself will report any real problem.
     */
    private suspend fun sourcesOf(entries: List<MediaEntry>): Map<String, SourceInfo> = coroutineScope {
        entries.map { entry ->
            async {
                try {
                    entry.id to backend.library.sourceInfo(entry.id)
                } catch (e: JellyfinException) {
                    null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }
}
