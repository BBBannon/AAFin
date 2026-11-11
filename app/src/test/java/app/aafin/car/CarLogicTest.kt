package app.aafin.car

import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure rules behind the car screens. */
class CarLogicTest {

    private fun episode(season: Int, number: Int, played: Boolean = false, seasonId: String? = "season-$season") = MediaEntry(
        id = "s${season}e$number",
        name = "Episode $number",
        kind = ItemKind.EPISODE,
        played = played,
        seriesId = "series",
        seriesName = "The Show",
        seasonId = seasonId,
        seasonName = "Season $season",
        indexNumber = number,
        parentIndexNumber = season,
    )

    // --- Template budget -------------------------------------------------------------------

    @Test
    fun `only a message or the player may be the fifth template`() {
        assertTrue(FlowBudget.allows(4, terminal = false))
        assertFalse(FlowBudget.allows(5, terminal = false))
        assertTrue(FlowBudget.allows(5, terminal = true))
        assertFalse(FlowBudget.allows(6, terminal = true))
    }

    @Test
    fun `a further page is offered only if what it lists can still be opened`() {
        // Home(1) > Films(2): pages at 3 and 4, then the player.
        assertTrue(FlowBudget.canPage(step = 2, stepsBelow = FlowBudget.stepsBelow(LibraryType.MOVIES)))
        assertTrue(FlowBudget.canPage(step = 3, stepsBelow = 0))
        assertFalse(FlowBudget.canPage(step = 4, stepsBelow = 0))
        // Home(1) > TV(2) > page(3) > series(4) > player: one extra page only.
        assertTrue(FlowBudget.canPage(step = 2, stepsBelow = FlowBudget.stepsBelow(LibraryType.TV_SHOWS)))
        assertFalse(FlowBudget.canPage(step = 3, stepsBelow = 1))
        // Collections need two more screens (the collection, then a series): no extra pages from step 2.
        assertFalse(FlowBudget.canPage(step = 2, stepsBelow = FlowBudget.stepsBelow(LibraryType.BOX_SETS)))
    }

    // --- Paging ----------------------------------------------------------------------------

    @Test
    fun `a list that fits is shown whole`() {
        assertEquals(PageWindow(6, hasMore = false), Paging.window(remaining = 6, limit = 6, canPage = true))
        assertEquals(PageWindow(0, hasMore = false), Paging.window(remaining = 0, limit = 6, canPage = true))
    }

    @Test
    fun `a longer list gives up its last slot to More`() {
        assertEquals(PageWindow(5, hasMore = true), Paging.window(remaining = 7, limit = 6, canPage = true))
    }

    @Test
    fun `with no budget for another page the screen is simply filled`() {
        assertEquals(PageWindow(6, hasMore = false), Paging.window(remaining = 300, limit = 6, canPage = false))
    }

    @Test
    fun `host limits are kept within a usable range`() {
        assertEquals(2, Paging.usableLimit(0, cap = 48))
        assertEquals(6, Paging.usableLimit(6, cap = 48))
        assertEquals(48, Paging.usableLimit(1000, cap = 48))
    }

    @Test
    fun `range label only appears when part of the list is hidden`() {
        assertNull(Paging.rangeLabel(start = 0, count = 4, total = 4))
        assertEquals("1–5 of 300", Paging.rangeLabel(start = 0, count = 5, total = 300))
        assertEquals("6–10 of 300", Paging.rangeLabel(start = 5, count = 5, total = 300))
        assertNull(Paging.rangeLabel(start = 0, count = 0, total = 0))
    }

    // --- Text ------------------------------------------------------------------------------

    @Test
    fun `clock formats minutes and hours`() {
        assertEquals("0:00", CarFormat.clock(0))
        assertEquals("0:00", CarFormat.clock(-5_000))
        assertEquals("2:05", CarFormat.clock(125_000))
        assertEquals("59:59", CarFormat.clock(3_599_999))
        assertEquals("1:02:03", CarFormat.clock(3_723_000))
        assertEquals("+0:45", CarFormat.signedClock(45_000))
        assertEquals("−1:30", CarFormat.signedClock(-90_000))
    }

    @Test
    fun `durations read naturally`() {
        assertEquals("1 min", CarFormat.duration(10_000))
        assertEquals("45 min", CarFormat.duration(45 * 60_000L))
        assertEquals("2 h", CarFormat.duration(120 * 60_000L))
        assertEquals("1 h 52 min", CarFormat.duration(112 * 60_000L))
    }

    @Test
    fun `episode rows read S1 E3 then the title`() {
        assertEquals("S1 E3 · Episode 3", CarFormat.episodeTitle(episode(1, 3)))
        assertEquals("E3 · Episode 3", CarFormat.episodeTitle(episode(1, 3).copy(parentIndexNumber = null)))
        assertEquals("Pilot", CarFormat.episodeTitle(episode(1, 1).copy(indexNumber = null, name = "Pilot")))
        assertEquals("The Show · S1 E3 · Episode 3", CarFormat.entryTitle(episode(1, 3)))
        assertEquals("Heat", CarFormat.entryTitle(MediaEntry("m", "Heat", ItemKind.MOVIE)))
    }

    @Test
    fun `status line shows progress watched state or length`() {
        val film = MediaEntry("m", "Heat", ItemKind.MOVIE, productionYear = 1995, runtimeMs = 170 * 60_000L)
        assertEquals("1995 · 2 h 50 min", CarFormat.status(film))
        assertEquals("1 h 50 min left", CarFormat.status(film.copy(resumePositionMs = 60 * 60_000L)))
        assertEquals("Watched · 2 h 50 min", CarFormat.status(film.copy(played = true)))
        assertEquals("3 to watch", CarFormat.status(MediaEntry("s", "Show", ItemKind.SERIES, unplayedCount = 3)))
        assertEquals("Watched", CarFormat.status(MediaEntry("s", "Show", ItemKind.SERIES, unplayedCount = 0)))
        assertNull(CarFormat.status(MediaEntry("e", "Ep", ItemKind.EPISODE)))
    }

    // --- Series ----------------------------------------------------------------------------

    @Test
    fun `episodes are grouped by season in order`() {
        val groups = SeriesLogic.groupBySeason(listOf(episode(1, 1), episode(1, 2), episode(2, 1)))
        assertEquals(listOf("Season 1", "Season 2"), groups.map { it.title })
        assertEquals(listOf(2, 1), groups.map { it.episodes.size })
    }

    @Test
    fun `seasons without ids fall back to their numbers and specials are named`() {
        val groups = SeriesLogic.groupBySeason(
            listOf(episode(0, 1, seasonId = null).copy(seasonName = null), episode(3, 1, seasonId = null).copy(seasonName = null)),
        )
        assertEquals(listOf("Specials", "Season 3"), groups.map { it.title })
    }

    @Test
    fun `a short series is one sectioned list`() {
        val episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1))
        val layout = SeriesLogic.layout(episodes, seasonKey = null, limit = 6, canOpenSeason = true)
        assertTrue(layout is SeriesLayout.Sectioned)
        assertEquals(2, (layout as SeriesLayout.Sectioned).seasons.size)
    }

    @Test
    fun `a long series lists its seasons when there is room to open one`() {
        val episodes = (1..3).flatMap { season -> (1..4).map { episode(season, it) } }
        assertTrue(SeriesLogic.layout(episodes, null, limit = 6, canOpenSeason = true) is SeriesLayout.SeasonPicker)
    }

    @Test
    fun `with no room for a season screen a long series starts at the next episode to watch`() {
        val episodes = (1..3).flatMap { season -> (1..4).map { episode(season, it, played = season == 1) } }
        val layout = SeriesLogic.layout(episodes, null, limit = 6, canOpenSeason = false) as SeriesLayout.Episodes
        assertEquals(12, layout.episodes.size)
        assertEquals(4, layout.start) // S2 E1
    }

    @Test
    fun `a picked season shows only its own episodes`() {
        val episodes = (1..2).flatMap { season -> (1..10).map { episode(season, it, played = season == 2 && it <= 8) } }
        val layout = SeriesLogic.layout(episodes, seasonKey = "season-2", limit = 6, canOpenSeason = false) as SeriesLayout.Episodes
        assertEquals(10, layout.episodes.size)
        assertTrue(layout.episodes.all { it.parentIndexNumber == 2 })
        // Next to watch is E9 (index 8), but the window is pulled back so the screen is full.
        assertEquals(4, layout.start)
    }

    @Test
    fun `window start is zero when everything fits or everything is watched`() {
        assertEquals(0, SeriesLogic.windowStart(listOf(episode(1, 1, played = true), episode(1, 2)), limit = 6))
        assertEquals(0, SeriesLogic.windowStart((1..10).map { episode(1, it, played = true) }, limit = 6))
    }

    @Test
    fun `next episode follows list order and crosses seasons`() {
        val episodes = listOf(episode(1, 1), episode(1, 2), episode(2, 1))
        assertEquals("s1e2", SeriesLogic.nextEpisode(episodes[0], episodes)?.id)
        assertEquals("s2e1", SeriesLogic.nextEpisode(episodes[1], episodes)?.id)
        assertNull(SeriesLogic.nextEpisode(episodes[2], episodes))
    }

    @Test
    fun `next episode falls back to numbering when the current one is not in the list`() {
        // Offline: only some episodes are downloaded.
        val downloaded = listOf(episode(2, 3), episode(1, 5), episode(1, 2))
        assertEquals("s1e5", SeriesLogic.nextEpisode(episode(1, 3), downloaded)?.id)
        assertEquals("s2e3", SeriesLogic.nextEpisode(episode(1, 5).copy(id = "other"), downloaded)?.id)
        assertNull(SeriesLogic.nextEpisode(episode(2, 3).copy(id = "other"), downloaded))
        assertNull(SeriesLogic.nextEpisode(MediaEntry("m", "Film", ItemKind.MOVIE), downloaded))
    }

    // --- Car identity ----------------------------------------------------------------------

    @Test
    fun `car key prefers the model the car reports`() {
        val identity = CarIdentity.of(CarModel("Dacia", "Duster", 2025), SurfaceSpec(1280, 720, 160))
        assertEquals("model:dacia|duster|2025", identity.key)
        assertEquals("Dacia Duster", identity.suggestedName)
    }

    @Test
    fun `car key falls back to the screen and then to unknown`() {
        val blank = CarModel(manufacturer = " ", name = null, year = 2025)
        val byScreen = CarIdentity.of(blank, SurfaceSpec(1280, 720, 160))
        assertEquals("screen:1280x720@160", byScreen.key)
        assertEquals("Car screen 1280×720", byScreen.suggestedName)

        assertEquals(CarIdentity(CarIdentity.UNKNOWN_KEY, "My car"), CarIdentity.of(null, null))
    }

    @Test
    fun `car key is the same however the phone is connected`() {
        // Nothing about the transport feeds the key: same car and screen, same key.
        val wired = CarIdentity.of(CarModel("Dacia", "Duster", 2025), SurfaceSpec(1280, 720, 160))
        val wireless = CarIdentity.of(CarModel("Dacia", "Duster", 2025), SurfaceSpec(1280, 720, 160))
        assertEquals(wired, wireless)
    }

    // --- Surface ---------------------------------------------------------------------------

    @Test
    fun `dragging right seeks forwards in proportion to the screen width`() {
        assertEquals(90_000, SeekGesture.seekMs(640f, surfaceWidthPx = 1280))
        assertEquals(-45_000, SeekGesture.seekMs(-320f, surfaceWidthPx = 1280))
        assertEquals(0, SeekGesture.seekMs(5f, surfaceWidthPx = 1280)) // a twitch, not a drag
        assertEquals(0, SeekGesture.seekMs(500f, surfaceWidthPx = 0))
    }

    @Test
    fun `insets keep the picture inside the visible area`() {
        assertEquals(SurfaceInsets(0, 96, 0, 0), SurfaceInsets.of(0, 96, 1280, 720, 1280, 720))
        assertEquals(SurfaceInsets(500, 0, 0, 0), SurfaceInsets.of(500, 0, 1280, 720, 1280, 720))
    }

    @Test
    fun `an unknown tiny or out of range visible area means no insets`() {
        assertEquals(SurfaceInsets.NONE, SurfaceInsets.of(0, 0, 0, 0, 1280, 720))
        assertEquals(SurfaceInsets.NONE, SurfaceInsets.of(1200, 0, 1280, 720, 1280, 720))
        assertEquals(SurfaceInsets(0, 0, 0, 0), SurfaceInsets.of(-50, -50, 5000, 5000, 1280, 720))
    }

    @Test
    fun `a tap brings the overlay back before it pauses`() {
        assertFalse(OverlayRule.tapTogglesPlayback(overlayVisible = false))
        assertTrue(OverlayRule.tapTogglesPlayback(overlayVisible = true))
        assertTrue(OverlayRule.mayAutoHide(isPlaying = true))
        assertFalse(OverlayRule.mayAutoHide(isPlaying = false))
    }
}
