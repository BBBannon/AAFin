package app.aafin.data.playback

import app.aafin.data.Ticks
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.JellyfinUrls
import org.jellyfin.sdk.model.api.MediaSourceInfo
import org.jellyfin.sdk.model.api.MediaStream
import org.jellyfin.sdk.model.api.MediaStreamProtocol
import org.jellyfin.sdk.model.api.MediaStreamType
import org.jellyfin.sdk.model.api.PlaybackErrorCode
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.api.SubtitleDeliveryMethod

/**
 * Pure logic that turns Jellyfin's `PlaybackInfo` answer into a [PlaybackPlan].
 * No Android or network dependencies, so it is fully unit-tested.
 */
object PlaybackDecision {

    /**
     * Chooses between direct play, direct stream and transcoding.
     *
     * 1. Direct play when the server allows it (and nothing forces a transcode).
     * 2. Otherwise the server's `TranscodingUrl` - labelled direct stream when the server is only
     *    re-wrapping, transcode when it re-encodes.
     * 3. Otherwise (older servers) a static stream when direct stream is allowed.
     *
     * @throws JellyfinException when the server reports an error or offers nothing playable.
     */
    fun decide(
        serverUrl: String,
        accountId: String,
        request: PlaybackRequest,
        response: PlaybackInfoResponse,
    ): PlaybackPlan {
        response.errorCode?.let { throw JellyfinException(it.toKind()) }
        val source = response.mediaSources.firstOrNull()
            ?: throw JellyfinException(JellyfinException.Kind.NO_COMPATIBLE_STREAM)
        val mediaSourceId = source.id ?: request.itemId

        val streams = source.mediaStreams.orEmpty()
        val audioTracks = streams.filter { it.type == MediaStreamType.AUDIO }.map { it.toTrack(delivery = null) }
        val subtitleStreams = streams.filter { it.type == MediaStreamType.SUBTITLE }
        val externalSubtitles = subtitleStreams.mapNotNull { it.toExternalSubtitle(serverUrl, request.itemId, mediaSourceId) }
        val sideLoadedIndexes = externalSubtitles.map { it.index }.toSet()
        val subtitleTracks = subtitleStreams.map {
            it.toTrack(if (it.index in sideLoadedIndexes) SubtitleDelivery.SIDE_LOADED else SubtitleDelivery.BURN_IN)
        }

        val selectedSubtitle = request.subtitleStreamIndex?.let { index -> subtitleTracks.firstOrNull { it.index == index } }
        val burnIn = selectedSubtitle?.delivery == SubtitleDelivery.BURN_IN
        val mustTranscode = request.forceTranscode || burnIn
        val transcodingUrl = source.transcodingUrl

        val method: PlayMethod
        val url: String
        when {
            source.supportsDirectPlay && !mustTranscode -> {
                method = PlayMethod.DIRECT_PLAY
                url = JellyfinUrls.directStream(serverUrl, request.itemId, mediaSourceId, source.eTag)
            }
            transcodingUrl != null -> {
                method = if (source.supportsDirectStream && !mustTranscode) PlayMethod.DIRECT_STREAM else PlayMethod.TRANSCODE
                url = JellyfinUrls.resolve(serverUrl, transcodingUrl)
            }
            source.supportsDirectStream && !mustTranscode -> {
                method = PlayMethod.DIRECT_STREAM
                url = JellyfinUrls.directStream(serverUrl, request.itemId, mediaSourceId, source.eTag)
            }
            else -> throw JellyfinException(JellyfinException.Kind.NO_COMPATIBLE_STREAM)
        }
        val isHls = method != PlayMethod.DIRECT_PLAY && transcodingUrl != null &&
            (source.transcodingSubProtocol == MediaStreamProtocol.HLS || transcodingUrl.substringBefore('?').endsWith(".m3u8", ignoreCase = true))

        return PlaybackPlan(
            request = request,
            accountId = accountId,
            mediaSourceId = mediaSourceId,
            playSessionId = response.playSessionId,
            playMethod = method,
            url = url,
            isHls = isHls,
            externalSubtitles = externalSubtitles,
            audioTracks = audioTracks,
            subtitleTracks = subtitleTracks,
            audioStreamIndex = request.audioStreamIndex ?: source.defaultAudioStreamIndex ?: audioTracks.firstOrNull()?.index,
            subtitleStreamIndex = selectedSubtitle?.index,
            subtitleBurnedIn = burnIn,
            runtimeMs = source.runTimeTicks?.let(Ticks::toMs),
        )
    }

    /**
     * True when the requested subtitle has to be burned in but the server's answer contains no
     * transcoding URL - the caller should ask again with direct play and direct stream disabled.
     */
    fun needsForcedTranscode(request: PlaybackRequest, response: PlaybackInfoResponse): Boolean {
        if (request.forceTranscode) return false
        val index = request.subtitleStreamIndex ?: return false
        val source = response.mediaSources.firstOrNull() ?: return false
        val stream = source.mediaStreams.orEmpty().firstOrNull { it.type == MediaStreamType.SUBTITLE && it.index == index }
            ?: return false
        return !stream.isSideLoadable() && source.transcodingUrl == null
    }

    private fun PlaybackErrorCode.toKind(): JellyfinException.Kind = when (this) {
        PlaybackErrorCode.NOT_ALLOWED -> JellyfinException.Kind.PLAYBACK_NOT_ALLOWED
        PlaybackErrorCode.NO_COMPATIBLE_STREAM -> JellyfinException.Kind.NO_COMPATIBLE_STREAM
        PlaybackErrorCode.RATE_LIMIT_EXCEEDED -> JellyfinException.Kind.TOO_MANY_STREAMS
    }

    /** Text subtitles the server can hand over as a file can be side-loaded; everything else is burned in. */
    private fun MediaStream.isSideLoadable(): Boolean =
        isTextSubtitleStream && (deliveryMethod == SubtitleDeliveryMethod.EXTERNAL || supportsExternalStream)

    private fun MediaStream.toExternalSubtitle(serverUrl: String, itemId: String, mediaSourceId: String): ExternalSubtitle? {
        if (!isSideLoadable()) return null
        val delivered = deliveryUrl?.takeIf { deliveryMethod == SubtitleDeliveryMethod.EXTERNAL }
        val deliveredMime = delivered?.let { SubtitleMime.forFormat(it.substringBefore('?').substringAfterLast('.', "")) }
        return if (delivered != null && deliveredMime != null) {
            ExternalSubtitle(index, JellyfinUrls.resolve(serverUrl, delivered), deliveredMime, language, displayTitle ?: title)
        } else {
            // No usable delivery URL: ask the server to convert the track to SubRip.
            ExternalSubtitle(
                index = index,
                url = JellyfinUrls.subtitle(serverUrl, itemId, mediaSourceId, index, "srt"),
                mimeType = SubtitleMime.SUBRIP,
                language = language,
                label = displayTitle ?: title,
            )
        }
    }

    private fun MediaStream.toTrack(delivery: SubtitleDelivery?): StreamTrack = StreamTrack(
        index = index,
        label = displayTitle ?: title ?: language ?: "Track $index",
        language = language,
        codec = codec,
        isDefault = isDefault,
        isForced = isForced,
        delivery = delivery,
    )
}
