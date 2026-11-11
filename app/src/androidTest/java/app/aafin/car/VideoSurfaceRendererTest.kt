package app.aafin.car

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.media.ImageReader
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import androidx.car.app.SurfaceContainer
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the rendering core on a real Android runtime, without a head unit: the renderer is
 * given a surface exactly as Android Auto would give one (here backed by an `ImageReader` so the
 * test can read the pixels back), and a real ExoPlayer plays a real H.264 file through it.
 *
 * What it shows: a `PlayerView` using a `SurfaceView`, inside a `Presentation` on a private
 * `VirtualDisplay`, is composited into the surface we were handed - letterboxed, not stretched -
 * and survives the surface being destroyed, resized and recreated.
 *
 * Run with an emulator or phone attached: `gradlew.bat connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class VideoSurfaceRendererTest {

    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val playback = MutableStateFlow(PlaybackState())
    private val film = MediaEntry("film", "Test film", ItemKind.MOVIE)

    private lateinit var scope: CoroutineScope
    private lateinit var player: ExoPlayer
    private lateinit var renderer: VideoSurfaceRenderer
    private val readerThread = HandlerThread("frames").apply { start() }
    private val screens = mutableListOf<Screen>()
    private var toggles = 0
    private val seeks = mutableListOf<Long>()

    /** A stand-in for the car's screen: a surface whose latest frame can be read back. */
    private inner class Screen(val width: Int, val height: Int) {
        private val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
        private val lock = Any()
        private var bytes: ByteArray? = null
        private var rowStride = 0
        private var pixelStride = 0

        init {
            reader.setOnImageAvailableListener({ source ->
                try {
                    source.acquireLatestImage()?.use { image ->
                        val plane = image.planes[0]
                        val copy = ByteArray(plane.buffer.remaining()).also { plane.buffer.get(it) }
                        synchronized(lock) {
                            bytes = copy
                            rowStride = plane.rowStride
                            pixelStride = plane.pixelStride
                        }
                    }
                } catch (e: IllegalStateException) {
                    // The reader was closed (surface replaced or test over) while a frame was in flight.
                }
            }, Handler(readerThread.looper))
            screens += this
        }

        fun container() = SurfaceContainer(reader.surface, width, height, 160)

        fun pixel(x: Int, y: Int): Int? = synchronized(lock) {
            val frame = bytes ?: return null
            val offset = y * rowStride + x * pixelStride
            Color.rgb(frame[offset].toInt() and 0xFF, frame[offset + 1].toInt() and 0xFF, frame[offset + 2].toInt() and 0xFF)
        }

        fun close() = reader.close()
    }

    private fun onMain(block: () -> Unit) = instrumentation.runOnMainSync(block)

    /** Waits until [condition] holds for the pixels of a frame, or fails with [what]. */
    private fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(100)
        }
        throw AssertionError("Timed out waiting for: $what")
    }

    private fun Int?.isGreen() = this != null && Color.green(this) > 140 && Color.red(this) < 90 && Color.blue(this) < 90
    private fun Int?.isBlack() = this != null && Color.red(this) < 24 && Color.green(this) < 24 && Color.blue(this) < 24

    /** The app's dark brand colour (#101018) - what surrounds the logo and fills any padding. */
    private fun Int?.isBrandDark() =
        this != null && Color.red(this) in 8..24 && Color.green(this) in 8..24 && Color.blue(this) in 16..36

    @Before
    fun setUp() = onMain {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        player = ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
        renderer = VideoSurfaceRenderer(
            context = context,
            scope = scope,
            playback = playback,
            player = { player },
            togglePlayPause = { toggles++ },
            seekBy = { seeks += it },
        )
    }

    @After
    fun tearDown() {
        onMain {
            renderer.release()
            player.release()
            scope.cancel()
        }
        readerThread.quitSafely()
        readerThread.join()
        screens.forEach { it.close() }
    }

    private fun startVideo() {
        // The test video lives in the test APK: 320x240 (4:3), solid green, six seconds, H.264.
        val file = File(context.cacheDir, "green-4x3.mp4")
        instrumentation.context.assets.open("green-4x3.mp4").use { input -> file.outputStream().use(input::copyTo) }
        onMain {
            playback.value = PlaybackState(item = film, title = "Test film", isPlaying = true, durationMs = 6_000)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(file)))
            player.prepare()
            player.play()
        }
    }

    @Test
    fun idleShowsTheBrandedBackground() {
        val screen = Screen(800, 480)
        onMain { renderer.onSurfaceAvailable(screen.container()) }

        await("the dark background in the corner") { screen.pixel(5, 5).isBrandDark() }
        // The logo sits in the middle: somewhere there the picture is not background.
        await("the logo in the middle") {
            (140..300 step 4).any { y -> (300..500 step 4).any { x -> !screen.pixel(x, y).isBrandDark() } }
        }
        onMain { assertTrue(renderer.isShowing) }
    }

    @Test
    fun videoIsLetterboxedNotStretched() {
        val screen = Screen(800, 480)
        onMain { renderer.onSurfaceAvailable(screen.container()) }
        startVideo()

        // 4:3 video on a 5:3 surface: 640 px wide in the middle, 80 px black bars either side.
        await("green video in the middle of the surface") { screen.pixel(400, 200).isGreen() }
        assertTrue("left edge of the picture", screen.pixel(100, 200).isGreen())
        assertTrue("right edge of the picture", screen.pixel(700, 200).isGreen())
        assertTrue("left bar", screen.pixel(40, 200).isBlack())
        assertTrue("right bar", screen.pixel(760, 200).isBlack())
    }

    @Test
    fun pictureStaysInsideTheVisibleArea() {
        val screen = Screen(800, 480)
        onMain {
            renderer.onVisibleAreaChanged(Rect(0, 120, 800, 480)) // before any surface: remembered
            renderer.onSurfaceAvailable(screen.container())
        }
        startVideo()

        // 360 px of height left: the 4:3 picture is 480 wide, centred, below the covered strip.
        await("green video inside the visible area") { screen.pixel(400, 300).isGreen() }
        assertTrue("covered strip is left clear", screen.pixel(400, 60).isBrandDark())
        assertTrue("bar beside the smaller picture", screen.pixel(100, 300).isBlack())

        // The host hides its controls: the whole surface is visible again and the picture grows.
        onMain { renderer.onVisibleAreaChanged(Rect(0, 0, 800, 480)) }
        await("picture fills the height again") { screen.pixel(400, 60).isGreen() && screen.pixel(100, 300).isGreen() }
    }

    @Test
    fun surfaceCanGoAwayComeBackAndChangeSize() {
        val first = Screen(800, 480)
        onMain { renderer.onSurfaceAvailable(first.container()) }
        startVideo()
        await("video on the first surface") { first.pixel(400, 240).isGreen() }

        // Destroyed (twice - the second must be harmless), with area changes while there is no surface.
        onMain {
            renderer.onSurfaceDestroyed(first.container())
            renderer.onSurfaceDestroyed(first.container())
            renderer.onStableAreaChanged(Rect(0, 0, 10, 10))
            renderer.onVisibleAreaChanged(Rect())
            assertFalse(renderer.isShowing)
        }

        // A new, larger surface: playback carried on, the picture comes back.
        val second = Screen(1280, 720)
        onMain { renderer.onSurfaceAvailable(second.container()) }
        await("video on the second surface") { second.pixel(640, 360).isGreen() }

        // A third arrives without the second being destroyed first.
        val third = Screen(640, 360)
        onMain { renderer.onSurfaceAvailable(third.container()) }
        await("video on the third surface") { third.pixel(320, 180).isGreen() }

        // A late "destroyed" for the surface that was replaced must not take the current one down.
        onMain {
            renderer.onSurfaceDestroyed(second.container())
            assertTrue(renderer.isShowing)
        }

        // After release nothing is created, whatever arrives.
        onMain {
            renderer.release()
            renderer.onSurfaceAvailable(Screen(320, 240).container())
            renderer.onVisibleAreaChanged(Rect(0, 0, 100, 100))
            renderer.onClick(1f, 1f)
            assertFalse(renderer.isShowing)
        }
    }

    /**
     * In the car the phone is usually asleep in a pocket. The window on the virtual display has
     * to keep laying out and drawing (not just the video layer) with the phone's own screen off.
     */
    @Test
    fun keepsDrawingWithThePhoneScreenOff() {
        val power = context.getSystemService(PowerManager::class.java)
        val screen = Screen(800, 480)
        try {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_SLEEP").close()
            await("the phone to go to sleep") { !power.isInteractive }

            onMain { renderer.onSurfaceAvailable(screen.container()) }
            startVideo()
            await("video while the phone sleeps") { screen.pixel(400, 240).isGreen() }

            // A layout change made while asleep must reach the car screen too.
            onMain { renderer.onVisibleAreaChanged(Rect(0, 120, 800, 480)) }
            await("the picture to move inside the new visible area") {
                screen.pixel(400, 60).isBrandDark() && screen.pixel(400, 300).isGreen()
            }
            assertFalse(power.isInteractive)
        } finally {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_WAKEUP").close()
        }
    }

    @Test
    fun tapsAndDragsControlPlayback() {
        val screen = Screen(800, 480)
        onMain { renderer.onSurfaceAvailable(screen.container()) }

        // Nothing loaded: touches do nothing.
        onMain {
            renderer.onClick(400f, 240f)
            renderer.onScroll(-300f, 0f)
            renderer.onFling(900f, 0f)
        }
        assertEquals(0, toggles)
        assertTrue(seeks.isEmpty())

        startVideo()
        // Playback has just started, so the overlay is up: a tap pauses.
        onMain { renderer.onClick(400f, 240f) }
        assertEquals(1, toggles)

        // Once the overlay has faded, the first tap only brings it back; the second pauses.
        Thread.sleep(OverlayRule.AUTO_HIDE_MS + 1_000)
        onMain { renderer.onClick(400f, 240f) }
        assertEquals(1, toggles)
        onMain { renderer.onClick(400f, 240f) }
        assertEquals(2, toggles)

        // Dragging a finger 400 px to the right (half the screen) seeks forward 90 s on release.
        onMain {
            renderer.onScroll(-150f, 3f)
            renderer.onScroll(-250f, -2f)
            renderer.onFling(1200f, 0f)
        }
        assertEquals(listOf(90_000L), seeks)

        // A mostly vertical drag is not a seek.
        onMain { renderer.onScroll(-40f, 300f) }
        Thread.sleep(800)
        assertEquals(listOf(90_000L), seeks)
    }
}
