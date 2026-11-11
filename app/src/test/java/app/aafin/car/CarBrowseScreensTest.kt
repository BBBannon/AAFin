package app.aafin.car

import androidx.car.app.OnDoneCallback
import androidx.car.app.model.GridItem
import androidx.car.app.model.GridTemplate
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.SearchTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import app.aafin.car.screens.AccountsScreen
import app.aafin.car.screens.DownloadsScreen
import app.aafin.car.screens.EntryListScreen
import app.aafin.car.screens.FlowResetScreen
import app.aafin.car.screens.HomeScreen
import app.aafin.car.screens.LibraryScreen
import app.aafin.car.screens.LibrarySource
import app.aafin.car.screens.PlayerScreen
import app.aafin.car.screens.SearchScreen
import app.aafin.car.screens.SeriesScreen
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.Library
import app.aafin.data.jellyfin.LibraryType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Template building for the browsing screens, against fakes (no server, no head unit). */
@RunWith(RobolectricTestRunner::class)
class CarBrowseScreensTest : CarScreenTestBase() {

    private val films = Library("lib-films", "Films", LibraryType.MOVIES, null)
    private val tv = Library("lib-tv", "TV", LibraryType.TV_SHOWS, null)

    // --- Home ------------------------------------------------------------------------------

    @Test
    fun `signed out shows a message with retry and becomes the menu after signing in on the phone`() {
        backend.activeAccount.value = null
        val home = show(HomeScreen(carContext, env))

        val message = home.template<MessageTemplate>()
        assertEquals("Sign in on your phone first.", message.message.toString())
        assertEquals(listOf("Retry", "Downloads"), message.actionTitles())

        // Retry re-checks the account store: still nobody signed in, still the same message.
        message.action("Retry").tap()
        assertEquals("Sign in on your phone first.", home.template<MessageTemplate>().message.toString())

        backend.activeAccount.value = backend.alice
        idle()
        assertEquals(
            listOf("Continue watching", "Next up", "Films", "TV", "Downloads", "Search"),
            home.template<ListTemplate>().titles(),
        )
    }

    @Test
    fun `home lists shelves libraries downloads and search and hides switch account for one account`() {
        backend.resume = listOf(film(1), film(2))
        backend.downloads.value = listOf(download(film(3)), download(film(4), DownloadStatus.DOWNLOADING))
        val template = show(HomeScreen(carContext, env)).template<ListTemplate>()

        assertEquals(listOf("Continue watching", "Next up", "Films", "TV", "Downloads", "Search"), template.titles())
        assertEquals("2 in progress", template.row("Continue watching").text())
        assertEquals("Nothing queued up", template.row("Next up").text())
        assertEquals("1 available offline", template.row("Downloads").text())
        assertFalse(template.isLoading)
    }

    @Test
    fun `switch account appears with two accounts and overflow goes behind More`() {
        backend.accountList = listOf(backend.alice, backend.bob)
        val home = show(HomeScreen(carContext, env))

        // Seven entries, six allowed: five and a More row.
        assertEquals(
            listOf("Continue watching", "Next up", "Films", "TV", "Downloads", "More…"),
            home.template<ListTemplate>().titles(),
        )

        home.template<ListTemplate>().row("More…").tap()
        val more = top as HomeScreen
        assertEquals(2, more.step)
        assertEquals(listOf("Search", "Switch account"), more.template<ListTemplate>().titles())
        assertEquals("Alice · Home server", more.template<ListTemplate>().row("Switch account").text())
    }

    @Test
    fun `server error shows a friendly message and retry recovers`() {
        backend.failure = networkError()
        val home = show(HomeScreen(carContext, env))

        val message = home.template<MessageTemplate>()
        assertEquals("Can't reach the server. Check your connection and try again.", message.message.toString())
        assertEquals(listOf("Retry", "Downloads"), message.actionTitles())

        backend.failure = null
        message.action("Retry").tap()
        assertEquals(6, home.template<ListTemplate>().rows().size)
        // The message and then the list: two templates, so Home is now step 2.
        assertEquals(2, home.step)
    }

    @Test
    fun `downloads stay reachable from the error message when offline`() {
        backend.failure = networkError()
        backend.downloads.value = listOf(download(film(1)))
        val home = show(HomeScreen(carContext, env))

        home.template<MessageTemplate>().action("Downloads").tap()

        assertEquals(listOf("Film 1"), (top as DownloadsScreen).template<ListTemplate>().titles())
    }

    @Test
    fun `an account with no libraries still gets the rest of the menu`() {
        backend.libraryList = emptyList()
        val template = show(HomeScreen(carContext, env)).template<ListTemplate>()

        assertEquals(listOf("Continue watching", "Next up", "Downloads", "Search"), template.titles())
    }

    @Test
    fun `coming back to home refreshes the counts without changing the rows or the step`() {
        val home = show(HomeScreen(carContext, env))
        val before = home.template<ListTemplate>().titles()

        show(DownloadsScreen(carContext, env, 2)) // Home is covered…
        backend.resume = listOf(film(1))
        screenManager.pop() // …and comes back
        idle()

        val after = home.template<ListTemplate>()
        assertEquals(before, after.titles())
        assertEquals("1 in progress", after.row("Continue watching").text())
        assertEquals(1, home.step)
    }

    @Test
    fun `a failed refresh keeps the menu and says why`() {
        val home = show(HomeScreen(carContext, env))

        show(DownloadsScreen(carContext, env, 2))
        backend.failure = networkError()
        screenManager.pop()
        idle()

        assertEquals(6, home.template<ListTemplate>().rows().size)
        assertEquals(listOf("Can't reach the server. Check your connection and try again."), toasts)
    }

    @Test
    fun `continue watching opens a list that plays what is tapped`() {
        backend.resume = listOf(film(1).copy(resumePositionMs = 600_000), episode(1, 2))
        val home = show(HomeScreen(carContext, env))

        home.template<ListTemplate>().row("Continue watching").tap()
        val list = (top as EntryListScreen).template<ListTemplate>()
        assertEquals(listOf("Film 1", "Series 1 · S1 E2 · Episode 2"), list.titles())
        assertEquals("1 h 30 min left", list.rows()[0].text())

        list.rows()[1].tap()
        assertTrue(top is PlayerScreen)
        assertEquals("s1e2", backend.played.single().first.id)
    }

    @Test
    fun `an empty shelf says so`() {
        val list = show(EntryListScreen(carContext, env, 2, "Next up", emptyList(), "Nothing is next up."))

        val template = list.template<ListTemplate>()
        assertTrue(template.rows().isEmpty())
        assertEquals("Nothing is next up.", template.singleList!!.noItemsMessage.toString())
    }

    // --- Library ---------------------------------------------------------------------------

    @Test
    fun `a library that fits shows every item as a grid`() {
        backend.items = (1..4).map(::film)
        val template = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films))).template<GridTemplate>()

        assertEquals("Films", template.title.toString())
        assertEquals(listOf("Film 1", "Film 2", "Film 3", "Film 4"), template.titles())
        assertEquals("2001 · 1 h 40 min", template.singleList!!.gridItems()[0].text.toString())
        // No poster yet: a placeholder icon, never a missing image.
        assertEquals(GridItem.IMAGE_TYPE_ICON, template.singleList!!.gridItems()[0].imageType)
    }

    @Test
    fun `an empty library says so`() {
        val template = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films))).template<GridTemplate>()

        assertTrue(template.singleList!!.items.isEmpty())
        assertEquals("Nothing here yet.", template.singleList!!.noItemsMessage.toString())
    }

    @Test
    fun `a long film library pages with More until the template budget runs out`() {
        backend.items = (1..20).map(::film)
        val first = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films)))

        val page1 = first.template<GridTemplate>()
        assertEquals("Films · 1–5 of 20", page1.title.toString())
        assertEquals(listOf("Film 1", "Film 2", "Film 3", "Film 4", "Film 5", "More…"), page1.titles())

        page1.singleList!!.gridItems().last().tap()
        val second = top as LibraryScreen
        assertEquals(3, second.step)
        val page2 = second.template<GridTemplate>()
        assertEquals("Films · 6–10 of 20", page2.title.toString())
        assertEquals("More…", page2.titles().last())

        page2.singleList!!.gridItems().last().tap()
        val third = top as LibraryScreen
        assertEquals(4, third.step)
        // Step 4 is the last browsing step: fill the screen, no More.
        assertEquals((11..16).map { "Film $it" }, third.template<GridTemplate>().titles())
    }

    @Test
    fun `a tv library offers one more page only because a series still has to fit`() {
        backend.items = (1..20).map(::series)
        val first = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(tv)))
        assertEquals("More…", first.template<GridTemplate>().titles().last())

        first.template<GridTemplate>().singleList!!.gridItems().last().tap()
        val second = top as LibraryScreen
        assertEquals((6..11).map { "Series $it" }, second.template<GridTemplate>().titles())
    }

    @Test
    fun `the grid never asks for more posters than the cap`() {
        limits.grid = 1000
        show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films)))

        assertEquals(0 to 48, backend.pageRequests.single())
    }

    @Test
    fun `a library that fails to load shows retry and recovers`() {
        backend.failure = networkError()
        backend.items = (1..3).map(::film)
        val library = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films)))

        val message = library.template<MessageTemplate>()
        assertEquals("Can't reach the server. Check your connection and try again.", message.message.toString())
        assertEquals(listOf("Retry"), message.actionTitles())

        backend.failure = null
        message.action("Retry").tap()
        assertEquals(3, library.template<GridTemplate>().titles().size)
    }

    @Test
    fun `films play and series open`() {
        backend.items = listOf(film(1), series(1))
        val library = show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films)))

        library.template<GridTemplate>().singleList!!.gridItems()[1].tap()
        assertTrue(top is SeriesScreen)
        assertEquals(3, (top as SeriesScreen).step)

        screenManager.pop()
        library.template<GridTemplate>().singleList!!.gridItems()[0].tap()
        assertTrue(top is PlayerScreen)
    }

    @Test
    fun `search is offered while there is room for it and what it finds`() {
        assertEquals(1, show(LibraryScreen(carContext, env, 2, LibrarySource.Top(films))).template<GridTemplate>().actionStrip!!.actions.size)
        assertNull(show(LibraryScreen(carContext, env, 3, LibrarySource.Top(films))).template<GridTemplate>().actionStrip)
    }

    // --- Series ----------------------------------------------------------------------------

    @Test
    fun `a short series is sectioned by season`() {
        backend.episodeList = listOf(episode(1, 1, played = true), episode(1, 2), episode(2, 1))
        val template = show(SeriesScreen(carContext, env, 3, "series-1", "Series 1")).template<ListTemplate>()

        assertEquals("Series 1", template.title.toString())
        assertEquals(listOf("Season 1", "Season 2"), template.sectionedLists.map { it.header.toString() })
        val firstSeason = template.sectionedLists[0].itemList.rows()
        assertEquals(listOf("S1 E1 · Episode 1", "S1 E2 · Episode 2"), firstSeason.map { it.title.toString() })
        assertEquals("Watched", firstSeason[0].text())
    }

    @Test
    fun `a single season is a plain list and tapping an episode plays it`() {
        backend.episodeList = listOf(episode(1, 1), episode(1, 2))
        val series = show(SeriesScreen(carContext, env, 3, "series-1", "Series 1"))

        val template = series.template<ListTemplate>()
        assertTrue(template.sectionedLists.isEmpty())
        template.rows()[1].tap()

        assertTrue(top is PlayerScreen)
        assertEquals("s1e2", backend.played.single().first.id)
    }

    @Test
    fun `a long series lists its seasons and each opens its own episodes`() {
        backend.episodeList = (1..3).flatMap { season -> (1..4).map { episode(season, it, played = season == 1) } }
        val series = show(SeriesScreen(carContext, env, 3, "series-1", "Series 1"))

        val seasons = series.template<ListTemplate>()
        assertEquals(listOf("Season 1", "Season 2", "Season 3"), seasons.titles())
        assertEquals("4 episodes · watched", seasons.rows()[0].text())
        assertEquals("4 episodes · 4 to watch", seasons.rows()[1].text())

        seasons.rows()[1].tap()
        val season = top as SeriesScreen
        assertEquals(4, season.step)
        val episodes = season.template<ListTemplate>()
        assertEquals("Series 1 · Season 2", episodes.title.toString())
        assertEquals((1..4).map { "S2 E$it · Episode $it" }, episodes.titles())
    }

    @Test
    fun `with no step left for a season screen the list starts at the next episode to watch`() {
        backend.episodeList = (1..3).flatMap { season -> (1..4).map { episode(season, it, played = season == 1) } }
        val template = show(SeriesScreen(carContext, env, 4, "series-1", "Series 1")).template<ListTemplate>()

        // Step 4: six rows, starting at S2 E1, and no More (the next template must be the player).
        assertEquals(listOf("S2 E1", "S2 E2", "S2 E3", "S2 E4", "S3 E1", "S3 E2"), template.titles().map { it.substringBefore(" ·") })
    }

    @Test
    fun `a series with no episodes and one that fails to load`() {
        val empty = show(SeriesScreen(carContext, env, 3, "series-1", "Series 1")).template<ListTemplate>()
        assertEquals("No episodes found.", empty.singleList!!.noItemsMessage.toString())

        backend.failure = networkError()
        val failed = show(SeriesScreen(carContext, env, 3, "series-2", "Series 2")).template<MessageTemplate>()
        assertEquals(listOf("Retry"), failed.actionTitles())
    }

    // --- Downloads -------------------------------------------------------------------------

    @Test
    fun `downloads list only finished items and works with no server`() {
        backend.failure = networkError()
        backend.downloads.value = listOf(
            download(film(1)),
            download(film(2), DownloadStatus.DOWNLOADING),
            download(episode(1, 3).copy(resumePositionMs = 60_000)),
        )
        val downloads = show(DownloadsScreen(carContext, env, 2))

        val template = downloads.template<ListTemplate>()
        assertEquals(listOf("Film 1", "Series 1 · S1 E3 · Episode 3"), template.titles())
        assertEquals("2001 · 1 h 40 min · 720p", template.rows()[0].text())

        template.rows()[1].tap()
        assertTrue(top is PlayerScreen)
        assertEquals(60_000, backend.played.single().first.resumePositionMs)
    }

    @Test
    fun `no downloads explains where to get them and a finished download appears`() {
        val downloads = show(DownloadsScreen(carContext, env, 2))
        assertEquals(
            "No downloads yet. Download films and episodes in AAFin on your phone.",
            downloads.template<ListTemplate>().singleList!!.noItemsMessage.toString(),
        )

        backend.downloads.value = listOf(download(film(1)))
        idle()
        assertEquals(listOf("Film 1"), downloads.template<ListTemplate>().titles())
    }

    @Test
    fun `long downloads list pages`() {
        backend.downloads.value = (1..8).map { download(film(it)) }
        val template = show(DownloadsScreen(carContext, env, 2)).template<ListTemplate>()

        assertEquals(6, template.rows().size)
        assertEquals("More…", template.titles().last())
    }

    // --- Search ----------------------------------------------------------------------------

    @Test
    fun `search waits for a pause in typing then lists results`() {
        backend.searchResults = listOf(film(1), series(1))
        val search = show(SearchScreen(carContext, env, 2))
        val done = object : OnDoneCallback {}

        val empty = search.template<SearchTemplate>()
        assertEquals("Type the name of a film, series or episode.", empty.itemList!!.noItemsMessage.toString())

        empty.searchCallbackDelegate.sendSearchTextChanged("fi", done)
        empty.searchCallbackDelegate.sendSearchTextChanged("film", done)
        idle()
        assertTrue(backend.searches.isEmpty())

        advance(600)
        assertEquals(listOf("film"), backend.searches)
        val results = search.template<SearchTemplate>()
        assertEquals(listOf("Film 1", "Series 1"), results.itemList!!.rows().map { it.title.toString() })

        results.itemList!!.rows()[1].tap()
        assertTrue(top is SeriesScreen)
    }

    @Test
    fun `search shows nothing found and server errors in place`() {
        val search = show(SearchScreen(carContext, env, 2))
        val done = object : OnDoneCallback {}

        search.template<SearchTemplate>().searchCallbackDelegate.sendSearchSubmitted("zzz", done)
        idle()
        assertEquals("Nothing found for “zzz”.", search.template<SearchTemplate>().itemList!!.noItemsMessage.toString())

        backend.failure = networkError()
        search.template<SearchTemplate>().searchCallbackDelegate.sendSearchSubmitted("abc", done)
        idle()
        assertEquals(
            "Can't reach the server. Check your connection and try again.",
            search.template<SearchTemplate>().itemList!!.noItemsMessage.toString(),
        )
        // However much the results change, search stays one template step.
        assertEquals(2, search.step)
    }

    // --- Accounts --------------------------------------------------------------------------

    @Test
    fun `switching account marks the current one and goes back to home`() {
        backend.accountList = listOf(backend.alice, backend.bob)
        val home = show(HomeScreen(carContext, env))
        val accounts = show(AccountsScreen(carContext, env, 2))

        val template = accounts.template<ListTemplate>()
        assertEquals(listOf("Alice", "Bob"), template.titles())
        assertEquals(0, template.singleList!!.selectedIndex)

        template.singleList!!.select(1)

        assertEquals(backend.bob, backend.activeAccount.value)
        assertEquals(1, backend.stops)
        assertSame(home, top)
    }

    // --- Template budget -------------------------------------------------------------------

    @Test
    fun `running out of template steps restarts from home instead of breaking the rule`() {
        backend.items = listOf(series(1))
        // A TV page at step 4: a series would be a fifth list, which the host forbids.
        val library = show(LibraryScreen(carContext, env, 4, LibrarySource.Top(tv)))

        library.template<GridTemplate>().singleList!!.gridItems()[0].tap()

        // A navigation template was sent first (it resets the host's count)…
        val reset = screenManager.screensPushed.single { it is FlowResetScreen }
        assertTrue(templatesSentBy(reset).single() is NavigationTemplate)
        // …and then the whole stack was replaced by a fresh Home, one step in.
        assertEquals(1, screenManager.stackSize)
        assertEquals(2, (top as HomeScreen).step)
        assertEquals(6, top.template<ListTemplate>().rows().size)
    }

    @Test
    fun `home never drifts past step two so a library a series and the player always fit`() {
        backend.activeAccount.value = null
        val home = show(HomeScreen(carContext, env, firstStep = 2))
        home.template<MessageTemplate>() // the signed-out message, at step 2

        backend.activeAccount.value = backend.alice
        idle()
        // Showing the menu now would make Home step 3, so the flow restarts instead:
        // a navigation template, then a fresh Home at step 2 showing the menu.
        assertTrue(screenManager.screensPushed.any { it is FlowResetScreen })
        assertTrue(home in screenManager.screensRemoved)
        assertEquals(1, screenManager.stackSize)
        assertEquals(2, (top as HomeScreen).step)
        assertEquals(6, top.template<ListTemplate>().rows().size)
    }
}
