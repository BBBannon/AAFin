package app.aafin.car

import androidx.car.app.HandshakeInfo
import androidx.car.app.model.Action
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.MessageTemplate
import androidx.car.app.navigation.model.NavigationTemplate
import app.aafin.R
import app.aafin.car.screens.ChoiceScreen
import app.aafin.car.screens.HomeScreen
import app.aafin.car.screens.PlayerOptionsScreen
import app.aafin.car.screens.PlayerScreen
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.playback.PlayMethod
import app.aafin.data.playback.PlaybackError
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.StreamTrack
import app.aafin.data.playback.SubtitleDelivery
import kotlinx.coroutines.flow.update
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The player screen, its options, and what happens when an episode ends. */
@RunWith(RobolectricTestRunner::class)
class CarPlayerScreensTest : CarScreenTestBase() {

    private val english = StreamTrack(1, "English 5.1", "eng")
    private val french = StreamTrack(2, "French", "fra")
    private val subsEnglish = StreamTrack(3, "English", "eng", delivery = SubtitleDelivery.SIDE_LOADED)
    private val subsPgs = StreamTrack(4, "French PGS", "fra", delivery = SubtitleDelivery.BURN_IN)

    private fun withTracks() = backend.playback.update {
        it.copy(
            audioTracks = listOf(english, french),
            subtitleTracks = listOf(subsEnglish, subsPgs),
            audioStreamIndex = 1,
            subtitleStreamIndex = null,
            playMethod = PlayMethod.TRANSCODE,
        )
    }

    // --- Player ----------------------------------------------------------------------------

    @Test
    fun `player starts playback at this car's quality and shows the control strips`() {
        quality.current = QualityTier.P1080_8M
        val player = show(PlayerScreen(carContext, env, film(1)))

        assertEquals(listOf(film(1) to QualityTier.P1080_8M), backend.played)

        val template = player.template<NavigationTemplate>()
        // Four in the action strip (the template's limit): back, -10 s, pause, +30 s.
        val strip = template.actionStrip!!.actions
        assertEquals(4, strip.size)
        assertEquals(listOf(null, "−10 s", null, "+30 s"), strip.map { it.title?.toString() })
        assertTrue(strip.all { it.icon != null })
        // Options and the pan action (which is what lets taps on the picture through).
        val mapStrip = template.mapActionStrip!!.actions
        assertEquals(2, mapStrip.size)
        assertEquals(Action.TYPE_PAN, mapStrip[1].type)
        assertTrue(mapStrip.all { it.title == null })
    }

    @Test
    fun `a host too old for the map strip gets one strip of four`() {
        carContext.updateHandshakeInfo(HandshakeInfo("old.host", 1))
        val template = show(PlayerScreen(carContext, env, film(1))).template<NavigationTemplate>()

        assertEquals(4, template.actionStrip!!.actions.size)
        assertNull(template.mapActionStrip)
    }

    @Test
    fun `strip buttons drive the player`() {
        val player = show(PlayerScreen(carContext, env, film(1)))
        val strip = player.template<NavigationTemplate>().actionStrip!!.actions

        strip[1].tap()
        strip[2].tap()
        strip[3].tap()

        assertEquals(listOf(-10_000L, 30_000L), backend.seeks)
        assertEquals(1, backend.toggles)
    }

    @Test
    fun `position updates do not redraw the template`() {
        val player = show(PlayerScreen(carContext, env, film(1)))
        val before = templatesSentBy(player).size

        repeat(30) { second -> backend.playback.update { it.copy(positionMs = second * 1_000L) } }
        idle()
        assertEquals(before, templatesSentBy(player).size)

        // Pausing does: the button changes.
        backend.playback.update { it.copy(isPlaying = false, playWhenReady = false) }
        idle()
        assertEquals(before + 1, templatesSentBy(player).size)

        // ...and once more a second later, in case the host lost the first refresh.
        advance(1_100)
        assertEquals(before + 2, templatesSentBy(player).size)
    }

    @Test
    fun `the button offers play whenever the player is paused and pause whenever it is not`() {
        val player = show(PlayerScreen(carContext, env, film(1)))
        fun buttonIcon() = player.template<NavigationTemplate>().actionStrip!!.actions[2].icon!!.icon!!.resId

        assertEquals(R.drawable.ic_car_pause, buttonIcon())

        // Buffering after a seek: still told to play, so still Pause - and no redraw.
        val before = templatesSentBy(player).size
        backend.playback.update { it.copy(isPlaying = false, isBuffering = true) }
        idle()
        assertEquals(R.drawable.ic_car_pause, buttonIcon())
        assertEquals(before, templatesSentBy(player).size)

        // Paused while still buffering: Play.
        backend.playback.update { it.copy(playWhenReady = false) }
        idle()
        assertEquals(R.drawable.ic_car_play, buttonIcon())

        backend.playback.update { it.copy(isPlaying = true, playWhenReady = true, isBuffering = false) }
        idle()
        assertEquals(R.drawable.ic_car_pause, buttonIcon())
    }

    @Test
    fun `leaving the player stops playback`() {
        show(HomeScreen(carContext, env))
        show(PlayerScreen(carContext, env, film(1)))
        assertEquals(0, backend.stops)

        screenManager.pop()

        assertEquals(1, backend.stops)
    }

    @Test
    fun `the back button closes the player`() {
        show(HomeScreen(carContext, env))
        val player = show(PlayerScreen(carContext, env, film(1)))

        player.template<NavigationTemplate>().actionStrip!!.actions[0].tap()

        assertTrue(player in screenManager.screensRemoved)
        assertEquals(1, backend.stops)
    }

    @Test
    fun `a playback error becomes a message with retry and back`() {
        backend.playSucceeds = false
        val player = show(PlayerScreen(carContext, env, film(1)))
        backend.playback.update { it.copy(error = PlaybackError("Lost the connection to the server.", "ERROR_CODE_IO")) }
        idle()

        val message = player.template<MessageTemplate>()
        assertEquals("Lost the connection to the server.", message.message.toString())
        assertEquals(listOf("Retry", "Back"), message.actionTitles())
        assertEquals(2, player.step)

        // It never started, so Retry starts it again from scratch.
        backend.playSucceeds = true
        message.action("Retry").tap()
        assertEquals(2, backend.played.size)
        player.template<NavigationTemplate>()
        assertEquals(1, player.step) // the player template starts a new task
    }

    @Test
    fun `retry after a mid-stream error asks the player to carry on`() {
        val player = show(PlayerScreen(carContext, env, film(1)))
        backend.playback.update { it.copy(isPlaying = false, error = PlaybackError("Playback stopped unexpectedly.", "X")) }
        idle()

        player.template<MessageTemplate>().action("Retry").tap()

        assertEquals(1, backend.retries)
        assertEquals(1, backend.played.size)
    }

    @Test
    fun `when an episode ends the next one plays after a countdown`() {
        backend.episodeList = listOf(episode(1, 1), episode(1, 2))
        show(PlayerScreen(carContext, env, episode(1, 1)))

        backend.playback.update { it.copy(isPlaying = false, isEnded = true) }
        idle()
        assertEquals("Up next in 8 s · S1 E2 · Episode 2", overlay.notices.last())

        advance(8_500)
        assertEquals(listOf("s1e1", "s1e2"), backend.played.map { it.first.id })
        assertNull(overlay.notices.last())
    }

    @Test
    fun `seeking back during the countdown cancels it`() {
        backend.episodeList = listOf(episode(1, 1), episode(1, 2))
        show(PlayerScreen(carContext, env, episode(1, 1)))
        backend.playback.update { it.copy(isPlaying = false, isEnded = true) }
        advance(2_000)

        backend.playback.update { it.copy(isPlaying = true, isEnded = false) }
        advance(10_000)

        assertEquals(1, backend.played.size)
        assertNull(overlay.notices.last())
    }

    @Test
    fun `offline the next episode comes from the downloads`() {
        backend.failure = networkError()
        backend.downloads.value = listOf(
            download(episode(1, 3)),
            download(episode(1, 1)),
            download(episode(1, 2), DownloadStatus.DOWNLOADING),
        )
        show(PlayerScreen(carContext, env, episode(1, 1)))

        backend.playback.update { it.copy(isPlaying = false, isEnded = true) }
        advance(8_500)

        // E2 is still downloading, so E3 is next.
        assertEquals(listOf("s1e1", "s1e3"), backend.played.map { it.first.id })
    }

    @Test
    fun `after a film or the last episode the player closes`() {
        show(HomeScreen(carContext, env))
        val player = show(PlayerScreen(carContext, env, film(1)))

        backend.playback.update { it.copy(isPlaying = false, isEnded = true) }
        idle()

        assertTrue(player in screenManager.screensRemoved)
        assertEquals(1, backend.played.size)
    }

    // --- Options ---------------------------------------------------------------------------

    @Test
    fun `options show the current audio subtitles and quality`() {
        show(PlayerScreen(carContext, env, film(1)))
        withTracks()
        val options = show(PlayerOptionsScreen(carContext, env)).template<ListTemplate>()

        assertEquals(listOf("Audio", "Subtitles", "Quality"), options.titles())
        assertEquals(listOf("English 5.1", "Off", "720p · 4 Mbps"), options.rows().map { it.text() })
    }

    @Test
    fun `subtitles can be chosen and turned off`() {
        show(PlayerScreen(carContext, env, film(1)))
        withTracks()
        backend.playback.update { it.copy(subtitleStreamIndex = 3) }
        val options = show(PlayerOptionsScreen(carContext, env))

        options.template<ListTemplate>().row("Subtitles").tap()
        val choice = top as ChoiceScreen
        assertEquals(3, choice.step)
        val list = choice.template<ListTemplate>().singleList!!
        assertEquals(listOf("Off", "English", "French PGS (restarts the video)"), list.rows().map { it.title.toString() })
        assertEquals(1, list.selectedIndex)

        list.select(0)

        assertEquals(listOf<Int?>(null), backend.subtitleChoices)
        assertSame(options, top)
    }

    @Test
    fun `audio track can be changed`() {
        show(PlayerScreen(carContext, env, film(1)))
        withTracks()
        val options = show(PlayerOptionsScreen(carContext, env))

        options.template<ListTemplate>().row("Audio").tap()
        (top as ChoiceScreen).template<ListTemplate>().singleList!!.select(1)

        assertEquals(listOf(2), backend.audioChoices)
    }

    @Test
    fun `quality is saved to this car's profile and applied to the stream`() {
        show(PlayerScreen(carContext, env, film(1)))
        withTracks()
        val options = show(PlayerOptionsScreen(carContext, env))

        options.template<ListTemplate>().row("Quality").tap()
        val list = (top as ChoiceScreen).template<ListTemplate>().singleList!!
        assertEquals(QualityTier.entries.map { it.label }, list.rows().map { it.title.toString() })
        assertEquals(QualityTier.entries.indexOf(QualityTier.P720_4M), list.selectedIndex)

        list.select(QualityTier.entries.indexOf(QualityTier.P480_2M))

        assertEquals(QualityTier.P480_2M, quality.current)
        assertEquals(listOf(QualityTier.P480_2M), backend.qualityChoices)
    }

    @Test
    fun `a choice the server refuses is reported and can be tried again`() {
        show(PlayerScreen(carContext, env, film(1)))
        withTracks()
        val choice = show(ChoiceScreen(carContext, env, 3, ChoiceScreen.Kind.AUDIO))

        backend.failure = networkError()
        choice.template<ListTemplate>().singleList!!.select(1)

        assertTrue(backend.audioChoices.isEmpty())
        assertEquals(listOf("Can't reach the server. Check your connection and try again."), toasts)
        assertSame(choice, top)

        backend.failure = null
        choice.template<ListTemplate>().singleList!!.select(1)
        assertEquals(listOf(2), backend.audioChoices)
    }

    @Test
    fun `quality of a download cannot be changed`() {
        show(PlayerScreen(carContext, env, film(1)))
        backend.playback.update { it.copy(playMethod = PlayMethod.OFFLINE) }
        val options = show(PlayerOptionsScreen(carContext, env))

        val row = options.template<ListTemplate>().row("Quality")
        assertEquals("Downloaded copy", row.text())
        row.tap()

        assertSame(options, top)
        assertFalse(toasts.isEmpty())
    }

    @Test
    fun `a long subtitle list is cut to the car's limit but keeps the current choice`() {
        show(PlayerScreen(carContext, env, film(1)))
        backend.playback.update { state ->
            state.copy(
                subtitleTracks = (1..10).map { StreamTrack(it, "Subtitle $it", delivery = SubtitleDelivery.SIDE_LOADED) },
                subtitleStreamIndex = 9,
            )
        }
        val list = show(ChoiceScreen(carContext, env, 3, ChoiceScreen.Kind.SUBTITLES)).template<ListTemplate>().singleList!!

        assertEquals(6, list.items.size)
        assertEquals("Subtitle 9", list.rows()[list.selectedIndex].title.toString())
    }
}
