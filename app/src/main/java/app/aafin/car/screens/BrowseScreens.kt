package app.aafin.car.screens

import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SearchTemplate
import androidx.car.app.model.SectionedItemList
import app.aafin.R
import app.aafin.car.CarEnv
import app.aafin.car.CarFormat
import app.aafin.car.FlowBudget
import app.aafin.car.Paging
import app.aafin.car.PosterBoard
import app.aafin.car.SeasonGroup
import app.aafin.car.SeriesLayout
import app.aafin.car.SeriesLogic
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.Page
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** What a [LibraryScreen] lists. */
sealed interface LibrarySource {
    val title: String

    /** Browsing screens the listed items may still need before the player. */
    val stepsBelow: Int

    /** A top-level library. */
    data class Top(val library: Library) : LibrarySource {
        override val title: String get() = library.name
        override val stepsBelow: Int get() = FlowBudget.stepsBelow(library.type)
    }

    /** The contents of a collection (box set) or folder; these can hold series. */
    data class Folder(val id: String, override val title: String) : LibrarySource {
        override val stepsBelow: Int get() = 1
    }
}

/**
 * One page of a library as a grid of posters. Films play; series and collections open.
 * Further pages open behind a "More…" tile while the template budget allows; the search button
 * reaches everything else.
 */
class LibraryScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int,
    private val source: LibrarySource,
    private val startIndex: Int = 0,
) : CarScreen(carContext, env, firstStep) {

    private var state: Load<Page<MediaEntry>> = Load.Loading
    private val posters = PosterBoard(scope, env.posters, ::invalidate)

    /** Fixed when the page is first asked for, so a limit that changes later cannot shift the paging. */
    private val limit = Paging.usableLimit(env.limits.grid, GRID_CAP)

    init {
        reload()
    }

    private fun reload() {
        load(state, { state = it }) {
            when (source) {
                is LibrarySource.Top -> env.backend.libraryItems(source.library, startIndex, limit)
                is LibrarySource.Folder -> env.backend.children(source.id, startIndex, limit)
            }
        }
    }

    @Suppress("DEPRECATION") // see CarScreen.loadingList
    override fun content(): Content = when (val current = state) {
        Load.Loading -> Content(
            GridTemplate.Builder().setTitle(source.title).setHeaderAction(Action.BACK).setLoading(true).build(),
            structure = "loading",
            loading = true,
        )
        is Load.Failed -> errorContent(source.title, current, onRetry = ::reload)
        is Load.Ready -> grid(current.value)
    }

    @Suppress("DEPRECATION")
    private fun grid(page: Page<MediaEntry>): Content {
        val window = pageWindow(page.totalCount, page.startIndex, limit, source.stepsBelow)
        val shown = page.items.take(window.count)
        posters.load(shown)

        val items = ItemList.Builder().setNoItemsMessage("Nothing here yet.")
        shown.forEach { entry ->
            val poster = posters.icon(entry.id)
            val item = GridItem.Builder()
                .setTitle(entry.name.ifBlank { "Untitled" })
                .setImage(
                    poster ?: icon(placeholderIcon(entry.kind)),
                    if (poster != null) GridItem.IMAGE_TYPE_LARGE else GridItem.IMAGE_TYPE_ICON,
                )
                .setOnClickListener { openEntry(entry) }
            CarFormat.status(entry)?.let(item::setText)
            items.addItem(item.build())
        }
        if (window.hasMore) {
            items.addItem(
                GridItem.Builder()
                    .setTitle(MORE)
                    .setImage(icon(R.drawable.ic_car_more), GridItem.IMAGE_TYPE_ICON)
                    .setOnClickListener { open { LibraryScreen(carContext, env, it, source, page.startIndex + shown.size) } }
                    .build(),
            )
        }

        val range = Paging.rangeLabel(page.startIndex, shown.size, page.totalCount)
        val title = listOfNotNull(source.title, range).joinToString(" · ")
        val builder = GridTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK).setSingleList(items.build())
        searchStrip()?.let(builder::setActionStrip)
        return Content(builder.build(), structure = title + "|" + shown.joinToString("|") { it.id } + if (window.hasMore) "|$MORE" else "")
    }
}

/**
 * A series. If every episode fits on one screen they are listed in sections, one per season.
 * Otherwise the seasons are listed and each opens its own screen ([seasonKey]); a season too
 * long for one screen starts at the next episode to watch and continues behind "More…".
 *
 * @param pageStart where in the season (or season list) this page starts; null = decide from what is watched.
 * @param preloaded the series' episodes, when the screen that opened this one already had them.
 */
class SeriesScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int,
    private val seriesId: String,
    private val title: String,
    private val seasonKey: String? = null,
    private val pageStart: Int? = null,
    preloaded: List<MediaEntry>? = null,
) : CarScreen(carContext, env, firstStep) {

    private var state: Load<List<MediaEntry>> = preloaded?.let { Load.Ready(it) } ?: Load.Loading

    /** Fixed on creation: the layout must not flip between sections and a season list mid-visit. */
    private val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
    private val canOpenSeason = FlowBudget.allows(firstStep + 1, terminal = false)

    init {
        if (preloaded == null) reload()
    }

    private fun reload() {
        load(state, { state = it }) { env.backend.episodes(seriesId) }
    }

    override fun content(): Content = when (val current = state) {
        Load.Loading -> loadingList(title)
        is Load.Failed -> errorContent(title, current, onRetry = ::reload)
        is Load.Ready -> when (val layout = SeriesLogic.layout(current.value, seasonKey, limit, canOpenSeason)) {
            is SeriesLayout.Sectioned -> sectioned(layout.seasons)
            is SeriesLayout.SeasonPicker -> seasonPicker(layout.seasons, current.value)
            is SeriesLayout.Episodes -> episodes(layout, current.value)
        }
    }

    private fun episodeRow(episode: MediaEntry): Row =
        row(CarFormat.episodeTitle(episode), CarFormat.status(episode)) { play(episode) }

    @Suppress("DEPRECATION") // see CarScreen.loadingList
    private fun sectioned(seasons: List<SeasonGroup>): Content {
        val structure = seasons.joinToString("/") { season -> season.key + ":" + season.episodes.joinToString("|") { it.id } }
        if (seasons.size <= 1) {
            val items = ItemList.Builder().setNoItemsMessage("No episodes found.")
            seasons.firstOrNull()?.episodes?.forEach { items.addItem(episodeRow(it)) }
            return listContent(title, items.build(), structure)
        }
        val builder = ListTemplate.Builder().setTitle(title).setHeaderAction(Action.BACK)
        seasons.forEach { season ->
            val items = ItemList.Builder()
            season.episodes.forEach { items.addItem(episodeRow(it)) }
            builder.addSectionedList(SectionedItemList.create(items.build(), season.title))
        }
        return Content(builder.build(), structure)
    }

    private fun seasonPicker(seasons: List<SeasonGroup>, all: List<MediaEntry>): Content {
        val start = pageStart ?: 0
        // Each season still needs its own screen before the player.
        val window = pageWindow(seasons.size, start, limit, stepsBelow = 1)
        val shown = seasons.drop(start).take(window.count)

        val items = ItemList.Builder()
        shown.forEach { season ->
            val unwatched = season.episodes.count { !it.played }
            val text = "${season.episodes.size} episodes · " + if (unwatched == 0) "watched" else "$unwatched to watch"
            items.addItem(
                row(season.title, text, browsable = true) {
                    open { SeriesScreen(carContext, env, it, seriesId, "$title · ${season.title}", season.key, preloaded = all) }
                },
            )
        }
        if (window.hasMore) {
            items.addItem(
                moreRow { open { SeriesScreen(carContext, env, it, seriesId, title, null, start + window.count, all) } },
            )
        }
        return listContent(title, items.build(), "seasons:" + shown.joinToString("|") { it.key } + if (window.hasMore) "|$MORE" else "")
    }

    private fun episodes(layout: SeriesLayout.Episodes, all: List<MediaEntry>): Content {
        val start = pageStart ?: layout.start
        val window = pageWindow(layout.episodes.size, start, limit, stepsBelow = 0)
        val shown = layout.episodes.drop(start).take(window.count)

        val items = ItemList.Builder().setNoItemsMessage("No episodes found.")
        shown.forEach { items.addItem(episodeRow(it)) }
        if (window.hasMore) {
            items.addItem(
                moreRow { open { SeriesScreen(carContext, env, it, seriesId, title, seasonKey, start + window.count, all) } },
            )
        }
        return listContent(title, items.build(), "episodes:" + shown.joinToString("|") { it.id } + if (window.hasMore) "|$MORE" else "")
    }
}

/**
 * Search by typing or voice on the car's keyboard. Results update as the text changes; the
 * search template treats every update as a refresh, so typing never uses up template steps.
 */
class SearchScreen(carContext: CarContext, env: CarEnv, firstStep: Int) : CarScreen(carContext, env, firstStep) {

    private var query = ""
    private var results: List<MediaEntry> = emptyList()
    private var searching = false
    private var error: JellyfinException? = null
    private var job: Job? = null
    private val posters = PosterBoard(scope, env.posters, ::invalidate)

    private val callback = object : SearchTemplate.SearchCallback {
        override fun onSearchTextChanged(searchText: String) = search(searchText, afterMs = TYPING_PAUSE_MS)
        override fun onSearchSubmitted(searchText: String) = search(searchText, afterMs = 0)
    }

    private fun search(text: String, afterMs: Long) {
        val term = text.trim()
        if (term == query && afterMs > 0) return
        query = term
        job?.cancel()
        if (term.isEmpty()) {
            results = emptyList()
            searching = false
            error = null
            invalidate()
            return
        }
        job = scope.launch {
            delay(afterMs)
            searching = true
            invalidate()
            try {
                results = env.backend.search(term, Paging.usableLimit(env.limits.list, LIST_CAP))
                error = null
            } catch (e: JellyfinException) {
                results = emptyList()
                error = e
            }
            searching = false
            invalidate()
        }
    }

    override fun content(): Content {
        val builder = SearchTemplate.Builder(callback)
            .setHeaderAction(Action.BACK)
            .setSearchHint("Search films and TV")
            .setShowKeyboardByDefault(true)
        if (searching) {
            builder.setLoading(true)
        } else {
            posters.load(results)
            val message = when {
                error != null -> error!!.userMessage
                query.isEmpty() -> "Type the name of a film, series or episode."
                else -> "Nothing found for “$query”."
            }
            val items = ItemList.Builder().setNoItemsMessage(message)
            // The car's limit can drop after the search was made (it is lower while driving).
            results.take(Paging.usableLimit(env.limits.list, LIST_CAP)).forEach { entry ->
                val poster = posters.icon(entry.id)
                items.addItem(
                    row(
                        title = CarFormat.entryTitle(entry),
                        text = CarFormat.status(entry),
                        image = poster ?: icon(placeholderIcon(entry.kind)),
                        imageType = if (poster != null) Row.IMAGE_TYPE_LARGE else Row.IMAGE_TYPE_ICON,
                    ) { openEntry(entry) },
                )
            }
            builder.setItemList(items.build())
        }
        return Content(builder.build(), structure = "search")
    }

    private companion object {
        const val TYPING_PAUSE_MS = 500L
    }
}
