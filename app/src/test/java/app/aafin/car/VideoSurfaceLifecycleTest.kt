package app.aafin.car

import android.graphics.SurfaceTexture
import android.os.Looper
import android.view.Surface
import androidx.car.app.SurfaceContainer
import androidx.test.core.app.ApplicationProvider
import app.aafin.car.render.VideoSurfaceRenderer
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The order in which a real Android Auto host hands over and takes away the drawing surface.
 *
 * A real host sends each `SurfaceContainer` across a process boundary, so the one in
 * "destroyed" is never the object that came with "available": its `Surface` is a different
 * object and (seen on the desktop head unit) its density can differ too. Each test container
 * here is therefore a separate object, as it would be in the car.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class VideoSurfaceLifecycleTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val surfaces = mutableListOf<Surface>()
    private val seen = mutableListOf<SurfaceSpec>()
    private val playback = MutableStateFlow(PlaybackState())
    private val film = MediaEntry(id = "film-1", name = "Film", kind = ItemKind.MOVIE)

    private val renderer = VideoSurfaceRenderer(
        context = ApplicationProvider.getApplicationContext(),
        scope = scope,
        playback = playback,
        player = { error("No surface is up in the tests that play, so the player must not be asked for") },
        seekBy = {},
        onSurface = { seen += it },
    )

    @After
    fun tearDown() {
        renderer.release()
        scope.cancel()
        surfaces.forEach(Surface::release)
    }

    private fun container(width: Int = 800, height: Int = 400, dpi: Int = 160): SurfaceContainer {
        val surface = Surface(SurfaceTexture(surfaces.size)).also { surfaces += it }
        return SurfaceContainer(surface, width, height, dpi)
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun `destroyed takes the window down and the next available puts it back`() {
        renderer.onSurfaceAvailable(container())
        idle()
        assertTrue("window shown on the first surface", renderer.isShowing)

        // Same size, but a different Surface object and no density - what the head unit sent.
        val destroyed = container(dpi = 0)
        renderer.onSurfaceDestroyed(destroyed)
        idle()
        assertFalse("window taken down when the surface is destroyed", renderer.isShowing)
        assertFalse("the destroyed copy is released", destroyed.surface!!.isValid)
        assertFalse("the surface that was in use is released", surfaces.first().isValid)

        // Back on the player: the host hands over a surface again.
        renderer.onSurfaceAvailable(container())
        idle()
        assertTrue("window rebuilt on the new surface", renderer.isShowing)
        assertEquals(2, seen.size)
    }

    @Test
    fun `destroyed with a different size still takes the window down`() {
        renderer.onSurfaceAvailable(container())
        idle()

        renderer.onSurfaceDestroyed(container(width = 0, height = 0, dpi = 0))
        idle()

        assertFalse(renderer.isShowing)
    }

    @Test
    fun `destroyed before any surface and destroyed twice are harmless`() {
        renderer.onSurfaceDestroyed(container())
        assertFalse(renderer.isShowing)

        renderer.onSurfaceAvailable(container())
        idle()
        renderer.onSurfaceDestroyed(container())
        renderer.onSurfaceDestroyed(container())
        idle()
        assertFalse(renderer.isShowing)

        renderer.onSurfaceAvailable(container())
        idle()
        assertTrue(renderer.isShowing)
    }

    @Test
    fun `a tap on the picture shows or hides the overlay and never pauses`() {
        playback.value = PlaybackState(item = film, title = "Film", isPlaying = true, playWhenReady = true)
        idle()
        assertTrue("overlay shown when playback starts", renderer.isOverlayVisible)

        renderer.onClick(400f, 200f)
        assertFalse("a tap hides it", renderer.isOverlayVisible)

        renderer.onClick(400f, 200f)
        assertTrue("the next tap brings it back", renderer.isOverlayVisible)

        // Nothing here can pause: the renderer is not even given a way to.
        assertTrue(playback.value.playWhenReady)
    }

    @Test
    fun `nothing is built after release`() {
        renderer.onSurfaceAvailable(container())
        idle()
        renderer.release()

        renderer.onSurfaceAvailable(container())
        renderer.onSurfaceDestroyed(container())
        idle()

        assertFalse(renderer.isShowing)
    }
}
