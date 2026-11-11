package app.aafin.car.render

import android.app.Presentation
import android.content.Context
import android.os.Bundle
import android.view.Display
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import androidx.annotation.OptIn
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import androidx.media3.ui.SubtitleView
import app.aafin.R
import app.aafin.car.CarFormat
import app.aafin.car.SurfaceInsets

/** Everything the surface shows at one moment. */
data class SurfaceFrame(
    /** Something is loaded in the player: show the picture rather than the idle logo. */
    val showVideo: Boolean = false,
    val title: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val overlayVisible: Boolean = false,
    /** A one-line message over the picture, or null. */
    val notice: String? = null,
)

/**
 * The window drawn on the car's surface: a Media3 `PlayerView` (letterboxed picture, subtitles,
 * buffering spinner), the idle logo, and the title/progress overlay.
 *
 * It is an ordinary `Presentation` on the private virtual display that [VideoSurfaceRenderer]
 * creates over the car's surface - the approach Google documents for drawing views in a
 * Car App Library app. Main thread only. Call [show] before anything else.
 */
@OptIn(UnstableApi::class)
internal class VideoPresentation(context: Context, display: Display) : Presentation(context, display) {

    private lateinit var content: View
    private lateinit var playerView: PlayerView
    private lateinit var idle: View
    private lateinit var overlay: View
    private lateinit var notice: TextView
    private lateinit var title: TextView
    private lateinit var position: TextView
    private lateinit var duration: TextView
    private lateinit var progress: ProgressBar

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setCancelable(false)
        setContentView(R.layout.car_presentation)
        content = findViewById(R.id.car_content)
        playerView = findViewById(R.id.car_player)
        idle = findViewById(R.id.car_idle)
        overlay = findViewById(R.id.car_overlay)
        notice = findViewById(R.id.car_notice)
        title = findViewById(R.id.car_title)
        position = findViewById(R.id.car_position)
        duration = findViewById(R.id.car_duration)
        progress = findViewById(R.id.car_progress)
        // Subtitles are read from further away than on a phone.
        playerView.subtitleView?.setFractionalTextSize(SubtitleView.DEFAULT_TEXT_SIZE_FRACTION * SUBTITLE_SCALE)
    }

    /** Cheap to call repeatedly: nothing happens if this player is already attached. */
    fun attachPlayer(player: Player) {
        if (playerView.player !== player) playerView.player = player
    }

    /** Lets go of the player so it stops drawing to a surface that is about to disappear. */
    fun detachPlayer() {
        playerView.player = null
    }

    /** Keeps the picture, logo and overlay inside the part of the surface Android Auto is not drawing over. */
    fun setInsets(insets: SurfaceInsets) {
        content.setPadding(insets.left, insets.top, insets.right, insets.bottom)
    }

    fun render(frame: SurfaceFrame) {
        playerView.visibility = if (frame.showVideo) View.VISIBLE else View.INVISIBLE
        idle.visibility = if (frame.showVideo) View.GONE else View.VISIBLE

        notice.visibility = if (frame.notice != null) View.VISIBLE else View.GONE
        notice.text = frame.notice

        overlay.visibility = if (frame.showVideo && frame.overlayVisible) View.VISIBLE else View.GONE
        title.text = frame.title
        position.text = CarFormat.clock(frame.positionMs)
        duration.text = if (frame.durationMs > 0) CarFormat.clock(frame.durationMs) else ""
        progress.progress = if (frame.durationMs > 0) {
            (frame.positionMs.coerceIn(0, frame.durationMs) * progress.max / frame.durationMs).toInt()
        } else {
            0
        }
    }

    private companion object {
        const val SUBTITLE_SCALE = 1.25f
    }
}
