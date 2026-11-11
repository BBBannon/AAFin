package app.aafin.phone

import app.aafin.data.downloads.DownloadQuality
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.jellyfin.SourceInfo
import app.aafin.data.playback.QualityTier
import app.cash.turbine.test
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseAndManageViewModelsTest {

    private val dispatcher = StandardTestDispatcher()
    private val phone = FakePhone.signedIn(FakePhone.BOB)

    @Before
    fun setUp() = Dispatchers.setMain(dispatcher)

    @After
    fun tearDown() = Dispatchers.resetMain()

    private fun TestScope.requester() = DownloadRequester(phone.backend, backgroundScope)

    // --- The download choice -----------------------------------------------------------------

    @Test
    fun `the dialog opens with 720p selected, then fills in the sizes`() = runTest(dispatcher) {
        val film = fakeFilm(1, "Big Film")
        phone.library.sources = mapOf(film.id to SourceInfo(sizeBytes = 12_000_000_000L, bitrate = 13_000_000))
        val requester = requester()

        requester.request("Big Film", listOf(film))

        val opened = requester.dialog.value!!
        assertEquals(DownloadQuality.P720, opened.selected)
        assertTrue(opened.checkingSize)
        assertNull(opened.options.first { it.quality == DownloadQuality.ORIGINAL }.sizeBytes)
        assertEquals(3_600_000_000L, opened.options.first { it.quality == DownloadQuality.P720 }.sizeBytes)

        runCurrent()
        val sized = requester.dialog.value!!
        assertFalse(sized.checkingSize)
        assertEquals(12_000_000_000L, sized.options.first { it.quality == DownloadQuality.ORIGINAL }.sizeBytes)
        assertEquals(DownloadQuality.P720, sized.selected)
    }

    @Test
    fun `sizes that cannot be fetched are left unknown and the dialog still works`() = runTest(dispatcher) {
        val requester = requester()
        requester.request("Film", listOf(fakeFilm(1, "Film"))) // the fake library knows no source for it
        runCurrent()

        val dialog = requester.dialog.value!!
        assertFalse(dialog.checkingSize)
        assertNull(dialog.options.first { it.quality == DownloadQuality.ORIGINAL }.sizeBytes)
        assertNotNull(dialog.options.first { it.quality == DownloadQuality.P720 }.sizeBytes)
    }

    @Test
    fun `confirming queues the download at the selected quality`() = runTest(dispatcher) {
        val film = fakeFilm(1, "Big Film")
        val requester = requester()

        requester.messages.test {
            requester.request("Big Film", listOf(film))
            requester.confirm()
            assertNull(requester.dialog.value)
            assertEquals("Downloading Big Film (720p). See the Downloads tab.", awaitItem())
        }
        assertEquals(listOf(film to DownloadQuality.P720), phone.downloads.enqueued)
        assertNull(requester.progress.value)
    }

    @Test
    fun `Original can be chosen when allowed, and not when the account may not download`() = runTest(dispatcher) {
        val requester = requester()

        requester.request("Allowed", listOf(fakeFilm(1, "Allowed")))
        requester.select(DownloadQuality.ORIGINAL)
        assertEquals(DownloadQuality.ORIGINAL, requester.dialog.value!!.selected)
        requester.confirm()
        runCurrent()
        assertEquals(DownloadQuality.ORIGINAL, phone.downloads.enqueued.single().second)

        requester.request("Locked", listOf(fakeFilm(2, "Locked", canDownload = false)))
        requester.select(DownloadQuality.ORIGINAL)
        val dialog = requester.dialog.value!!
        assertEquals(DownloadQuality.P720, dialog.selected)
        assertFalse(dialog.options.first { it.quality == DownloadQuality.ORIGINAL }.enabled)
    }

    @Test
    fun `dismissing downloads nothing`() = runTest(dispatcher) {
        val requester = requester()
        requester.request("Film", listOf(fakeFilm(1, "Film")))
        requester.dismiss()
        requester.confirm()
        runCurrent()

        assertNull(requester.dialog.value)
        assertTrue(phone.downloads.enqueued.isEmpty())
    }

    @Test
    fun `a season skips episodes already downloaded and queues the rest in order`() = runTest(dispatcher) {
        val episodes = (1..4).map { fakeEpisode(it, it) }
        phone.downloads.downloads.value = listOf(fakeDownload(episodes[1], DownloadStatus.COMPLETED))
        val requester = requester()

        requester.messages.test {
            requester.request("Season 1", episodes)
            assertEquals(listOf(episodes[0], episodes[2], episodes[3]), requester.dialog.value!!.entries)
            requester.confirm(notificationsDenied = true)
            val message = awaitItem()
            assertTrue(message.startsWith("Downloading 3 episodes (720p)."))
            assertTrue(message.contains("Notifications are off"))
        }
        assertEquals(listOf(episodes[0], episodes[2], episodes[3]), phone.downloads.enqueued.map { it.first })
    }

    @Test
    fun `asking for something already downloaded says so instead of opening the dialog`() = runTest(dispatcher) {
        val film = fakeFilm(1, "Film")
        phone.downloads.downloads.value = listOf(fakeDownload(film, DownloadStatus.DOWNLOADING))
        val requester = requester()

        requester.messages.test {
            requester.request("Film", listOf(film))
            assertEquals("That is already in your downloads.", awaitItem())
        }
        assertNull(requester.dialog.value)
    }

    @Test
    fun `a failure part-way through a season stops and reports how far it got`() = runTest(dispatcher) {
        val episodes = (1..3).map { fakeEpisode(it, it) }
        phone.downloads.failures = mapOf(episodes[1].id to JellyfinException(JellyfinException.Kind.NETWORK))
        val requester = requester()

        requester.messages.test {
            requester.request("Season 1", episodes)
            requester.confirm()
            assertEquals("Added 1 of 3, then stopped. ${JellyfinException.Kind.NETWORK.userMessage}", awaitItem())
        }
        assertEquals(listOf(episodes[0]), phone.downloads.enqueued.map { it.first })
        assertNull(requester.progress.value)
    }

    @Test
    fun `a storage failure is reported in plain words`() = runTest(dispatcher) {
        val film = fakeFilm(1, "Film")
        phone.downloads.failures = mapOf(film.id to IOException("ENOSPC"))
        val requester = requester()

        requester.messages.test {
            requester.request("Film", listOf(film))
            requester.confirm()
            assertEquals(STORAGE_ERROR_MESSAGE, awaitItem())
        }
    }

    // --- Browsing ----------------------------------------------------------------------------

    @Test
    fun `libraries load, and a failure can be retried`() = runTest(dispatcher) {
        phone.library.failure = JellyfinException(JellyfinException.Kind.NETWORK)
        val viewModel = LibrariesViewModel(phone.backend)
        assertEquals(Loadable.Loading, viewModel.state.value)
        runCurrent()
        assertEquals(Loadable.Failed(JellyfinException.Kind.NETWORK.userMessage), viewModel.state.value)

        phone.library.failure = null
        viewModel.load()
        runCurrent()
        assertEquals(listOf("Films", "TV"), (viewModel.state.value as Loadable.Ready).value.map { it.library.name })
    }

    @Test
    fun `a library loads a page at a time until the end`() = runTest(dispatcher) {
        phone.library.contents = mapOf("lib-films" to (1..25).map { fakeFilm(it, "Film $it") })
        val viewModel = GridViewModel(phone.backend, "lib-films", "Films", LibraryType.MOVIES, pageSize = 10)
        assertTrue(viewModel.state.value.loading)

        runCurrent()
        assertEquals(10, viewModel.state.value.items.size)
        assertEquals(25, viewModel.state.value.total)
        assertFalse(viewModel.state.value.endReached)

        viewModel.loadMore()
        viewModel.loadMore() // ignored while a page is on its way
        runCurrent()
        viewModel.loadMore()
        runCurrent()
        assertEquals((1..25).map { "Film $it" }, viewModel.state.value.items.map { it.entry.name })
        assertTrue(viewModel.state.value.endReached)

        viewModel.loadMore()
        runCurrent()
        assertEquals(listOf(0 to 10, 10 to 10, 20 to 10), phone.library.pageRequests)
    }

    @Test
    fun `a page that fails keeps what was loaded and can be retried`() = runTest(dispatcher) {
        phone.library.contents = mapOf("lib-films" to (1..15).map { fakeFilm(it, "Film $it") })
        val viewModel = GridViewModel(phone.backend, "lib-films", "Films", LibraryType.MOVIES, pageSize = 10)
        runCurrent()

        phone.library.failure = JellyfinException(JellyfinException.Kind.SERVER)
        viewModel.loadMore()
        runCurrent()
        assertEquals(10, viewModel.state.value.items.size)
        assertEquals(JellyfinException.Kind.SERVER.userMessage, viewModel.state.value.error)

        phone.library.failure = null
        viewModel.loadMore()
        runCurrent()
        assertEquals(15, viewModel.state.value.items.size)
        assertNull(viewModel.state.value.error)
    }

    private fun season(n: Int, played: Boolean) = MediaEntry(
        id = fakeId(9000 + n),
        name = if (n == 0) "Specials" else "Season $n",
        kind = ItemKind.SEASON,
        indexNumber = n,
        played = played,
        unplayedCount = if (played) 0 else 5,
    )

    @Test
    fun `a series opens on the first season with something left to watch`() = runTest(dispatcher) {
        val seasons = listOf(season(0, played = false), season(1, played = true), season(2, played = false))
        phone.library.seasonList = seasons
        phone.library.episodeLists = mapOf(seasons[2].id to listOf(fakeEpisode(21, 1, season = 2), fakeEpisode(22, 2, season = 2)))
        val viewModel = SeriesViewModel(phone.backend, fakeId(9000))
        runCurrent()

        assertEquals(seasons[2].id, viewModel.state.value.selectedSeasonId)
        assertEquals(2, (viewModel.state.value.episodes as Loadable.Ready).value.size)

        viewModel.downloadSeason()
        val dialog = viewModel.downloads.dialog.value!!
        assertEquals("Season 2", dialog.title)
        assertEquals(2, dialog.entries.size)
        assertEquals(DownloadQuality.P720, dialog.selected)
    }

    @Test
    fun `a series with no seasons lists all its episodes`() = runTest(dispatcher) {
        phone.library.episodeLists = mapOf(null to listOf(fakeEpisode(1, 1)))
        val viewModel = SeriesViewModel(phone.backend, fakeId(9000))
        runCurrent()

        assertNull(viewModel.state.value.selectedSeasonId)
        assertEquals(1, (viewModel.state.value.episodes as Loadable.Ready).value.size)
    }

    @Test
    fun `search waits for typing to pause`() = runTest(dispatcher) {
        phone.library.searchResults = listOf(fakeFilm(1, "Alien"))
        val viewModel = SearchViewModel(phone.backend)

        viewModel.onQueryChange("al")
        advanceTimeBy(SearchViewModel.TYPING_PAUSE_MS / 2)
        viewModel.onQueryChange("alien ")
        assertEquals(Loadable.Loading, viewModel.state.value.results)
        advanceTimeBy(SearchViewModel.TYPING_PAUSE_MS + 1)

        assertEquals(listOf("alien"), phone.library.searches)
        assertEquals("Alien", (viewModel.state.value.results as Loadable.Ready).value.single().entry.name)

        viewModel.onQueryChange("")
        assertNull(viewModel.state.value.results)
    }

    // --- Downloads manager -------------------------------------------------------------------

    @Test
    fun `downloads are listed newest first with storage figures`() = runTest(dispatcher) {
        val old = fakeDownload(fakeFilm(1, "Old"), DownloadStatus.COMPLETED, createdAtMs = 1)
        val new = fakeDownload(fakeFilm(2, "New"), DownloadStatus.PAUSED, createdAtMs = 2)
        phone.downloads.downloads.value = listOf(old, new)
        phone.downloads.usedBytes = 5_000_000_000
        phone.downloads.freeBytes = 40_000_000_000
        val viewModel = DownloadsViewModel(phone.backend)
        runCurrent()

        assertEquals(listOf("New", "Old"), viewModel.state.value.items.map { it.metadata.entry.name })
        assertEquals(StorageUi(5_000_000_000, 40_000_000_000), viewModel.state.value.storage)

        // Deleting changes the list, which re-measures the storage.
        phone.downloads.usedBytes = 1_000_000_000
        viewModel.delete(old.itemId)
        runCurrent()
        assertEquals(listOf(old.itemId), phone.downloads.deleted)
        assertEquals(1_000_000_000L, viewModel.state.value.storage?.usedBytes)
    }

    @Test
    fun `pause and resume go straight to the downloads`() = runTest(dispatcher) {
        val viewModel = DownloadsViewModel(phone.backend)
        viewModel.pause("a")
        viewModel.resume("b")
        assertEquals(listOf("a"), phone.downloads.paused)
        assertEquals(listOf("b"), phone.downloads.resumed)
    }

    @Test
    fun `retry restarts a failed download of the active account`() = runTest(dispatcher) {
        val failed = fakeDownload(fakeFilm(1, "Film"), DownloadStatus.FAILED)
        val viewModel = DownloadsViewModel(phone.backend)

        viewModel.retry(failed)
        runCurrent()
        assertEquals(listOf(failed.itemId), phone.downloads.retried)
    }

    @Test
    fun `retry explains itself when the download belongs to another account`() = runTest(dispatcher) {
        val bobs = fakeDownload(fakeFilm(1, "Film"), DownloadStatus.FAILED, accountId = FakePhone.BOB.id)
        val orphan = fakeDownload(fakeFilm(2, "Film"), DownloadStatus.FAILED, accountId = "server-9:gone")
        val viewModel = DownloadsViewModel(phone.backend)

        viewModel.messages.test {
            viewModel.retry(bobs)
            assertEquals("Switch to bob on Home to retry this download.", awaitItem())
            viewModel.retry(orphan)
            assertTrue(awaitItem().contains("has been removed"))
        }
        assertTrue(phone.downloads.retried.isEmpty())
    }

    @Test
    fun `a retry that fails says why`() = runTest(dispatcher) {
        val failed = fakeDownload(fakeFilm(1, "Film"), DownloadStatus.FAILED)
        phone.downloads.failures = mapOf(failed.itemId to JellyfinException(JellyfinException.Kind.UNAUTHORISED))
        val viewModel = DownloadsViewModel(phone.backend)

        viewModel.messages.test {
            viewModel.retry(failed)
            assertEquals(JellyfinException.Kind.UNAUTHORISED.userMessage, awaitItem())
        }
    }

    // --- Cars and settings -------------------------------------------------------------------

    @Test
    fun `cars are listed most recently used first and can be renamed, retuned and deleted`() = runTest(dispatcher) {
        phone.carProfiles.getOrCreate("model:dacia|duster|2025", "Dacia Duster")
        phone.carProfiles.getOrCreate("screen:800x480@160", "Car 800 x 480")
        phone.carProfiles.markUsed("model:dacia|duster|2025", nowMs = 1_000)
        phone.carProfiles.markUsed("screen:800x480@160", nowMs = 2_000)
        val viewModel = CarsViewModel(phone.backend)
        assertNull(viewModel.profiles.value)
        runCurrent()

        assertEquals(listOf("Car 800 x 480", "Dacia Duster"), viewModel.profiles.value!!.map { it.name })
        assertEquals(2_000L, viewModel.profiles.value!!.first().lastUsedAtMs)

        viewModel.rename("screen:800x480@160", "Second car")
        viewModel.setQuality("screen:800x480@160", QualityTier.P1080_8M)
        viewModel.delete("model:dacia|duster|2025")
        runCurrent()

        val car = viewModel.profiles.value!!.single()
        assertEquals("Second car", car.name)
        assertEquals(QualityTier.P1080_8M, car.quality)
    }

    @Test
    fun `settings show the default quality and each account's server version`() = runTest(dispatcher) {
        val viewModel = SettingsViewModel(phone.backend)
        advanceUntilIdle()

        val state = viewModel.state.value
        assertEquals(QualityTier.P720_4M, state.defaultQuality)
        assertEquals("1.0-test", state.appVersion)
        assertEquals(listOf("alice", "bob"), state.accounts.map { it.account.userName })
        assertTrue(state.accounts.all { it.serverVersion == "Jellyfin 12.4.0" })

        viewModel.setDefaultQuality(QualityTier.P1080_15M)
        runCurrent()
        assertEquals(QualityTier.P1080_15M, viewModel.state.value.defaultQuality)
        assertEquals(QualityTier.P1080_15M, phone.carProfiles.defaultQuality.first())
    }

    @Test
    fun `settings say when a server's version cannot be read`() = runTest(dispatcher) {
        phone.gateway.servers = emptyMap()
        val viewModel = SettingsViewModel(phone.backend)
        advanceUntilIdle()

        assertTrue(viewModel.state.value.accounts.all { it.serverVersion.startsWith("Version unknown") })
    }
}
