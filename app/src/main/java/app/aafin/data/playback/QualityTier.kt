package app.aafin.data.playback

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * Streaming quality choices offered in car profiles and settings.
 *
 * [AUTO] has no fixed numbers: it follows the connection and the car's screen (see [AutoQuality]).
 * The others are fixed caps that never change by themselves.
 *
 * @property label user-facing name.
 * @property maxBitrate total stream bitrate cap in bits per second, or null for no fixed cap.
 * @property maxWidth widest picture allowed in pixels, or null for no fixed cap.
 */
@Serializable(with = QualityTierSerializer::class)
enum class QualityTier(val label: String, val maxBitrate: Int?, val maxWidth: Int?) {
    AUTO("Auto (recommended)", null, null),
    P480_2M("480p · 2 Mbps", 2_000_000, 854),
    P720_4M("720p · 4 Mbps", 4_000_000, 1280),
    P1080_8M("1080p · 8 Mbps", 8_000_000, 1920),
    P1080_15M("1080p · 15 Mbps", 15_000_000, 1920),
    ORIGINAL("Original", null, null);

    /**
     * The value sent to Jellyfin as `MaxStreamingBitrate` for a fixed tier; uncapped tiers send a
     * very high ceiling. Not meaningful for [AUTO], whose cap comes from [StreamLimits].
     */
    val streamingBitrate: Int get() = maxBitrate ?: UNCAPPED_BITRATE

    companion object {
        val DEFAULT = AUTO

        /** 120 Mbps: above any real file, so "Original" never forces a transcode on bitrate alone. */
        const val UNCAPPED_BITRATE = 120_000_000

        /** The tier saved under [name]; a name this version does not know (or no longer has) means [AUTO]. */
        fun fromStored(name: String): QualityTier = entries.firstOrNull { it.name == name } ?: AUTO
    }
}

/** Stores a tier by name and reads unknown names as [QualityTier.AUTO], so old or newer files never fail to load. */
object QualityTierSerializer : KSerializer<QualityTier> {
    override val descriptor: SerialDescriptor = PrimitiveSerialDescriptor("app.aafin.data.playback.QualityTier", PrimitiveKind.STRING)

    override fun serialize(encoder: Encoder, value: QualityTier) = encoder.encodeString(value.name)

    override fun deserialize(decoder: Decoder): QualityTier = QualityTier.fromStored(decoder.decodeString())
}
