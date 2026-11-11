package app.aafin.data.playback

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.DownloadRequest
import app.aafin.data.accounts.AccountStore
import app.aafin.data.jellyfin.JellyfinClients
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.jellyfinCall
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.mediaInfoApi
import org.jellyfin.sdk.model.api.PlaybackInfoDto
import org.jellyfin.sdk.model.api.PlaybackInfoResponse
import org.jellyfin.sdk.model.serializer.toUUID

/** A [PlaybackPlan] together with the Media3 item that plays it. */
data class ResolvedPlayback(val plan: PlaybackPlan, val mediaItem: MediaItem)

/** A completed download ready for offline playback; produced by `DownloadRepo.offlineMedia`. */
data class OfflineMedia(
    val itemId: String,
    val accountId: String,
    val mediaSourceId: String,
    val title: String,
    val runtimeMs: Long?,
    /** The Media3 download request: its URI, cache key and stream keys locate the cached data. */
    val downloadRequest: DownloadRequest,
    /** Subtitle files saved beside the download (`file://` URIs). */
    val subtitles: List<ExternalSubtitle>,
    val audioTracks: List<StreamTrack>,
    val subtitleTracks: List<StreamTrack>,
)

/**
 * Works out how to play an item.
 *
 * - Online: asks Jellyfin (`POST /Items/{id}/PlaybackInfo`) with this phone's device profile and
 *   the request's [StreamLimits], then [PlaybackDecision] picks direct play or the transcoding URL.
 * - Offline: wraps a completed download.
 *
 * The start position is deliberately not sent to the server: every stream (including HLS
 * transcodes, whose playlists cover the whole runtime) starts at zero and the player seeks.
 */
@OptIn(UnstableApi::class)
class PlaybackResolver(
    private val clients: JellyfinClients,
    private val accountStore: AccountStore,
    private val capabilities: CodecCapabilities,
) {

    /**
     * @param title shown in the media session / notification metadata.
     * @throws JellyfinException if signed out, the server is unreachable, or nothing is playable.
     */
    suspend fun resolveOnline(request: PlaybackRequest, title: String): ResolvedPlayback {
        val plan = planOnline(request)
        return ResolvedPlayback(plan, MediaItems.online(plan, title))
    }

    /**
     * The network half of [resolveOnline]: asks the server and decides, without building a
     * Media3 item. Used for downloads, which only need the plan.
     *
     * @throws JellyfinException as [resolveOnline].
     */
    suspend fun planOnline(request: PlaybackRequest): PlaybackPlan {
        val account = accountStore.active() ?: throw JellyfinException(JellyfinException.Kind.NOT_SIGNED_IN)
        val api = clients.forAccount(account)

        var effective = request
        var response = playbackInfo(api, account.userId, effective)
        if (PlaybackDecision.needsForcedTranscode(effective, response)) {
            effective = effective.copy(forceTranscode = true)
            response = playbackInfo(api, account.userId, effective)
        }

        var plan = PlaybackDecision.decide(account.serverUrl, account.id, effective, response)
        if (plan.subtitleBurnedIn && !effective.forceTranscode && effective.limits.transcodeSize != null) {
            // A subtitle is being burned in, so the video is re-encoded anyway - but that alone does
            // not make the server apply the picture-size ceiling. Asking again as a forced
            // transcode does (see DeviceProfileBuilder).
            effective = effective.copy(forceTranscode = true)
            response = playbackInfo(api, account.userId, effective)
            plan = PlaybackDecision.decide(account.serverUrl, account.id, effective, response)
        }
        return plan
    }

    fun resolveOffline(media: OfflineMedia, tier: QualityTier, subtitleStreamIndex: Int? = null): ResolvedPlayback {
        val plan = PlaybackPlan(
            request = PlaybackRequest(media.itemId, tier, subtitleStreamIndex = subtitleStreamIndex),
            accountId = media.accountId,
            mediaSourceId = media.mediaSourceId,
            playSessionId = null,
            playMethod = PlayMethod.OFFLINE,
            url = media.downloadRequest.uri.toString(),
            isHls = media.downloadRequest.mimeType == MimeTypes.APPLICATION_M3U8,
            externalSubtitles = media.subtitles,
            audioTracks = media.audioTracks,
            subtitleTracks = media.subtitleTracks,
            audioStreamIndex = null,
            subtitleStreamIndex = subtitleStreamIndex?.takeIf { index -> media.subtitles.any { it.index == index } },
            subtitleBurnedIn = false,
            runtimeMs = media.runtimeMs,
        )
        return ResolvedPlayback(plan, MediaItems.offline(media))
    }

    private suspend fun playbackInfo(api: ApiClient, userId: String, request: PlaybackRequest): PlaybackInfoResponse =
        jellyfinCall {
            api.mediaInfoApi.getPostedPlaybackInfo(
                itemId = request.itemId.toUUID(),
                data = PlaybackInfoDto(
                    userId = userId.toUUID(),
                    maxStreamingBitrate = request.limits.maxBitrate,
                    audioStreamIndex = request.audioStreamIndex,
                    // -1 = no subtitles. Leaving it unset lets the server pick one, which can force a burn-in transcode.
                    subtitleStreamIndex = request.subtitleStreamIndex ?: -1,
                    deviceProfile = DeviceProfileBuilder.build(request.limits, capabilities, request.forceTranscode),
                    enableDirectPlay = !request.forceTranscode,
                    enableDirectStream = !request.forceTranscode,
                    enableTranscoding = true,
                    allowVideoStreamCopy = true,
                    allowAudioStreamCopy = true,
                    alwaysBurnInSubtitleWhenTranscoding = false,
                ),
            ).content
        }
}

/** Builds Media3 [MediaItem]s. Subtitle tracks get the id [subtitleTrackId] so they can be selected later. */
@OptIn(UnstableApi::class)
object MediaItems {

    /** Id given to the side-loaded subtitle with Jellyfin stream [index]. */
    fun subtitleTrackId(index: Int): String = "aafin-sub-$index"

    fun online(plan: PlaybackPlan, title: String): MediaItem = MediaItem.Builder()
        .setMediaId(plan.itemId)
        .setUri(plan.url)
        .setMimeType(if (plan.isHls) MimeTypes.APPLICATION_M3U8 else null)
        .setSubtitleConfigurations(plan.externalSubtitles.map(::subtitleConfiguration))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(title).build())
        .build()

    fun offline(media: OfflineMedia): MediaItem = media.downloadRequest.toMediaItem().buildUpon()
        .setMediaId(media.itemId)
        .setSubtitleConfigurations(media.subtitles.map(::subtitleConfiguration))
        .setMediaMetadata(MediaMetadata.Builder().setTitle(media.title).build())
        .build()

    private fun subtitleConfiguration(subtitle: ExternalSubtitle): MediaItem.SubtitleConfiguration =
        MediaItem.SubtitleConfiguration.Builder(Uri.parse(subtitle.url))
            .setId(subtitleTrackId(subtitle.index))
            .setMimeType(subtitle.mimeType)
            .setLanguage(subtitle.language)
            .setLabel(subtitle.label)
            // Never auto-selected: PlaybackController chooses the text track explicitly.
            .setSelectionFlags(0)
            .setRoleFlags(C.ROLE_FLAG_SUBTITLE)
            .build()
}
