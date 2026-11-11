package app.aafin.data.playback

import kotlinx.serialization.Serializable

/** How the video reaches the player. Mirrors Jellyfin's play methods, plus local playback. */
enum class PlayMethod {
    /** The original file, untouched. */
    DIRECT_PLAY,

    /** Original video/audio re-wrapped by the server (no quality loss). */
    DIRECT_STREAM,

    /** Re-encoded by the server to fit this phone or the quality cap. */
    TRANSCODE,

    /** A completed download, played from local storage. */
    OFFLINE,
}

/** How a subtitle track gets on screen. */
enum class SubtitleDelivery {
    /** A text track fetched as a separate file and drawn by the player. Switching is instant. */
    SIDE_LOADED,

    /** A picture-based track the server must burn into the video. Switching restarts the stream. */
    BURN_IN,
}

/**
 * An audio or subtitle track as Jellyfin describes it. [index] is Jellyfin's stream index - the
 * value to pass to `PlaybackController.selectAudio` / `selectSubtitle`.
 */
@Serializable
data class StreamTrack(
    val index: Int,
    val label: String,
    val language: String? = null,
    val codec: String? = null,
    val isDefault: Boolean = false,
    val isForced: Boolean = false,
    /** Subtitles only. */
    val delivery: SubtitleDelivery? = null,
)

/** A side-loaded subtitle file. [url] may be a remote URL or a local `file://` URI. */
@Serializable
data class ExternalSubtitle(
    val index: Int,
    val url: String,
    val mimeType: String,
    val language: String? = null,
    val label: String? = null,
)

/** What to play and how. */
data class PlaybackRequest(
    val itemId: String,
    val tier: QualityTier,
    /** Jellyfin audio stream index, or null for the server's default. */
    val audioStreamIndex: Int? = null,
    /** Jellyfin subtitle stream index, or null for subtitles off. */
    val subtitleStreamIndex: Int? = null,
    /** Refuse direct play/stream: used for 720p downloads and as a fallback after decoder errors. */
    val forceTranscode: Boolean = false,
)

/**
 * The outcome of resolving a [PlaybackRequest]: where the stream is and what progress reports need.
 *
 * [url] and subtitle URLs can embed an access token - never log a plan.
 */
data class PlaybackPlan(
    val request: PlaybackRequest,
    val accountId: String,
    val mediaSourceId: String,
    /** Identifies the server-side play session (and transcode job). Null for offline playback. */
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val url: String,
    val isHls: Boolean,
    val externalSubtitles: List<ExternalSubtitle>,
    val audioTracks: List<StreamTrack>,
    val subtitleTracks: List<StreamTrack>,
    /** The audio stream that will play (requested, else the server's default). */
    val audioStreamIndex: Int?,
    /** The subtitle stream that will show, or null for none. */
    val subtitleStreamIndex: Int?,
    /** True when the selected subtitle is burned into the picture rather than side-loaded. */
    val subtitleBurnedIn: Boolean,
    val runtimeMs: Long?,
) {
    val itemId: String get() = request.itemId

    override fun toString(): String =
        "PlaybackPlan(item=$itemId, method=$playMethod, hls=$isHls, audio=$audioStreamIndex, subtitle=$subtitleStreamIndex)"
}

/** MIME types for side-loaded subtitles (the values Media3 expects). */
object SubtitleMime {
    const val SUBRIP = "application/x-subrip"
    const val VTT = "text/vtt"
    const val SSA = "text/x-ssa"

    /** MIME type for a subtitle file extension or Jellyfin codec name, or null if the player cannot render it. */
    fun forFormat(format: String?): String? = when (format?.lowercase()) {
        "srt", "subrip" -> SUBRIP
        "vtt", "webvtt" -> VTT
        "ass", "ssa" -> SSA
        else -> null
    }

    /** File extension to use when saving a subtitle of [mimeType] to disk. */
    fun extensionFor(mimeType: String): String = when (mimeType) {
        VTT -> "vtt"
        SSA -> "ass"
        else -> "srt"
    }
}
