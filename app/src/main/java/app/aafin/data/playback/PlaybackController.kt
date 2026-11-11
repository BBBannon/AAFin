package app.aafin.data.playback

import android.content.Context
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.session.MediaSession
import app.aafin.data.downloads.DownloadRepo
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.MediaEntry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A playback failure in a form that is safe to show: no URLs, no tokens. */
data class PlaybackError(
    /** UK-English message for the user. */
    val message: String,
    /** Short technical code for logs and bug reports, e.g. `ERROR_CODE_IO_BAD_HTTP_STATUS`. */
    val code: String,
)

/** Everything a player screen needs to draw itself. Emitted by [PlaybackController.state]. */
data class PlaybackState(
    /** The item loaded in the player, or null when nothing is. */
    val item: MediaEntry? = null,
    val title: String = "",
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val isPlaying: Boolean = false,
    /** The player has run out of data and is waiting for more. */
    val isBuffering: Boolean = false,
    /** Asking the server how to play (before the player has anything to buffer). */
    val isLoading: Boolean = false,
    val isEnded: Boolean = false,
    /** Set when playback could not start or broke down. Cleared by the next successful start. */
    val error: PlaybackError? = null,
    val playMethod: PlayMethod? = null,
    val tier: QualityTier? = null,
    val audioTracks: List<StreamTrack> = emptyList(),
    val subtitleTracks: List<StreamTrack> = emptyList(),
    /** Jellyfin stream index of the audio track in use, if known. */
    val audioStreamIndex: Int? = null,
    /** Jellyfin stream index of the subtitle in use; null = subtitles off. */
    val subtitleStreamIndex: Int? = null,
) {
    val hasMedia: Boolean get() = item != null
    val isOffline: Boolean get() = playMethod == PlayMethod.OFFLINE
}

/**
 * Owns the app's single ExoPlayer and everything around it: resolving streams, audio focus,
 * the media session (steering-wheel buttons), track selection and watch-progress reports.
 *
 * Main thread only. Created once in `AppGraph` and shared by the car and phone sides; the car
 * player screen attaches [player] to a `PlayerView` and renders [state].
 *
 * Playback prefers a completed download of the item over streaming it.
 */
@MainThread
@OptIn(UnstableApi::class)
class PlaybackController(
    context: Context,
    private val resolver: PlaybackResolver,
    private val reporter: ProgressReporter,
    private val downloads: DownloadRepo,
    private val dataSourceFactory: DataSource.Factory,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private var createdPlayer: ExoPlayer? = null
    private var mediaSession: MediaSession? = null

    /**
     * The ExoPlayer, created on first use. Give it to a Media3 `PlayerView` to show the picture.
     * Use this controller's functions rather than driving the player directly, so that state
     * and progress reports stay correct (transport controls via the media session are fine).
     */
    val player: ExoPlayer
        get() = createdPlayer ?: createPlayer().also { createdPlayer = it }

    private class ActiveSession(val entry: MediaEntry, var plan: PlaybackPlan) {
        /** The start report has been sent; progress reports are held back until then. */
        var startReported = false

        /** The server has been told this play session is over. */
        var stopReported = false

        /** Playback reached the end of the item (as opposed to being stopped or failing). */
        var reachedEnd = false
    }

    private var session: ActiveSession? = null
    private var ticker: Job? = null
    private var transcodeFallbackTried = false

    /** Serialises operations that talk to the server, so two taps cannot interleave. */
    private val operations = Mutex()

    /** Bumped whenever the current item is replaced or stopped; lets slow server calls detect they are stale. */
    private var generation = 0

    /**
     * Stops whatever is playing and starts [entry] at [startPositionMs] (default: where the user left off).
     *
     * Plays a completed download if there is one, otherwise streams within [tier].
     *
     * @return true if playback started. On failure [state]'s `error` says why.
     */
    suspend fun play(entry: MediaEntry, tier: QualityTier, startPositionMs: Long = entry.resumePositionMs): Boolean {
        endSession()
        val myGeneration = generation
        transcodeFallbackTried = false
        _state.value = PlaybackState(item = entry, title = entry.displayTitle, isLoading = true, tier = tier)

        return operations.withLock {
            val resolved = try {
                val offline = downloads.offlineMedia(entry.id)
                if (offline != null) {
                    resolver.resolveOffline(offline, tier)
                } else {
                    resolver.resolveOnline(PlaybackRequest(entry.id, tier), entry.displayTitle)
                }
            } catch (e: JellyfinException) {
                if (myGeneration == generation) {
                    _state.update { it.copy(isLoading = false, error = PlaybackError(e.userMessage, e.kind.name)) }
                }
                return@withLock false
            }
            if (myGeneration != generation) return@withLock false
            begin(entry, resolved, startPositionMs, playWhenReady = true)
            true
        }
    }

    fun pause() {
        createdPlayer?.pause()
    }

    fun resume() {
        if (session != null) createdPlayer?.play()
    }

    fun togglePlayPause() {
        val player = createdPlayer ?: return
        if (session == null) return
        if (player.playWhenReady) player.pause() else player.play()
    }

    /** Jumps forwards (positive) or backwards (negative), clamped to the item's length. */
    fun seekBy(deltaMs: Long) {
        val player = createdPlayer ?: return
        seekTo(player.currentPosition + deltaMs)
    }

    fun seekTo(positionMs: Long) {
        val player = createdPlayer ?: return
        if (session == null) return
        val duration = player.duration
        val target = if (duration != C.TIME_UNSET) positionMs.coerceIn(0, duration) else positionMs.coerceAtLeast(0)
        player.seekTo(target)
    }

    /** Stops playback, tells the server where the user got to, and clears [state]. */
    fun stop() {
        endSession()
        _state.value = PlaybackState()
    }

    /**
     * Chooses an audio track by Jellyfin stream index (see [PlaybackState.audioTracks]).
     * When streaming, the server is asked again so it can transcode a track this phone can't decode.
     *
     * @throws JellyfinException if the server could not be reached; playback carries on unchanged.
     */
    suspend fun selectAudio(streamIndex: Int) = operations.withLock {
        val current = session ?: return@withLock
        val plan = current.plan
        if (plan.audioTracks.none { it.index == streamIndex }) return@withLock
        if (plan.playMethod == PlayMethod.OFFLINE) {
            current.plan = plan.copy(audioStreamIndex = streamIndex)
            applyTrackSelection()
            publish()
        } else {
            restartStream(plan.request.copy(audioStreamIndex = streamIndex))
        }
    }

    /**
     * Chooses a subtitle by Jellyfin stream index, or null to turn subtitles off.
     * Text subtitles switch instantly; picture-based ones restart the stream with the subtitle burned in.
     *
     * @throws JellyfinException if the server could not be reached; playback carries on unchanged.
     */
    suspend fun selectSubtitle(streamIndex: Int?) = operations.withLock {
        val current = session ?: return@withLock
        val plan = current.plan
        val target = streamIndex?.let { index -> plan.subtitleTracks.firstOrNull { it.index == index } ?: return@withLock }
        val needsServer = plan.playMethod != PlayMethod.OFFLINE &&
            (plan.subtitleBurnedIn || target?.delivery == SubtitleDelivery.BURN_IN)
        if (needsServer) {
            restartStream(plan.request.copy(subtitleStreamIndex = streamIndex))
        } else {
            current.plan = plan.copy(
                request = plan.request.copy(subtitleStreamIndex = streamIndex),
                subtitleStreamIndex = streamIndex,
                subtitleBurnedIn = false,
            )
            applyTrackSelection()
            publish()
        }
    }

    /**
     * Changes the streaming quality, keeping the current position. No effect on offline playback.
     *
     * @throws JellyfinException if the server could not be reached; playback carries on unchanged.
     */
    suspend fun setQuality(tier: QualityTier) = operations.withLock {
        val plan = session?.plan ?: return@withLock
        if (plan.playMethod == PlayMethod.OFFLINE || plan.request.tier == tier) return@withLock
        restartStream(plan.request.copy(tier = tier))
    }

    /**
     * Tries the current item again from the current position, after an error.
     *
     * @throws JellyfinException if the server still cannot be reached.
     */
    suspend fun retry() = operations.withLock {
        val current = session ?: return@withLock
        if (current.plan.playMethod == PlayMethod.OFFLINE) {
            val offline = downloads.offlineMedia(current.entry.id) ?: return@withLock
            val position = player.currentPosition
            begin(current.entry, resolver.resolveOffline(offline, current.plan.request.tier, current.plan.subtitleStreamIndex), position, true)
        } else {
            restartStream(current.plan.request)
        }
    }

    /** Releases the player and media session. Only for process shutdown and tests. */
    fun release() {
        stop()
        mediaSession?.release()
        mediaSession = null
        createdPlayer?.release()
        createdPlayer = null
    }

    // ---------------------------------------------------------------------------------------

    private fun createPlayer(): ExoPlayer {
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()
        val player = ExoPlayer.Builder(appContext)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSourceFactory))
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            // Keeps the CPU and Wi-Fi awake while playing with the phone's screen off in the car.
            .setWakeMode(C.WAKE_MODE_NETWORK)
            .setSeekBackIncrementMs(SEEK_BACK_MS)
            .setSeekForwardIncrementMs(SEEK_FORWARD_MS)
            .build()
        player.addListener(PlayerListener())
        // A plain MediaSession (no MediaSessionService / MediaBrowser) gives steering-wheel and
        // headset buttons something to talk to without registering AAFin as an Android Auto media app.
        mediaSession = MediaSession.Builder(appContext, player).setId(SESSION_ID).build()
        return player
    }

    private fun begin(entry: MediaEntry, resolved: ResolvedPlayback, startPositionMs: Long, playWhenReady: Boolean) {
        val current = ActiveSession(entry, resolved.plan)
        session = current
        _state.update { it.copy(error = null, isLoading = false, isEnded = false) }

        val player = player
        player.setMediaItem(resolved.mediaItem, startPositionMs.coerceAtLeast(0))
        applyTrackSelection()
        player.prepare()
        player.playWhenReady = playWhenReady
        publish()

        reportStarted(current)
        startTicker()
    }

    private fun reportStarted(current: ActiveSession) {
        val snapshot = snapshot(current)
        current.startReported = true
        scope.launch { reporter.started(snapshot) }
    }

    /** Re-asks the server how to play the current item with a changed [request], then carries on from the same position. */
    private suspend fun restartStream(request: PlaybackRequest) {
        val current = session ?: return
        val myGeneration = generation
        _state.update { it.copy(isLoading = true) }
        val resolved = try {
            resolver.resolveOnline(request, current.entry.displayTitle)
        } catch (e: JellyfinException) {
            if (myGeneration == generation) _state.update { it.copy(isLoading = false) }
            throw e
        }
        if (myGeneration != generation) return

        val player = player
        val position = player.currentPosition
        val playWhenReady = player.playWhenReady || current.stopReported // after an error, start playing again

        // Ends the old play session (and its transcode) before the new one starts. The player is
        // stopped first so the dying stream cannot raise errors while the report is in flight.
        val lastSnapshot = snapshot(current)
        player.stop()
        if (!current.stopReported) {
            current.stopReported = true
            reporter.stopped(lastSnapshot)
        }
        if (myGeneration != generation) return
        begin(current.entry, resolved, position, playWhenReady)
    }

    private fun endSession() {
        generation++
        ticker?.cancel()
        ticker = null
        val current = session ?: return
        session = null
        reportStopped(current)
        createdPlayer?.let {
            it.stop()
            it.clearMediaItems()
        }
    }

    private fun reportStopped(current: ActiveSession, positionOverrideMs: Long? = null) {
        if (current.stopReported) return
        current.stopReported = true
        val snapshot = snapshot(current).let { if (positionOverrideMs != null) it.copy(positionMs = positionOverrideMs) else it }
        scope.launch {
            reporter.stopped(snapshot)
            // No-op unless this item has been downloaded.
            downloads.saveResumePosition(snapshot.itemId, if (positionOverrideMs != null) 0 else snapshot.positionMs)
        }
    }

    private fun reportProgress() {
        val current = session ?: return
        if (!current.startReported || current.stopReported) return
        val snapshot = snapshot(current)
        scope.launch { reporter.progress(snapshot) }
    }

    private fun snapshot(current: ActiveSession): PlaybackSnapshot {
        val player = createdPlayer
        return PlaybackSnapshot(
            accountId = current.plan.accountId,
            itemId = current.plan.itemId,
            mediaSourceId = current.plan.mediaSourceId,
            playSessionId = current.plan.playSessionId,
            playMethod = current.plan.playMethod,
            positionMs = player?.currentPosition?.coerceAtLeast(0) ?: 0,
            isPaused = player?.playWhenReady != true,
            audioStreamIndex = current.plan.audioStreamIndex,
            subtitleStreamIndex = current.plan.subtitleStreamIndex,
        )
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            var seconds = 0
            while (isActive) {
                delay(TICK_MS)
                publish()
                seconds++
                if (seconds % PROGRESS_REPORT_EVERY_TICKS == 0 && createdPlayer?.isPlaying == true) reportProgress()
            }
        }
    }

    private fun publish() {
        val current = session ?: return
        val player = createdPlayer ?: return
        val plan = current.plan
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: plan.runtimeMs ?: current.entry.runtimeMs ?: 0
        _state.update {
            it.copy(
                item = current.entry,
                title = current.entry.displayTitle,
                positionMs = player.currentPosition.coerceAtLeast(0),
                durationMs = duration,
                isPlaying = player.isPlaying,
                isBuffering = player.playbackState == Player.STATE_BUFFERING,
                isEnded = player.playbackState == Player.STATE_ENDED,
                playMethod = plan.playMethod,
                tier = plan.request.tier,
                audioTracks = plan.audioTracks,
                subtitleTracks = plan.subtitleTracks,
                audioStreamIndex = plan.audioStreamIndex,
                subtitleStreamIndex = plan.subtitleStreamIndex,
            )
        }
    }

    /**
     * Points ExoPlayer at the tracks the plan asks for. Runs when playback starts and again
     * whenever the player discovers its tracks; it settles once the parameters stop changing.
     */
    private fun applyTrackSelection() {
        val plan = session?.plan ?: return
        val player = createdPlayer ?: return
        val groups = player.currentTracks.groups
        val builder = player.trackSelectionParameters.buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT)
            .clearOverridesOfType(C.TRACK_TYPE_AUDIO)

        // Subtitles: off unless a side-loaded track is chosen (burned-in ones are part of the picture).
        val wantedTextId = plan.subtitleStreamIndex?.takeUnless { plan.subtitleBurnedIn }?.let(MediaItems::subtitleTrackId)
        val textGroup = wantedTextId?.let { id -> groups.firstOrNull { it.type == C.TRACK_TYPE_TEXT && it.hasTrackId(id) } }
        if (textGroup != null) {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setOverrideForType(TrackSelectionOverride(textGroup.mediaTrackGroup, 0))
        } else {
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
        }

        // Audio: when the original file is playing, pick the matching track from it. Jellyfin and
        // the file list audio tracks in the same order; if the counts differ, leave ExoPlayer's choice.
        val originalFile = plan.playMethod == PlayMethod.DIRECT_PLAY || (plan.playMethod == PlayMethod.OFFLINE && !plan.isHls)
        if (originalFile) {
            val audioGroups = groups.filter { it.type == C.TRACK_TYPE_AUDIO }
            val position = plan.audioTracks.indexOfFirst { it.index == plan.audioStreamIndex }
            if (position >= 0 && audioGroups.size == plan.audioTracks.size && audioGroups[position].isTrackSupported(0)) {
                builder.setOverrideForType(TrackSelectionOverride(audioGroups[position].mediaTrackGroup, 0))
            }
        }

        val parameters = builder.build()
        if (parameters != player.trackSelectionParameters) player.trackSelectionParameters = parameters
    }

    /** Side-loaded tracks keep the id from their SubtitleConfiguration, prefixed with a source index when merged. */
    private fun Tracks.Group.hasTrackId(id: String): Boolean = (0 until length).any { index ->
        val formatId = getTrackFormat(index).id
        formatId == id || formatId?.endsWith(":$id") == true
    }

    private fun onEnded() {
        val current = session ?: return
        val duration = createdPlayer?.duration?.takeIf { it != C.TIME_UNSET }
        // Report the full length so the server marks the item as watched.
        reportStopped(current, positionOverrideMs = duration)
        current.reachedEnd = true
        ticker?.cancel()
        publish()
    }

    /** If the user seeks back into an item that had finished, start a fresh play session for it. */
    private fun onReady() {
        val current = session ?: return
        if (!current.reachedEnd) return
        current.reachedEnd = false
        current.stopReported = false
        reportStarted(current)
        startTicker()
    }

    private fun onError(error: PlaybackException) {
        val current = session ?: return
        // Code only: the exception message and cause can contain the stream URL.
        Log.w(TAG, "Playback error ${error.errorCodeName} (${current.plan.playMethod})")

        val canFallBack = isDecoderError(error.errorCode) &&
            current.plan.playMethod != PlayMethod.OFFLINE &&
            current.plan.playMethod != PlayMethod.TRANSCODE &&
            !transcodeFallbackTried
        if (canFallBack) {
            // The phone claimed a decoder it could not deliver: have the server transcode instead.
            transcodeFallbackTried = true
            scope.launch {
                try {
                    operations.withLock { restartStream(current.plan.request.copy(forceTranscode = true)) }
                } catch (e: JellyfinException) {
                    fail(current, PlaybackError(e.userMessage, e.kind.name))
                }
            }
            return
        }
        fail(current, PlaybackError(messageFor(error.errorCode), error.errorCodeName))
    }

    private fun fail(current: ActiveSession, error: PlaybackError) {
        reportStopped(current)
        ticker?.cancel()
        publish()
        _state.update { it.copy(isLoading = false, isPlaying = false, isBuffering = false, error = error) }
    }

    private fun isDecoderError(code: Int): Boolean = code in DECODER_ERRORS

    private fun messageFor(code: Int): String = when (code) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "Lost the connection to the server."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused to send this video."
        in DECODER_ERRORS -> "This phone couldn't decode the video. Try a lower quality."
        else -> "Playback stopped unexpectedly."
    }

    private inner class PlayerListener : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) = publish()

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            publish()
            reportProgress() // pause / resume
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            publish()
            when (playbackState) {
                Player.STATE_ENDED -> onEnded()
                Player.STATE_READY -> onReady()
                else -> Unit
            }
        }

        override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
            if (reason == Player.DISCONTINUITY_REASON_SEEK) {
                publish()
                reportProgress()
            }
        }

        override fun onTracksChanged(tracks: Tracks) = applyTrackSelection()

        override fun onPlayerError(error: PlaybackException) = onError(error)
    }

    private companion object {
        const val TAG = "AAFin.Playback"
        const val SESSION_ID = "aafin-playback"
        const val TICK_MS = 1_000L
        const val PROGRESS_REPORT_EVERY_TICKS = 10
        const val SEEK_BACK_MS = 10_000L
        const val SEEK_FORWARD_MS = 30_000L

        val DECODER_ERRORS = setOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED,
        )
    }
}
