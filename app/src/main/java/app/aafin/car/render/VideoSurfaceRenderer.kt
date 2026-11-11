package app.aafin.car.render

import android.content.Context
import android.graphics.Rect
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.media3.common.Player
import app.aafin.car.CarFormat
import app.aafin.car.CarLog
import app.aafin.car.OverlayRule
import app.aafin.car.SeekGesture
import app.aafin.car.SurfaceInsets
import app.aafin.car.SurfaceSpec
import app.aafin.car.VideoOverlay
import app.aafin.data.playback.PlaybackState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * Puts the video on the car screen.
 *
 * Android Auto gives a navigation app a bare `Surface`. Handing that straight to ExoPlayer would
 * stretch the picture to the screen's shape and leave nowhere for subtitles, so instead:
 *
 * 1. a private `VirtualDisplay` is created whose output is the car's surface, and
 * 2. a [VideoPresentation] (an ordinary window with a Media3 `PlayerView`) is shown on it.
 *
 * This is the pattern Google documents for drawing views in a Car App Library app
 * (developer.android.com/training/cars/apps/library/draw-maps, "Use a virtual display to render
 * content") and the one its own Navigation SDK uses on Android Auto
 * (developers.google.com/maps/documentation/navigation/android-sdk/android-auto), whose
 * `VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY` flag is used here too.
 *
 * Everything here runs on the main thread: the Car App Library delivers surface callbacks there,
 * and windows and the player must be touched there.
 *
 * The callbacks are written to be safe in any order: a second "available" without a "destroyed",
 * a "destroyed" before any "available" (or twice), area changes before any surface, and anything
 * at all after [release]. A "destroyed" always takes the window down: see [onSurfaceDestroyed].
 *
 * @param player the ExoPlayer; only asked for once there is a surface and something to play.
 * @param onSurface told the size of each usable surface (the car profile falls back to it).
 */
class VideoSurfaceRenderer(
    private val context: Context,
    private val scope: CoroutineScope,
    private val playback: StateFlow<PlaybackState>,
    private val player: () -> Player,
    private val seekBy: (Long) -> Unit,
    private val onSurface: (SurfaceSpec) -> Unit = {},
) : SurfaceCallback, VideoOverlay {

    private val displayManager: DisplayManager = context.getSystemService(DisplayManager::class.java)
    private val handler = Handler(Looper.getMainLooper())

    private var container: SurfaceContainer? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var presentation: VideoPresentation? = null
    private var visibleArea = Rect()
    private var stableArea = Rect()
    private var rebuilds = 0
    private var released = false

    private var overlayTimed = false
    private var hideOverlay: Job? = null
    private var notice: String? = null
    private var wasPlaying = false

    private var dragRightPx = 0f
    private var dragVerticalPx = 0f
    private var dragSettle: Job? = null
    private var seekPreview: String? = null

    init {
        scope.launch {
            playback.collect { state ->
                // Show the title for a few seconds whenever playback starts or resumes.
                if (state.isPlaying && !wasPlaying) showOverlay()
                wasPlaying = state.isPlaying
                render()
            }
        }
    }

    /** True while a window is up on the car's surface. */
    val isShowing: Boolean get() = presentation != null

    // --- Surface lifecycle -----------------------------------------------------------------

    override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
        val surface = surfaceContainer.surface
        Log.i(TAG, "Surface available: ${surfaceContainer.width}x${surfaceContainer.height} @${surfaceContainer.dpi}dpi")
        if (released) {
            surface?.release()
            return
        }
        // The host may send a new surface (or new size) without destroying the old one first.
        tearDown()
        if (surface == null || !surface.isValid || surfaceContainer.width <= 0 || surfaceContainer.height <= 0) {
            Log.w(TAG, "Surface is not usable yet; waiting for the next one")
            surface?.release()
            return
        }
        container = surfaceContainer
        rebuilds = 0
        onSurface(SurfaceSpec(surfaceContainer.width, surfaceContainer.height, surfaceContainer.dpi))
        build()
    }

    override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
        Log.i(TAG, "Surface destroyed: ${surfaceContainer.width}x${surfaceContainer.height} @${surfaceContainer.dpi}dpi")
        // Every Surface object handed to the app has to be released, including this copy.
        if (surfaceContainer.surface !== container?.surface) surfaceContainer.surface?.release()
        // An app has one surface at a time, so this is always the one in use - whatever the
        // container says. It crosses a process boundary, so its Surface is never the same object
        // as the one from onSurfaceAvailable, and real hosts do not repeat the size and density
        // faithfully (the desktop head unit reports a different dpi here). Nothing may be drawn
        // once this returns; the next onSurfaceAvailable builds everything again.
        if (container != null || virtualDisplay != null || presentation != null) {
            tearDown()
        } else {
            Log.d(TAG, "Nothing was showing on it")
        }
    }

    override fun onVisibleAreaChanged(visibleArea: Rect) {
        Log.d(TAG, "Visible area: $visibleArea")
        this.visibleArea = Rect(visibleArea)
        applyInsets()
    }

    override fun onStableAreaChanged(stableArea: Rect) {
        Log.d(TAG, "Stable area: $stableArea")
        this.stableArea = Rect(stableArea)
        applyInsets()
    }

    /** Call when the car session ends. Nothing is drawn or created afterwards. */
    fun release() {
        Log.i(TAG, "Renderer released")
        released = true
        hideOverlay?.cancel()
        dragSettle?.cancel()
        handler.removeCallbacksAndMessages(null)
        tearDown()
    }

    private fun build() {
        val current = container ?: return
        val surface = current.surface ?: return
        try {
            val display = virtualDisplay
                ?: displayManager.createVirtualDisplay(
                    DISPLAY_NAME,
                    current.width,
                    current.height,
                    current.dpi.coerceAtLeast(1),
                    surface,
                    // Only ever our own window - never a mirror of the phone's screen.
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                )
                ?: run {
                    Log.e(TAG, "The system refused to create a virtual display")
                    return
                }
            virtualDisplay = display

            val window = VideoPresentation(context, display.display)
            presentation = window
            window.setOnDismissListener { onDismissedBySystem(window) }
            window.show()
            applyInsets()
            render()
            Log.i(TAG, "Presentation showing on virtual display ${display.display.displayId}")
        } catch (e: RuntimeException) {
            // InvalidDisplayException / BadTokenException: the surface went away while we were
            // setting up. Clean up and wait for the next onSurfaceAvailable.
            Log.e(TAG, "Could not show the video window: ${e.javaClass.simpleName}: ${e.message}")
            tearDown()
        }
    }

    /**
     * Android dismisses a Presentation by itself if its display changes or disappears. If our
     * surface is still there, put the window back (a few times at most, to rule out a loop).
     */
    private fun onDismissedBySystem(window: VideoPresentation) {
        if (released || presentation !== window) return
        Log.w(TAG, "Presentation was dismissed by the system")
        window.detachPlayer()
        presentation = null
        if (rebuilds++ < MAX_REBUILDS) {
            handler.post { if (!released && presentation == null && container != null) build() }
        }
    }

    private fun tearDown() {
        presentation?.let { window ->
            presentation = null
            window.setOnDismissListener(null)
            try {
                window.detachPlayer()
                window.dismiss()
            } catch (e: RuntimeException) {
                // The window's display has already gone; there is nothing left to remove.
                Log.w(TAG, "Dismissing the video window: ${e.javaClass.simpleName}")
            }
        }
        virtualDisplay?.let {
            it.release()
            Log.i(TAG, "Virtual display released")
        }
        virtualDisplay = null
        container?.surface?.release()
        container = null
    }

    private fun applyInsets() {
        val current = container ?: return
        val area = if (!visibleArea.isEmpty) visibleArea else stableArea
        presentation?.setInsets(SurfaceInsets.of(area.left, area.top, area.right, area.bottom, current.width, current.height))
    }

    // --- Overlay ---------------------------------------------------------------------------

    override fun setNotice(text: String?) {
        notice = text
        render()
    }

    /** True while the title and progress bar are (or would be) drawn over the picture. */
    internal val isOverlayVisible: Boolean get() = frame().overlayVisible

    override fun showOverlay() {
        overlayTimed = true
        hideOverlay?.cancel()
        hideOverlay = scope.launch {
            delay(OverlayRule.AUTO_HIDE_MS)
            overlayTimed = false
            render()
        }
        render()
    }

    private fun frame(): SurfaceFrame {
        val state = playback.value
        return SurfaceFrame(
            showVideo = state.hasMedia,
            title = state.title,
            positionMs = state.positionMs,
            durationMs = state.durationMs,
            overlayVisible = overlayTimed || !OverlayRule.mayAutoHide(state.isPlaying),
            notice = seekPreview ?: state.error?.message ?: notice ?: state.qualityNotice ?: if (state.isLoading) "Loading…" else null,
        )
    }

    private fun render() {
        val window = presentation ?: return
        val frame = frame()
        // The player (and its media session) is only created once there is something to play.
        if (frame.showVideo) window.attachPlayer(player())
        window.render(frame)
    }

    // --- Touch -----------------------------------------------------------------------------
    // The host only calls these on head units (and car API levels) that support them, and only
    // while the player's template includes the pan action. Each is a no-op unless a video is loaded.

    override fun onClick(x: Float, y: Float) {
        val state = playback.value
        if (released || !state.hasMedia || state.error != null) return
        // A tap only shows or hides the title and progress bar. It never pauses: a finger aimed
        // at one of the host's buttons often lands on the picture instead. Play/pause is the button.
        if (overlayTimed) {
            Log.d(TAG, "Tap: hide overlay")
            hideOverlay?.cancel()
            overlayTimed = false
            render()
        } else {
            Log.d(TAG, "Tap: show overlay")
            showOverlay()
        }
    }

    override fun onScroll(distanceX: Float, distanceY: Float) {
        if (released || !playback.value.hasMedia) return
        // distanceX follows Android's gesture convention: positive when the finger moves left.
        dragRightPx -= distanceX
        dragVerticalPx += distanceY
        val seekMs = pendingSeekMs()
        seekPreview = if (seekMs != 0L) CarFormat.signedClock(seekMs) else null
        render()
        dragSettle?.cancel()
        dragSettle = scope.launch {
            delay(DRAG_SETTLE_MS)
            commitDrag()
        }
    }

    override fun onFling(velocityX: Float, velocityY: Float) {
        // A fling is the end of a drag: apply the seek now rather than waiting for it to settle.
        if (dragSettle?.isActive == true) {
            dragSettle?.cancel()
            commitDrag()
        }
    }

    private fun pendingSeekMs(): Long =
        if (abs(dragVerticalPx) > abs(dragRightPx)) 0 else SeekGesture.seekMs(dragRightPx, container?.width ?: 0)

    private fun commitDrag() {
        val seekMs = pendingSeekMs()
        dragRightPx = 0f
        dragVerticalPx = 0f
        seekPreview = null
        if (seekMs != 0L && playback.value.hasMedia) {
            Log.d(TAG, "Drag: seek ${seekMs / 1000} s")
            seekBy(seekMs)
            showOverlay()
        } else {
            render()
        }
    }

    private companion object {
        const val TAG = CarLog.SURFACE
        const val DISPLAY_NAME = "AAFin car video"
        const val MAX_REBUILDS = 3
        const val DRAG_SETTLE_MS = 400L
    }
}
