package app.aafin.car.screens

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.ItemList
import androidx.car.app.model.MessageTemplate
import androidx.car.app.model.Row
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import app.aafin.R
import app.aafin.car.CarEnv
import app.aafin.car.CarFormat
import app.aafin.car.CarLog
import app.aafin.car.Paging
import app.aafin.car.SeriesLogic
import app.aafin.data.downloads.DownloadStatus
import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.playback.PlaybackState
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.StreamTrack
import app.aafin.data.playback.SubtitleDelivery
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * The player: a navigation template, so the video surface is fully visible and only the action
 * strips sit on top of it.
 *
 * - Action strip: Back, −10 s, Play/Pause, +30 s (the template allows four).
 * - Map action strip (car API 2+): Options, plus the standard pan action - without it the host
 *   does not pass taps and drags on the picture to the app. A tap only shows or hides the
 *   overlay; play/pause is the button's job alone.
 *
 * The picture, title, progress bar and messages are drawn on the surface by `VideoSurfaceRenderer`;
 * this screen only redraws when the play/pause button or the error state changes, never per second.
 *
 * When an episode ends the next one starts after a short countdown; after a film, or the last
 * episode, the player closes.
 */
class PlayerScreen(
    carContext: CarContext,
    env: CarEnv,
    first: MediaEntry,
) : CarScreen(carContext, env, firstStep = 1) {

    /** The parts of the playback state the template depends on. */
    private data class Face(val showPause: Boolean, val error: String?)

    private var current: MediaEntry = first
    private var face = Face(showPause = true, error = null)
    private var started = false
    private var upNext: Job? = null

    init {
        lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                Log.i(CarLog.PLAYBACK, "Player closed; stopping playback")
                env.overlay.setNotice(null)
                env.backend.stop()
            }
        })
        scope.launch { start(first) }
        scope.launch {
            // The button shows what pressing it will do, so it follows "told to play", not
            // "frames are moving": paused-while-buffering must still offer Play.
            env.backend.playback.map { Face(it.isLoading || (it.playWhenReady && !it.isEnded), it.error?.message) }
                .distinctUntilChanged()
                .collectLatest {
                    face = it
                    invalidate()
                    // Once more when things have settled: on the desktop head unit the strip kept
                    // its old icon after a pause, i.e. a refresh sent mid-touch can be lost.
                    delay(FACE_SETTLE_MS)
                    invalidate()
                }
        }
        scope.launch {
            env.backend.playback.map { it.isEnded && it.item?.id == current.id }.distinctUntilChanged().collect { ended ->
                upNext?.cancel()
                env.overlay.setNotice(null)
                if (ended) upNext = scope.launch { afterEnd() }
            }
        }
    }

    private suspend fun start(entry: MediaEntry) {
        current = entry
        // Shown at once: working out the car's profile and asking the server can take a moment.
        env.overlay.setNotice("Loading…")
        val tier = env.quality.tier()
        Log.i(CarLog.PLAYBACK, "Play ${entry.kind} ${entry.id} at $tier from ${entry.resumePositionMs / 1000} s")
        started = env.backend.play(entry, tier)
        env.overlay.setNotice(null)
        if (!started) Log.w(CarLog.PLAYBACK, "Playback did not start: ${env.backend.playback.value.error?.code}")
        env.overlay.showOverlay()
    }

    private suspend fun afterEnd() {
        val next = nextEpisode(current)
        if (next == null) {
            Log.i(CarLog.PLAYBACK, "Finished ${current.id}; nothing to play next")
            screenManager.remove(this)
            return
        }
        Log.i(CarLog.PLAYBACK, "Finished ${current.id}; next is ${next.id}")
        for (seconds in UP_NEXT_SECONDS downTo 1) {
            env.overlay.setNotice("Up next in $seconds s · ${CarFormat.episodeTitle(next)}")
            delay(1_000)
        }
        env.overlay.setNotice(null)
        // From here on this is an ordinary start: the state change it causes must not cancel it.
        upNext = null
        start(next)
    }

    /** The server's episode order when it can be reached, otherwise whatever is downloaded. */
    private suspend fun nextEpisode(entry: MediaEntry): MediaEntry? {
        val seriesId = entry.seriesId
        if (entry.kind != ItemKind.EPISODE || seriesId == null) return null
        val episodes = try {
            env.backend.episodes(seriesId)
        } catch (e: JellyfinException) {
            Log.i(CarLog.PLAYBACK, "Next episode from downloads (${e.kind})")
            env.backend.downloads.value
                .filter { it.status == DownloadStatus.COMPLETED && it.entry.seriesId == seriesId }
                .map { it.entry }
                .sortedWith(compareBy({ it.parentIndexNumber ?: Int.MAX_VALUE }, { it.indexNumber ?: Int.MAX_VALUE }))
        }
        return SeriesLogic.nextEpisode(entry, episodes)
    }

    private fun retry() {
        scope.launch {
            if (!started) {
                start(current)
                return@launch
            }
            try {
                env.backend.retry()
            } catch (e: JellyfinException) {
                toast(e.userMessage)
            }
        }
    }

    override fun content(): Content {
        face.error?.let { return error(it) }

        val back = iconAction(R.drawable.ic_car_back) { screenManager.remove(this) }
        val playPause = iconAction(if (face.showPause) R.drawable.ic_car_pause else R.drawable.ic_car_play) {
            env.backend.togglePlayPause()
            env.overlay.showOverlay()
        }
        val rewind = seekAction("−10 s", R.drawable.ic_car_rewind, -10_000)
        val forward = seekAction("+30 s", R.drawable.ic_car_forward, 30_000)
        val options = iconAction(R.drawable.ic_car_tune) { screenManager.push(PlayerOptionsScreen(carContext, env)) }

        val builder = NavigationTemplate.Builder()
        if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_2) {
            builder.setActionStrip(ActionStrip.Builder().addAction(back).addAction(rewind).addAction(playPause).addAction(forward).build())
            builder.setMapActionStrip(ActionStrip.Builder().addAction(options).addAction(Action.PAN).build())
        } else {
            // Very old hosts have one strip of four and no taps on the picture.
            builder.setActionStrip(ActionStrip.Builder().addAction(back).addAction(playPause).addAction(forward).addAction(options).build())
        }
        return Content(builder.build(), structure = "player")
    }

    private fun seekAction(title: String, icon: Int, deltaMs: Long): Action =
        Action.Builder().setTitle(title).setIcon(icon(icon)).setOnClickListener {
            env.backend.seekBy(deltaMs)
            env.overlay.showOverlay()
        }.build()

    @Suppress("DEPRECATION") // see CarScreen.loadingList
    private fun error(message: String): Content {
        val template = MessageTemplate.Builder(message)
            .setTitle(current.displayTitle)
            .setHeaderAction(Action.BACK)
            .setIcon(CarIcon.ERROR)
            .addAction(textAction("Retry", ::retry))
            .addAction(textAction("Back") { screenManager.remove(this) })
            .build()
        return Content(template, structure = "error:$message")
    }

    private companion object {
        const val UP_NEXT_SECONDS = 8
        const val FACE_SETTLE_MS = 1_000L
    }
}

/** Audio track, subtitles and quality for what is playing. Opens on top of the player. */
class PlayerOptionsScreen(carContext: CarContext, env: CarEnv) : CarScreen(carContext, env, firstStep = 2) {

    init {
        // The second line of each row follows the player (e.g. after a choice has been applied).
        scope.launch {
            env.backend.playback
                .map { Triple(it.audioStreamIndex, it.subtitleStreamIndex, it.tier) }
                .distinctUntilChanged()
                .collect { invalidate() }
        }
    }

    override fun content(): Content {
        val state = env.backend.playback.value
        val items = ItemList.Builder().setNoItemsMessage("Nothing is playing.")
        if (state.hasMedia) {
            val audio = state.audioTracks.firstOrNull { it.index == state.audioStreamIndex }
            val subtitle = state.subtitleTracks.firstOrNull { it.index == state.subtitleStreamIndex }
            items.addItem(
                row(AUDIO, audio?.let(::trackName) ?: "Default", browsable = true) {
                    open { ChoiceScreen(carContext, env, it, ChoiceScreen.Kind.AUDIO) }
                },
            )
            items.addItem(
                row(SUBTITLES, subtitle?.let(::trackName) ?: "Off", browsable = true) {
                    open { ChoiceScreen(carContext, env, it, ChoiceScreen.Kind.SUBTITLES) }
                },
            )
            items.addItem(
                row(QUALITY, if (state.isOffline) "Downloaded copy" else state.tier?.label ?: "", browsable = true) {
                    if (state.isOffline) {
                        toast("This is a download, so its quality is fixed")
                    } else {
                        open { ChoiceScreen(carContext, env, it, ChoiceScreen.Kind.QUALITY) }
                    }
                },
            )
        }
        return listContent("Options", items.build(), structure = if (state.hasMedia) "options" else "empty")
    }

    private companion object {
        const val AUDIO = "Audio"
        const val SUBTITLES = "Subtitles"
        const val QUALITY = "Quality"
    }
}

internal fun trackName(track: StreamTrack): String =
    track.label.ifBlank { track.language ?: "Track ${track.index}" } +
        if (track.delivery == SubtitleDelivery.BURN_IN) " (restarts the video)" else ""

/**
 * One list of choices with the current one ticked. Choosing applies it and goes back to Options.
 * Quality is saved to this car's profile first, so it also applies to whatever is played next.
 */
class ChoiceScreen(
    carContext: CarContext,
    env: CarEnv,
    firstStep: Int,
    private val kind: Kind,
) : CarScreen(carContext, env, firstStep) {

    enum class Kind(val title: String) { AUDIO("Audio"), SUBTITLES("Subtitles"), QUALITY("Quality") }

    private class Choice(val title: String, val selected: Boolean, val apply: suspend () -> Unit)

    /** Taken once: the list must not reshuffle under the user's finger. */
    private val choices: List<Choice> = choices(env.backend.playback.value)
    private var applying = false

    private fun choices(state: PlaybackState): List<Choice> {
        val limit = Paging.usableLimit(env.limits.list, LIST_CAP)
        return when (kind) {
            Kind.AUDIO -> state.audioTracks.map { track ->
                Choice(trackName(track), track.index == state.audioStreamIndex) { env.backend.selectAudio(track.index) }
            }
            Kind.SUBTITLES -> listOf(Choice("Off", state.subtitleStreamIndex == null) { env.backend.selectSubtitle(null) }) +
                state.subtitleTracks.map { track ->
                    Choice(trackName(track), track.index == state.subtitleStreamIndex) { env.backend.selectSubtitle(track.index) }
                }
            Kind.QUALITY -> QualityTier.entries.map { tier ->
                Choice(tier.label, tier == state.tier) {
                    env.quality.setTier(tier)
                    env.backend.setQuality(tier)
                }
            }
        }.fitTo(limit)
    }

    /** Keeps the list within the car's limit without ever dropping the current choice. */
    private fun List<Choice>.fitTo(limit: Int): List<Choice> {
        if (size <= limit) return this
        val kept = take(limit)
        val selected = firstOrNull { it.selected }
        return if (selected == null || selected in kept) kept else kept.dropLast(1) + selected
    }

    override fun content(): Content {
        if (choices.isEmpty()) {
            return listContent(kind.title, ItemList.Builder().setNoItemsMessage("Nothing to choose from.").build(), "empty")
        }
        val items = ItemList.Builder()
        choices.forEach { items.addItem(Row.Builder().setTitle(it.title).build()) }
        choices.indexOfFirst { it.selected }.takeIf { it >= 0 }?.let(items::setSelectedIndex)
        items.setOnSelectedListener(::choose)
        return listContent(kind.title, items.build(), structure = choices.joinToString("|") { it.title })
    }

    private fun choose(index: Int) {
        val choice = choices.getOrNull(index) ?: return
        if (applying) return
        applying = true
        scope.launch {
            try {
                Log.i(CarLog.PLAYBACK, "Option chosen: ${kind.name} #$index")
                choice.apply()
                env.overlay.showOverlay()
                screenManager.remove(this@ChoiceScreen)
            } catch (e: JellyfinException) {
                // Playback carries on unchanged; say why and let them try again.
                Log.w(CarLog.PLAYBACK, "Option not applied (${e.kind})")
                toast(e.userMessage)
                applying = false
                invalidate()
            }
        }
    }
}
