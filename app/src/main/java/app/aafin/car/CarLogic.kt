package app.aafin.car

import app.aafin.data.jellyfin.ItemKind
import app.aafin.data.jellyfin.LibraryType
import app.aafin.data.jellyfin.MediaEntry
import app.aafin.data.profiles.CarProfileStore
import java.util.Locale
import kotlin.math.abs

/*
 * Pure logic for the car screens: no Android or Car App Library types, so all of it is unit-tested.
 */

/**
 * Android Auto's "task" rule: at most [MAX_STEPS] templates in a row, and the fifth may only be a
 * message or the player. Going back gives steps back; reaching the player starts a new task.
 *
 * Every car screen knows which step it is, and nothing is offered that would break the rule.
 */
object FlowBudget {
    const val MAX_STEPS = 5

    /** May a template be shown as step [step]? [terminal] = a message or the player. */
    fun allows(step: Int, terminal: Boolean): Boolean = step < MAX_STEPS || (step == MAX_STEPS && terminal)

    /**
     * May a screen at [step] offer a further browsing screen (a "More…" page, or search) whose
     * own content still needs [stepsBelow] browsing screens before the player?
     */
    fun canPage(step: Int, stepsBelow: Int): Boolean = step + 1 + stepsBelow < MAX_STEPS

    /** Browsing screens a library of this type needs between its grid and the player. */
    fun stepsBelow(type: LibraryType): Int = when (type) {
        LibraryType.MOVIES -> 0
        LibraryType.TV_SHOWS, LibraryType.MIXED -> 1
        LibraryType.BOX_SETS -> 2
    }
}

/** How many items of a longer list to show on one screen, and whether a "More…" entry follows them. */
data class PageWindow(val count: Int, val hasMore: Boolean)

object Paging {
    /** The host's limit can be tiny or huge; keep it usable. */
    fun usableLimit(hostLimit: Int, cap: Int): Int = hostLimit.coerceIn(2, cap)

    /**
     * @param remaining items left from the current start position.
     * @param limit how many entries the car allows on one screen.
     * @param canPage whether another page may be opened (see [FlowBudget.canPage]).
     */
    fun window(remaining: Int, limit: Int, canPage: Boolean): PageWindow = when {
        remaining <= limit -> PageWindow(remaining.coerceAtLeast(0), hasMore = false)
        canPage -> PageWindow(limit - 1, hasMore = true)
        else -> PageWindow(limit, hasMore = false)
    }

    /** "1–5 of 300" when only part of a list fits, otherwise null. */
    fun rangeLabel(start: Int, count: Int, total: Int): String? =
        if (count <= 0 || (start == 0 && count >= total)) null else "${start + 1}–${start + count} of $total"
}

/** User-facing text for car rows and the video overlay. */
object CarFormat {
    /** "1:02:03" or "2:05". Negative values count as zero. */
    fun clock(ms: Long): String {
        val total = ms.coerceAtLeast(0) / 1000
        val hours = total / 3600
        val minutes = (total % 3600) / 60
        val seconds = total % 60
        return if (hours > 0) {
            String.format(Locale.UK, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(Locale.UK, "%d:%02d", minutes, seconds)
        }
    }

    /** "+0:45" / "−1:30" for the seek preview. */
    fun signedClock(ms: Long): String = (if (ms < 0) "−" else "+") + clock(abs(ms))

    /** "1 h 52 min", "45 min"; anything under a minute is "1 min". */
    fun duration(ms: Long): String {
        val minutes = ((ms + 30_000) / 60_000).coerceAtLeast(1)
        return if (minutes >= 60) {
            val rest = minutes % 60
            if (rest == 0L) "${minutes / 60} h" else "${minutes / 60} h $rest min"
        } else {
            "$minutes min"
        }
    }

    /** Row title for an episode inside its series: "S1 E3 · The One With…". */
    fun episodeTitle(entry: MediaEntry): String {
        val number = when {
            entry.parentIndexNumber != null && entry.indexNumber != null -> "S${entry.parentIndexNumber} E${entry.indexNumber}"
            entry.indexNumber != null -> "E${entry.indexNumber}"
            else -> null
        }
        return listOfNotNull(number, entry.name.ifBlank { null }).joinToString(" · ").ifEmpty { "Episode" }
    }

    /** Row title for a film or episode outside its series: "Series · S1 E3 · Name", or the film's name. */
    fun entryTitle(entry: MediaEntry): String =
        if (entry.kind == ItemKind.EPISODE && !entry.seriesName.isNullOrBlank()) {
            "${entry.seriesName} · ${episodeTitle(entry)}"
        } else {
            entry.name.ifBlank { "Untitled" }
        }

    /** Second line of a row: watched state, time left, length, or how much of a series is left. */
    fun status(entry: MediaEntry): String? = when {
        entry.kind == ItemKind.SERIES || entry.kind == ItemKind.SEASON || entry.kind == ItemKind.BOX_SET ->
            entry.unplayedCount?.let { if (it == 0) "Watched" else "$it to watch" } ?: entry.productionYear?.toString()
        entry.resumePositionMs > 0 && entry.runtimeMs != null && entry.runtimeMs > entry.resumePositionMs ->
            "${duration(entry.runtimeMs - entry.resumePositionMs)} left"
        entry.resumePositionMs > 0 -> "Started"
        entry.played -> listOfNotNull("Watched", entry.runtimeMs?.let(::duration)).joinToString(" · ")
        else -> listOfNotNull(
            entry.productionYear?.takeIf { entry.kind == ItemKind.MOVIE }?.toString(),
            entry.runtimeMs?.let(::duration),
        ).joinToString(" · ").ifEmpty { null }
    }
}

/** The episodes of one season, in order. */
data class SeasonGroup(val key: String, val title: String, val episodes: List<MediaEntry>)

/** How a series is laid out on the car screen. */
sealed interface SeriesLayout {
    /** Everything fits: one list with a section per season. */
    data class Sectioned(val seasons: List<SeasonGroup>) : SeriesLayout

    /** Too many episodes: list the seasons, each opening its own screen. */
    data class SeasonPicker(val seasons: List<SeasonGroup>) : SeriesLayout

    /** A flat run of [episodes]; when they do not all fit, showing starts at [start] (the next one to watch). */
    data class Episodes(val episodes: List<MediaEntry>, val start: Int) : SeriesLayout
}

object SeriesLogic {
    /** Groups episodes (already in order) by season, in the order the seasons first appear. */
    fun groupBySeason(episodes: List<MediaEntry>): List<SeasonGroup> =
        episodes.groupBy { it.seasonId ?: "number:${it.parentIndexNumber}" }.map { (key, list) ->
            val first = list.first()
            val title = first.seasonName?.takeIf { it.isNotBlank() }
                ?: first.parentIndexNumber?.let { if (it == 0) "Specials" else "Season $it" }
                ?: "Episodes"
            SeasonGroup(key, title, list)
        }

    /** Index of the first episode not yet watched, or 0 if everything has been. */
    fun firstUnwatched(episodes: List<MediaEntry>): Int = episodes.indexOfFirst { !it.played }.coerceAtLeast(0)

    /**
     * @param seasonKey non-null when one season was picked from a [SeriesLayout.SeasonPicker].
     * @param limit rows the car allows on one screen.
     * @param canOpenSeason whether a season screen may still be pushed (see [FlowBudget.allows]).
     */
    fun layout(episodes: List<MediaEntry>, seasonKey: String?, limit: Int, canOpenSeason: Boolean): SeriesLayout {
        val seasons = groupBySeason(episodes)
        if (seasonKey != null) {
            val season = seasons.firstOrNull { it.key == seasonKey }?.episodes ?: emptyList()
            return SeriesLayout.Episodes(season, windowStart(season, limit))
        }
        return when {
            episodes.size <= limit -> SeriesLayout.Sectioned(seasons)
            seasons.size > 1 && canOpenSeason -> SeriesLayout.SeasonPicker(seasons)
            else -> SeriesLayout.Episodes(episodes, windowStart(episodes, limit))
        }
    }

    /** Where a list too long for the screen should start so the next episode to watch is on it. */
    fun windowStart(episodes: List<MediaEntry>, limit: Int): Int =
        if (episodes.size <= limit) 0 else firstUnwatched(episodes).coerceAtMost(episodes.size - limit)

    /** The episode after [current] in [episodes] (in order), or null at the end of the series. */
    fun nextEpisode(current: MediaEntry, episodes: List<MediaEntry>): MediaEntry? {
        val index = episodes.indexOfFirst { it.id == current.id }
        if (index >= 0) return episodes.getOrNull(index + 1)
        // The current episode is not in the list (e.g. only some episodes are downloaded): go by numbering.
        val season = current.parentIndexNumber ?: return null
        val number = current.indexNumber ?: return null
        return episodes
            .filter { it.parentIndexNumber != null && it.indexNumber != null }
            .sortedWith(compareBy({ it.parentIndexNumber }, { it.indexNumber }))
            .firstOrNull { it.parentIndexNumber!! > season || (it.parentIndexNumber == season && it.indexNumber!! > number) }
    }
}

/** What the car says about itself; any part may be missing. */
data class CarModel(val manufacturer: String?, val name: String?, val year: Int?)

/** The drawing surface the head unit gave us. */
data class SurfaceSpec(val width: Int, val height: Int, val dpi: Int)

/** The key a car's profile is stored under, and the name a new profile starts with. */
data class CarIdentity(val key: String, val suggestedName: String) {
    companion object {
        const val UNKNOWN_KEY = "screen:unknown"

        /**
         * The car's make and model when it reports them, otherwise its screen. The same for wired
         * and wireless Android Auto: both come from the head unit, not from the connection.
         */
        fun of(model: CarModel?, surface: SurfaceSpec?): CarIdentity {
            val modelKey = model?.let { CarProfileStore.keyForModel(it.manufacturer, it.name, it.year) }
            return when {
                model != null && modelKey != null -> CarIdentity(
                    modelKey,
                    listOfNotNull(model.manufacturer?.trim(), model.name?.trim()).filter { it.isNotEmpty() }.joinToString(" "),
                )
                surface != null -> CarIdentity(
                    CarProfileStore.keyForScreen(surface.width, surface.height, surface.dpi),
                    "Car screen ${surface.width}×${surface.height}",
                )
                else -> CarIdentity(UNKNOWN_KEY, "My car")
            }
        }
    }
}

/** Turns a sideways drag on the video into a seek: one full screen width is [FULL_WIDTH_MS]. */
object SeekGesture {
    const val FULL_WIDTH_MS = 180_000L
    private const val MINIMUM_MS = 2_000L

    /**
     * @param dragRightPx how far the finger has travelled to the right (negative = left).
     * @return milliseconds to seek by; 0 for a drag too small to mean anything.
     */
    fun seekMs(dragRightPx: Float, surfaceWidthPx: Int): Long {
        if (surfaceWidthPx <= 0) return 0
        val ms = (dragRightPx / surfaceWidthPx * FULL_WIDTH_MS).toLong()
        return if (abs(ms) < MINIMUM_MS) 0 else ms
    }
}

/** Padding that keeps the picture inside the part of the surface Android Auto is not drawing over. */
data class SurfaceInsets(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    companion object {
        val NONE = SurfaceInsets(0, 0, 0, 0)

        /**
         * From the visible area the host reports (left/top/right/bottom in surface pixels).
         * An empty area means "unknown", and a sliver is no use for video: both give no padding.
         */
        fun of(left: Int, top: Int, right: Int, bottom: Int, surfaceWidth: Int, surfaceHeight: Int): SurfaceInsets {
            val l = left.coerceIn(0, surfaceWidth)
            val t = top.coerceIn(0, surfaceHeight)
            val r = right.coerceIn(l, surfaceWidth)
            val b = bottom.coerceIn(t, surfaceHeight)
            val tooSmall = (r - l) * MIN_FRACTION < surfaceWidth || (b - t) * MIN_FRACTION < surfaceHeight
            return if (tooSmall) NONE else SurfaceInsets(l, t, surfaceWidth - r, surfaceHeight - b)
        }

        /** The picture is kept inside the visible area only while that is at least a quarter of each side. */
        private const val MIN_FRACTION = 4
    }
}

/** When the title/progress overlay on the video is showing. */
object OverlayRule {
    const val AUTO_HIDE_MS = 4_000L

    /** The overlay stays up while paused, loading or failed; it only times out during playback. */
    fun mayAutoHide(isPlaying: Boolean): Boolean = isPlaying
}
