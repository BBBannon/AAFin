package app.aafin.data.playback

import kotlinx.serialization.Serializable

/**
 * Streaming quality caps offered in car profiles and settings.
 *
 * @property label user-facing name.
 * @property maxBitrate total stream bitrate cap in bits per second, or null for no cap.
 * @property maxWidth widest picture allowed in pixels, or null for no cap.
 */
@Serializable
enum class QualityTier(val label: String, val maxBitrate: Int?, val maxWidth: Int?) {
    P480_2M("480p · 2 Mbps", 2_000_000, 854),
    P720_4M("720p · 4 Mbps", 4_000_000, 1280),
    P1080_8M("1080p · 8 Mbps", 8_000_000, 1920),
    P1080_15M("1080p · 15 Mbps", 15_000_000, 1920),
    ORIGINAL("Original", null, null);

    /** The value sent to Jellyfin as `MaxStreamingBitrate`; uncapped tiers send a very high ceiling. */
    val streamingBitrate: Int get() = maxBitrate ?: UNCAPPED_BITRATE

    companion object {
        val DEFAULT = P720_4M

        /** 120 Mbps: above any real file, so "Original" never forces a transcode on bitrate alone. */
        const val UNCAPPED_BITRATE = 120_000_000
    }
}
