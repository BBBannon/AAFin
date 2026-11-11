package app.aafin.data.playback

import app.aafin.data.jellyfin.JellyfinException
import org.jellyfin.sdk.model.api.MediaProtocol
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaSourceType
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackErrorCode
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PlaybackDecisionTest {

    private val server = "https://jf.example.com/base"
    private val itemId = "0f8fad5b-d9cb-469f-a165-70867728950e"
    private val request = PlaybackRequest(itemId, QualityTier.P720_4M)

    private fun stream(
        index: Int,
        type: MediaStreamType,
        codec: String? = null,
        isText: Boolean = false,
        supportsExternal: Boolean = false,
        deliveryMethod: SubtitleDeliveryMethod? = null,
        deliveryUrl: String? = null,
        isExternalUrl: Boolean? = null,
        language: String? = null,
        title: String? = null,
        isDefault: Boolean = false,
    ) = MediaStream(
        codec = codec,
        language = language,
        displayTitle = title,
        isInterlaced = false,
        isDefault = isDefault,
        isForced = false,
        isHearingImpaired = false,
        type = type,
        index = index,
        isExternal = false,
        deliveryMethod = deliveryMethod,
        deliveryUrl = deliveryUrl,
        isExternalUrl = isExternalUrl,
        isTextSubtitleStream = isText,
        supportsExternalStream = supportsExternal,
    )

    private fun source(
        directPlay: Boolean = false,
        directStream: Boolean = false,
        transcodingUrl: String? = null,
        subProtocol: MediaStreamProtocol = MediaStreamProtocol.HTTP,
        streams: List<MediaStream> = listOf(
            stream(0, MediaStreamType.VIDEO, "h264"),
            stream(1, MediaStreamType.AUDIO, "aac", language = "eng", title = "English - AAC - Stereo", isDefault = true),
        ),
        defaultAudio: Int? = 1,
    ) = MediaSourceInfo(
        protocol = MediaProtocol.FILE,
        id = "abc123",
        type = MediaSourceType.DEFAULT,
        container = "mkv",
        isRemote = false,
        eTag = "etag1",
        runTimeTicks = 72_000_000_000, // 2 hours
        readAtNativeFramerate = false,
        ignoreDts = false,
        ignoreIndex = false,
        genPtsInput = false,
        supportsTranscoding = true,
        supportsDirectStream = directStream,
        supportsDirectPlay = directPlay,
        isInfiniteStream = false,
        requiresOpening = false,
        requiresClosing = false,
        requiresLooping = false,
        supportsProbing = true,
        mediaStreams = streams,
        transcodingUrl = transcodingUrl,
        transcodingSubProtocol = subProtocol,
        defaultAudioStreamIndex = defaultAudio,
        hasSegments = false,
    )

    private fun response(vararg sources: MediaSourceInfo, error: PlaybackErrorCode? = null) =
        PlaybackInfoResponse(mediaSources = sources.toList(), playSessionId = "session-1", errorCode = error)

    private fun decide(response: PlaybackInfoResponse, request: PlaybackRequest = this.request) =
        PlaybackDecision.decide(server, "account-1", request, response)

    private val hlsUrl = "/videos/$itemId/master.m3u8?MediaSourceId=abc123&VideoCodec=h264&ApiKey=secret&PlaySessionId=session-1"

    @Test
    fun `direct play uses the static stream url and keeps report details`() {
        val plan = decide(response(source(directPlay = true, directStream = true, transcodingUrl = hlsUrl)))

        assertEquals(PlayMethod.DIRECT_PLAY, plan.playMethod)
        assertEquals("$server/Videos/$itemId/stream?static=true&mediaSourceId=abc123&tag=etag1", plan.url)
        assertFalse(plan.isHls)
        assertEquals("session-1", plan.playSessionId)
        assertEquals("abc123", plan.mediaSourceId)
        assertEquals("account-1", plan.accountId)
        assertEquals(7_200_000L, plan.runtimeMs)
        assertEquals(1, plan.audioStreamIndex)
        assertNull(plan.subtitleStreamIndex)
    }

    @Test
    fun `transcode uses the server transcoding url as hls`() {
        val plan = decide(response(source(transcodingUrl = hlsUrl, subProtocol = MediaStreamProtocol.HLS)))

        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertEquals(server + hlsUrl, plan.url)
        assertTrue(plan.isHls)
    }

    @Test
    fun `remux is labelled direct stream`() {
        val plan = decide(response(source(directStream = true, transcodingUrl = hlsUrl, subProtocol = MediaStreamProtocol.HLS)))

        assertEquals(PlayMethod.DIRECT_STREAM, plan.playMethod)
        assertTrue(plan.isHls)
    }

    @Test
    fun `hls is recognised from the url when the sub protocol is not set`() {
        val plan = decide(response(source(transcodingUrl = hlsUrl)))
        assertTrue(plan.isHls)
    }

    @Test
    fun `progressive transcode is not treated as hls`() {
        val plan = decide(response(source(transcodingUrl = "/videos/$itemId/stream.mp4?VideoCodec=h264")))
        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertFalse(plan.isHls)
    }

    @Test
    fun `direct stream without a transcoding url falls back to the static stream`() {
        val plan = decide(response(source(directStream = true)))
        assertEquals(PlayMethod.DIRECT_STREAM, plan.playMethod)
        assertTrue(plan.url.startsWith("$server/Videos/$itemId/stream?static=true"))
    }

    @Test
    fun `forced transcode ignores direct play`() {
        val plan = decide(
            response(source(directPlay = true, directStream = true, transcodingUrl = hlsUrl, subProtocol = MediaStreamProtocol.HLS)),
            request.copy(forceTranscode = true),
        )
        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertEquals(server + hlsUrl, plan.url)
    }

    @Test
    fun `nothing playable is reported as no compatible stream`() {
        val noSources = assertThrows(JellyfinException::class.java) { decide(response()) }
        assertEquals(JellyfinException.Kind.NO_COMPATIBLE_STREAM, noSources.kind)

        val unplayable = assertThrows(JellyfinException::class.java) { decide(response(source())) }
        assertEquals(JellyfinException.Kind.NO_COMPATIBLE_STREAM, unplayable.kind)

        val forcedWithoutUrl = assertThrows(JellyfinException::class.java) {
            decide(response(source(directPlay = true)), request.copy(forceTranscode = true))
        }
        assertEquals(JellyfinException.Kind.NO_COMPATIBLE_STREAM, forcedWithoutUrl.kind)
    }

    @Test
    fun `server error codes become friendly errors`() {
        val notAllowed = assertThrows(JellyfinException::class.java) {
            decide(response(source(directPlay = true), error = PlaybackErrorCode.NOT_ALLOWED))
        }
        assertEquals(JellyfinException.Kind.PLAYBACK_NOT_ALLOWED, notAllowed.kind)

        val rateLimited = assertThrows(JellyfinException::class.java) {
            decide(response(error = PlaybackErrorCode.RATE_LIMIT_EXCEEDED))
        }
        assertEquals(JellyfinException.Kind.TOO_MANY_STREAMS, rateLimited.kind)
    }

    private val subtitleStreams = listOf(
        stream(0, MediaStreamType.VIDEO, "hevc"),
        stream(1, MediaStreamType.AUDIO, "aac", language = "eng", isDefault = true),
        stream(2, MediaStreamType.AUDIO, "ac3", language = "fra"),
        stream(
            3, MediaStreamType.SUBTITLE, "subrip", isText = true, supportsExternal = true, language = "eng", title = "English - SRT",
            deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
            deliveryUrl = "/Videos/$itemId/abc123/Subtitles/3/0/Stream.srt?ApiKey=secret",
        ),
        stream(
            4, MediaStreamType.SUBTITLE, "ass", isText = true, supportsExternal = true, language = "ger",
            deliveryMethod = SubtitleDeliveryMethod.EXTERNAL,
            deliveryUrl = "https://cdn.example.org/subs/4.ass",
            isExternalUrl = true,
        ),
        stream(5, MediaStreamType.SUBTITLE, "pgssub", language = "eng", deliveryMethod = SubtitleDeliveryMethod.ENCODE),
        // Text track the server gave no delivery URL for.
        stream(6, MediaStreamType.SUBTITLE, "mov_text", isText = true, supportsExternal = true, language = "spa"),
    )

    @Test
    fun `text subtitles are side loaded with correct urls and mime types`() {
        val plan = decide(response(source(directPlay = true, streams = subtitleStreams)))

        assertEquals(listOf(3, 4, 6), plan.externalSubtitles.map { it.index })

        val srt = plan.externalSubtitles[0]
        assertEquals("$server/Videos/$itemId/abc123/Subtitles/3/0/Stream.srt?ApiKey=secret", srt.url)
        assertEquals("application/x-subrip", srt.mimeType)
        assertEquals("eng", srt.language)
        assertEquals("English - SRT", srt.label)

        val ass = plan.externalSubtitles[1]
        assertEquals("https://cdn.example.org/subs/4.ass", ass.url)
        assertEquals("text/x-ssa", ass.mimeType)

        val converted = plan.externalSubtitles[2]
        assertEquals("$server/Videos/$itemId/abc123/Subtitles/6/Stream.srt", converted.url)
        assertEquals("application/x-subrip", converted.mimeType)
    }

    @Test
    fun `track lists describe audio and subtitle streams`() {
        val plan = decide(response(source(directPlay = true, streams = subtitleStreams)))

        assertEquals(listOf(1, 2), plan.audioTracks.map { it.index })
        assertEquals(listOf(3, 4, 5, 6), plan.subtitleTracks.map { it.index })
        assertEquals(
            listOf(SubtitleDelivery.SIDE_LOADED, SubtitleDelivery.SIDE_LOADED, SubtitleDelivery.BURN_IN, SubtitleDelivery.SIDE_LOADED),
            plan.subtitleTracks.map { it.delivery },
        )
    }

    @Test
    fun `selecting a text subtitle keeps direct play`() {
        val plan = decide(
            response(source(directPlay = true, streams = subtitleStreams)),
            request.copy(subtitleStreamIndex = 3, audioStreamIndex = 2),
        )
        assertEquals(PlayMethod.DIRECT_PLAY, plan.playMethod)
        assertEquals(3, plan.subtitleStreamIndex)
        assertFalse(plan.subtitleBurnedIn)
        assertEquals(2, plan.audioStreamIndex)
    }

    @Test
    fun `selecting a picture subtitle forces a burn-in transcode`() {
        val burnIn = request.copy(subtitleStreamIndex = 5)
        val plan = decide(
            response(source(directPlay = true, transcodingUrl = hlsUrl, subProtocol = MediaStreamProtocol.HLS, streams = subtitleStreams)),
            burnIn,
        )
        assertEquals(PlayMethod.TRANSCODE, plan.playMethod)
        assertTrue(plan.subtitleBurnedIn)
        assertEquals(5, plan.subtitleStreamIndex)
    }

    @Test
    fun `unknown subtitle index means subtitles off`() {
        val plan = decide(response(source(directPlay = true, streams = subtitleStreams)), request.copy(subtitleStreamIndex = 99))
        assertNull(plan.subtitleStreamIndex)
        assertEquals(PlayMethod.DIRECT_PLAY, plan.playMethod)
    }

    @Test
    fun `needsForcedTranscode only when burn-in is wanted and no transcoding url came back`() {
        val directOnly = response(source(directPlay = true, streams = subtitleStreams))
        val withTranscode = response(source(directPlay = true, transcodingUrl = hlsUrl, streams = subtitleStreams))

        assertTrue(PlaybackDecision.needsForcedTranscode(request.copy(subtitleStreamIndex = 5), directOnly))
        assertFalse(PlaybackDecision.needsForcedTranscode(request.copy(subtitleStreamIndex = 5), withTranscode))
        assertFalse(PlaybackDecision.needsForcedTranscode(request.copy(subtitleStreamIndex = 3), directOnly))
        assertFalse(PlaybackDecision.needsForcedTranscode(request, directOnly))
        assertFalse(PlaybackDecision.needsForcedTranscode(request.copy(subtitleStreamIndex = 5, forceTranscode = true), directOnly))
    }

    @Test
    fun `plan description never contains the url or token`() {
        val plan = decide(response(source(transcodingUrl = hlsUrl, subProtocol = MediaStreamProtocol.HLS)))
        assertFalse(plan.toString().contains("secret"))
        assertFalse(plan.toString().contains("http"))
    }
}
