package app.aafin.data.playback

import android.media.MediaCodecList
import org.jellyfin.sdk.model.api.CodecProfile
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.DeviceProfile
import org.jellyfin.sdk.model.api.DirectPlayProfile
import org.jellyfin.sdk.model.api.DlnaProfileType
import org.jellyfin.sdk.model.api.EncodingContext
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileCondition
import org.jellyfin.sdk.model.api.ProfileConditionType
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.jellyfin.sdk.model.api.SubtitleProfile
import org.jellyfin.sdk.model.api.TranscodingProfile

/** Which decoders this phone has. Abstracted so the profile logic can be unit-tested. */
fun interface CodecCapabilities {
    /** True if a decoder exists for the Android MIME type, e.g. `video/hevc`. */
    fun hasDecoder(mimeType: String): Boolean
}

/** [CodecCapabilities] read once from the platform's [MediaCodecList]. */
class MediaCodecCapabilities : CodecCapabilities {
    private val decoderTypes: Set<String> by lazy {
        MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
            .filterNot { it.isEncoder }
            .flatMap { it.supportedTypes.asIterable() }
            .mapTo(HashSet()) { it.lowercase() }
    }

    override fun hasDecoder(mimeType: String): Boolean = mimeType.lowercase() in decoderTypes
}

/**
 * Builds the Jellyfin `DeviceProfile` that tells the server what this phone can play directly
 * and how to transcode everything else.
 *
 * - Direct play: mp4/mkv/webm/ts containers with whichever of h264/hevc/vp9/av1 and
 *   aac/mp3/opus/flac/vorbis/ac3/eac3 have a decoder on this phone.
 * - Transcoding: HLS with MPEG-TS segments, h264 + stereo aac. This is the combination the
 *   official Jellyfin Android TV client uses with ExoPlayer.
 * - Bitrate and picture size are capped by the [StreamLimits]; anything above is transcoded down.
 *   The picture-size ceiling for transcodes travels as conditions on the transcoding profile:
 *   Jellyfin has no `MaxWidth`/`MaxHeight` field in the PlaybackInfo request, and applies those
 *   conditions only to video it re-encodes - so a file that can play directly still does.
 * - Text subtitles are delivered as separate files (External); picture-based ones are burned in.
 */
object DeviceProfileBuilder {

    /** Jellyfin codec name → Android decoder MIME type. Order = preference. */
    val VIDEO_CODECS: List<Pair<String, String>> = listOf(
        "h264" to "video/avc",
        "hevc" to "video/hevc",
        "vp9" to "video/x-vnd.on2.vp9",
        "av1" to "video/av01",
    )

    val AUDIO_CODECS: List<Pair<String, String>> = listOf(
        "aac" to "audio/mp4a-latm",
        "mp3" to "audio/mpeg",
        "opus" to "audio/opus",
        "flac" to "audio/flac",
        "vorbis" to "audio/vorbis",
        "ac3" to "audio/ac3",
        "eac3" to "audio/eac3",
    )

    const val DIRECT_PLAY_CONTAINERS = "mp4,m4v,mov,mkv,webm,ts,mpegts"

    /** Subtitle formats ExoPlayer renders itself; fetched as side-loaded files. */
    val TEXT_SUBTITLE_FORMATS = listOf("srt", "subrip", "vtt", "webvtt", "ass", "ssa")

    /** Picture-based subtitle formats; the server burns these into the video. */
    val IMAGE_SUBTITLE_FORMATS = listOf("pgs", "pgssub", "dvdsub", "dvbsub")

    /** The profile for a tier's own limits, with no car screen involved. */
    fun build(tier: QualityTier, capabilities: CodecCapabilities): DeviceProfile = build(StreamLimits.forTier(tier), capabilities)

    /**
     * @param forceTranscode the request refuses direct play, so the picture-size ceiling is also
     *   given as a codec condition - which the server applies to every transcode, whatever its reason.
     */
    fun build(limits: StreamLimits, capabilities: CodecCapabilities, forceTranscode: Boolean = false): DeviceProfile {
        // h264 and aac are the transcoding targets, so they are always listed: every Android phone decodes them.
        val videoCodecs = VIDEO_CODECS.filter { (name, mime) -> name == "h264" || capabilities.hasDecoder(mime) }.map { it.first }
        val audioCodecs = AUDIO_CODECS.filter { (name, mime) -> name == "aac" || capabilities.hasDecoder(mime) }.map { it.first }

        return DeviceProfile(
            name = "AAFin",
            maxStreamingBitrate = limits.maxBitrate,
            maxStaticBitrate = limits.maxBitrate,
            directPlayProfiles = listOf(
                DirectPlayProfile(
                    container = DIRECT_PLAY_CONTAINERS,
                    videoCodec = videoCodecs.joinToString(","),
                    audioCodec = audioCodecs.joinToString(","),
                    type = DlnaProfileType.VIDEO,
                ),
            ),
            transcodingProfiles = listOf(
                TranscodingProfile(
                    container = "ts",
                    type = DlnaProfileType.VIDEO,
                    videoCodec = "h264",
                    audioCodec = "aac",
                    protocol = MediaStreamProtocol.HLS,
                    context = EncodingContext.STREAMING,
                    maxAudioChannels = "2",
                    copyTimestamps = false,
                    enableSubtitlesInManifest = false,
                    conditions = sizeConditions(limits.transcodeSize?.width, limits.transcodeSize?.height),
                ),
            ),
            containerProfiles = emptyList(),
            codecProfiles = codecProfiles(limits, forceTranscode),
            subtitleProfiles = TEXT_SUBTITLE_FORMATS.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.EXTERNAL) } +
                IMAGE_SUBTITLE_FORMATS.map { SubtitleProfile(format = it, method = SubtitleDeliveryMethod.ENCODE) },
        )
    }

    private fun codecProfiles(limits: StreamLimits, forceTranscode: Boolean): List<CodecProfile> = buildList {
        // Phone hardware decoders do not handle 10-bit h264; make the server convert it.
        add(
            CodecProfile(
                type = CodecType.VIDEO,
                codec = "h264",
                conditions = listOf(atMost(ProfileConditionValue.VIDEO_BIT_DEPTH, 8)),
                applyConditions = emptyList(),
            ),
        )
        val ceiling = limits.transcodeSize.takeIf { forceTranscode }
        val conditions = sizeConditions(listOfNotNull(limits.directPlayMaxWidth, ceiling?.width).minOrNull(), ceiling?.height)
        if (conditions.isNotEmpty()) {
            add(CodecProfile(type = CodecType.VIDEO, conditions = conditions, applyConditions = emptyList()))
        }
    }

    private fun sizeConditions(maxWidth: Int?, maxHeight: Int?): List<ProfileCondition> = listOfNotNull(
        maxWidth?.let { atMost(ProfileConditionValue.WIDTH, it) },
        maxHeight?.let { atMost(ProfileConditionValue.HEIGHT, it) },
    )

    private fun atMost(property: ProfileConditionValue, value: Int) = ProfileCondition(
        condition = ProfileConditionType.LESS_THAN_EQUAL,
        property = property,
        value = value.toString(),
        isRequired = false,
    )
}
