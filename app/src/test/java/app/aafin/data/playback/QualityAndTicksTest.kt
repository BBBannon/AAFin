package app.aafin.data.playback

import app.aafin.data.Ticks
import org.jellyfin.sdk.model.api.CodecType
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.ProfileConditionValue
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QualityAndTicksTest {

    // --- Quality tiers -------------------------------------------------------------------

    @Test
    fun `tiers map to the agreed bitrates and widths`() {
        assertEquals(2_000_000, QualityTier.P480_2M.maxBitrate)
        assertEquals(854, QualityTier.P480_2M.maxWidth)
        assertEquals(4_000_000, QualityTier.P720_4M.maxBitrate)
        assertEquals(1280, QualityTier.P720_4M.maxWidth)
        assertEquals(8_000_000, QualityTier.P1080_8M.maxBitrate)
        assertEquals(1920, QualityTier.P1080_8M.maxWidth)
        assertEquals(15_000_000, QualityTier.P1080_15M.maxBitrate)
        assertEquals(1920, QualityTier.P1080_15M.maxWidth)
        assertNull(QualityTier.ORIGINAL.maxBitrate)
        assertNull(QualityTier.ORIGINAL.maxWidth)
    }

    @Test
    fun `default tier is 720p at 4 Mbps`() {
        assertEquals(QualityTier.P720_4M, QualityTier.DEFAULT)
    }

    @Test
    fun `streaming bitrate equals the cap or a high ceiling for original`() {
        assertEquals(4_000_000, QualityTier.P720_4M.streamingBitrate)
        assertEquals(QualityTier.UNCAPPED_BITRATE, QualityTier.ORIGINAL.streamingBitrate)
        assertTrue(QualityTier.ORIGINAL.streamingBitrate > QualityTier.P1080_15M.streamingBitrate)
    }

    // --- Ticks ---------------------------------------------------------------------------

    @Test
    fun `ticks convert to and from milliseconds`() {
        assertEquals(10_000_000L, Ticks.fromMs(1_000))
        assertEquals(1_000L, Ticks.toMs(10_000_000))
        assertEquals(0L, Ticks.toMs(0))
        // 1 h 23 min 45.678 s
        val ms = 5_025_678L
        assertEquals(50_256_780_000L, Ticks.fromMs(ms))
        assertEquals(ms, Ticks.toMs(Ticks.fromMs(ms)))
    }

    @Test
    fun `sub-millisecond ticks round down`() {
        assertEquals(1L, Ticks.toMs(19_999))
    }

    // --- Device profile ------------------------------------------------------------------

    private val everything = CodecCapabilities { true }
    private val nothing = CodecCapabilities { false }

    @Test
    fun `profile lists only codecs with a decoder, but always h264 and aac`() {
        val limited = CodecCapabilities { it in setOf("video/avc", "video/hevc", "audio/mp4a-latm", "audio/opus") }
        val direct = DeviceProfileBuilder.build(QualityTier.P720_4M, limited).directPlayProfiles.single()
        assertEquals("h264,hevc", direct.videoCodec)
        assertEquals("aac,opus", direct.audioCodec)
        assertEquals("mp4,m4v,mov,mkv,webm,ts,mpegts", direct.container)

        val bare = DeviceProfileBuilder.build(QualityTier.P720_4M, nothing).directPlayProfiles.single()
        assertEquals("h264", bare.videoCodec)
        assertEquals("aac", bare.audioCodec)

        val full = DeviceProfileBuilder.build(QualityTier.P720_4M, everything).directPlayProfiles.single()
        assertEquals("h264,hevc,vp9,av1", full.videoCodec)
        assertEquals("aac,mp3,opus,flac,vorbis,ac3,eac3", full.audioCodec)
    }

    @Test
    fun `transcoding is hls with ts segments, h264 and stereo aac`() {
        val transcoding = DeviceProfileBuilder.build(QualityTier.P720_4M, everything).transcodingProfiles.single()
        assertEquals(MediaStreamProtocol.HLS, transcoding.protocol)
        assertEquals("ts", transcoding.container)
        assertEquals("h264", transcoding.videoCodec)
        assertEquals("aac", transcoding.audioCodec)
        assertEquals("2", transcoding.maxAudioChannels)
    }

    @Test
    fun `tier caps bitrate and width`() {
        val profile = DeviceProfileBuilder.build(QualityTier.P720_4M, everything)
        assertEquals(4_000_000, profile.maxStreamingBitrate)
        assertEquals(4_000_000, profile.maxStaticBitrate)
        val widthCap = profile.codecProfiles
            .filter { it.type == CodecType.VIDEO && it.codec == null }
            .flatMap { it.conditions }
            .single { it.property == ProfileConditionValue.WIDTH }
        assertEquals("1280", widthCap.value)
    }

    @Test
    fun `original tier has no width cap`() {
        val profile = DeviceProfileBuilder.build(QualityTier.ORIGINAL, everything)
        assertEquals(QualityTier.UNCAPPED_BITRATE, profile.maxStreamingBitrate)
        assertTrue(profile.codecProfiles.flatMap { it.conditions }.none { it.property == ProfileConditionValue.WIDTH })
    }

    @Test
    fun `text subtitles are external and picture subtitles are burned in`() {
        val methods = DeviceProfileBuilder.build(QualityTier.P720_4M, everything).subtitleProfiles.associate { it.format to it.method }
        for (format in listOf("srt", "subrip", "vtt", "ass", "ssa")) assertEquals(format, SubtitleDeliveryMethod.EXTERNAL, methods[format])
        for (format in listOf("pgs", "pgssub", "dvdsub", "dvbsub")) assertEquals(format, SubtitleDeliveryMethod.ENCODE, methods[format])
    }
}
