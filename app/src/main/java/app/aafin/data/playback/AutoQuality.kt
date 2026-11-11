package app.aafin.data.playback

import java.util.Locale

/*
 * Automatic quality: pure logic, no Android or network types, so all of it is unit-tested.
 *
 * Android Auto re-encodes the car surface itself, so the phone-to-car link costs the same whatever
 * is played. What AAFin controls is the stream from the Jellyfin server: its bitrate (mobile data
 * throughput) and its picture size (no point in more pixels than the car screen shows).
 */

/** The car's drawing surface in pixels. */
data class ScreenSize(val widthPx: Int, val heightPx: Int)

/** The standard picture sizes a transcode is limited to. */
enum class PictureSize(val width: Int, val height: Int, val label: String) {
    P360(640, 360, "360p"),
    P480(854, 480, "480p"),
    P720(1280, 720, "720p"),
    P1080(1920, 1080, "1080p");

    companion object {
        /** Used when the car has not told us its screen size yet. */
        val UNKNOWN_SCREEN = P720

        /**
         * The smallest standard size that still fills the screen: a 16:9 picture is fitted to the
         * surface and the next size up from that is chosen. 800×400 shows a 711×400 picture, so 480p.
         */
        fun forScreen(screen: ScreenSize?): PictureSize {
            if (screen == null || screen.widthPx <= 0 || screen.heightPx <= 0) return UNKNOWN_SCREEN
            val shownWidth = minOf(screen.widthPx.toLong(), screen.heightPx * 16L / 9)
            return entries.firstOrNull { it.width >= shownWidth } ?: P1080
        }

        /** The standard size for a tier's width cap (854 → 480p, 1280 → 720p, 1920 → 1080p). */
        fun forWidth(maxWidth: Int): PictureSize = entries.firstOrNull { it.width >= maxWidth } ?: P1080
    }
}

/** What Auto is asking the server for: a total bitrate cap and the largest picture worth sending. */
data class AutoLevel(val bitrate: Int, val picture: PictureSize) {
    /** "480p · 2.5 Mbps" */
    val label: String get() = "${picture.label} · ${AutoQuality.mbps(bitrate)}"
}

/** The numbers behind Auto, and the arithmetic on them. */
object AutoQuality {
    /** Share of the measured throughput a stream may use, in percent; the rest is headroom for dips. */
    const val ESTIMATE_SHARE_PERCENT = 70

    /** A starting cap is never below this, however poor the estimate: 480p still looks fine at it. */
    const val MIN_START_BITRATE = 1_500_000

    /** Auto never asks for more than this; "Original" and the 1080p tiers exist for that. */
    const val MAX_BITRATE = 8_000_000

    /** Starting cap when nothing has been measured yet. */
    const val NO_ESTIMATE_BITRATE = 3_000_000

    /** The steps Auto moves between, lowest (the floor) first. */
    val LADDER = listOf(1_000_000, 1_500_000, 2_500_000, 4_000_000, 6_000_000, 8_000_000)

    private const val ROUND_TO = 100_000

    /** The most a screen of this class can make use of (h264): more bitrate buys nothing visible. */
    fun ceilingBitrate(screen: PictureSize): Int = when (screen) {
        PictureSize.P360 -> 1_500_000
        PictureSize.P480 -> 2_500_000
        PictureSize.P720 -> 4_000_000
        PictureSize.P1080 -> 8_000_000
    }

    /** The picture size a bitrate can carry well, never larger than the screen needs. */
    fun level(bitrate: Int, screen: ScreenSize?): AutoLevel {
        val carried = when {
            bitrate < 1_500_000 -> PictureSize.P360
            bitrate < 3_500_000 -> PictureSize.P480
            bitrate < 7_000_000 -> PictureSize.P720
            else -> PictureSize.P1080
        }
        return AutoLevel(bitrate, minOf(carried, PictureSize.forScreen(screen)))
    }

    /**
     * Where Auto starts: [ESTIMATE_SHARE_PERCENT] of the measured throughput, kept between
     * [MIN_START_BITRATE] and [MAX_BITRATE] and never above what the screen can use.
     *
     * @param estimateBps measured throughput in bits per second, or null if nothing is known.
     */
    fun startLevel(estimateBps: Long?, screen: ScreenSize?): AutoLevel {
        val wanted = estimateBps?.let { usableShare(it).coerceIn(MIN_START_BITRATE.toLong(), MAX_BITRATE.toLong()).toInt() }
            ?: NO_ESTIMATE_BITRATE
        val capped = minOf(wanted, ceilingBitrate(PictureSize.forScreen(screen)))
        return level(capped / ROUND_TO * ROUND_TO, screen)
    }

    /** The part of a measured throughput a stream may use. */
    fun usableShare(estimateBps: Long): Long = estimateBps.coerceIn(0, Long.MAX_VALUE / 100) * ESTIMATE_SHARE_PERCENT / 100

    /**
     * The next step down, or null at the floor.
     *
     * @param belowBitrate when the original file is being played directly, its bitrate: only a cap
     *   below that makes the server transcode, so steps at or above it are skipped.
     */
    fun lower(level: AutoLevel, screen: ScreenSize?, belowBitrate: Int? = null): AutoLevel? {
        val limit = minOf(level.bitrate, belowBitrate ?: level.bitrate)
        return LADDER.lastOrNull { it < limit }?.let { level(it, screen) }
    }

    /** The next step up, never above [upTo]; null if there is none. */
    fun higher(level: AutoLevel, screen: ScreenSize?, upTo: Int): AutoLevel? {
        val next = minOf(LADDER.firstOrNull { it > level.bitrate } ?: return null, upTo)
        return if (next > level.bitrate) level(next, screen) else null
    }

    /** What the Options screen shows beside "Auto": "480p · 2.5 Mbps", or that the original file is playing. */
    fun describe(level: AutoLevel, playMethod: PlayMethod?): String = when (playMethod) {
        PlayMethod.DIRECT_PLAY, PlayMethod.DIRECT_STREAM -> "Original picture · up to ${mbps(level.bitrate)}"
        else -> level.label
    }

    /** "2.5 Mbps", "4 Mbps". */
    fun mbps(bitrate: Int): String {
        val tenths = (bitrate + 50_000) / 100_000
        return if (tenths % 10 == 0) "${tenths / 10} Mbps" else String.format(Locale.UK, "%d.%d Mbps", tenths / 10, tenths % 10)
    }
}

/** Why Auto wants to change level. */
enum class AutoReason(val logText: String) {
    TWO_STALLS("2 stalls within 60 s"),
    LONG_STALL("a stall longer than 5 s"),
    CLEAN_RUN("10 min without a stall"),
}

/**
 * A change Auto has decided on. [to] is null when there is nothing lower left to try (the floor,
 * or an original file that is already smaller than the lowest step).
 */
data class AutoChange(val from: AutoLevel, val to: AutoLevel?, val reason: AutoReason) {
    val isStepDown: Boolean get() = to == null || to.bitrate < from.bitrate
}

/**
 * Watches one player for stalls and decides when Auto should step down or back up.
 *
 * - A stall is the player running out of data while it should be playing. Waiting for the first
 *   frames of a stream, and waiting after a seek, are not stalls.
 * - Two stalls within [STALL_WINDOW_MS], or one longer than [LONG_STALL_MS], ask for a step down.
 * - [STEP_UP_AFTER_MS] of playing without a stall asks for one step back up - never above where
 *   this item would have started, and only while the measured throughput has room for it.
 * - Hysteresis: a level that was stepped up to and then stalled again is not tried again for
 *   this item, so the stream cannot bounce between two levels.
 * - A step down is remembered for [STEP_UP_AFTER_MS], so the next episode does not start too high.
 *
 * Decisions are proposals: the caller applies them (which needs the server) and then reports back
 * with [applied] or [failed]. Nothing else is proposed in between. All times are in milliseconds
 * on any steadily increasing clock. Not thread-safe: one thread only.
 */
class AutoQualityTracker {

    /** The level in use, or null before [start]. */
    var level: AutoLevel? = null
        private set

    private var screen: ScreenSize? = null

    /** Step-ups stop here: the level this item would have started at, lowered after a failed step-up. */
    private var upperBitrate = 0
    private var steppedUp = false
    private var lastStepDownAtMs: Long? = null

    private var directSourceBitrate: Int? = null
    private var ready = false
    private var seeking = false
    private var stallStartedAtMs: Long? = null
    private val recentStalls = ArrayDeque<Long>()
    private var cleanPlayMs = 0L
    private var lastTickMs: Long? = null
    private var pending = false

    /** Stalls counted in the current window (for logs). */
    val stallCount: Int get() = recentStalls.size

    /** A counted stall is in progress. */
    val isStalled: Boolean get() = stallStartedAtMs != null

    /**
     * Picks the level for a newly played item (or when the user switches to Auto).
     * A recent step down carries over, so a run of episodes on a poor connection stays low.
     */
    fun start(nowMs: Long, estimateBps: Long?, screen: ScreenSize?): AutoLevel {
        val ideal = AutoQuality.startLevel(estimateBps, screen)
        val steppedDownAt = lastStepDownAtMs
        val carried = level?.takeIf { steppedDownAt != null && nowMs - steppedDownAt < STEP_UP_AFTER_MS && it.bitrate < ideal.bitrate }
        val chosen = if (carried != null) AutoQuality.level(carried.bitrate, screen) else ideal
        this.screen = screen
        upperBitrate = ideal.bitrate
        steppedUp = false
        pending = false
        level = chosen
        resetStalls()
        onStreamLoading(directSourceBitrate = null)
        return chosen
    }

    /**
     * A stream is being loaded into the player (first start, or a restart for any reason).
     *
     * @param directSourceBitrate the file's own bitrate when it is played without transcoding, if known.
     */
    fun onStreamLoading(directSourceBitrate: Int?) {
        this.directSourceBitrate = directSourceBitrate
        ready = false
        seeking = false
        stallStartedAtMs = null
    }

    /** The player has enough data and is (or could be) showing frames. */
    fun onReady() {
        ready = true
        seeking = false
        stallStartedAtMs = null
    }

    /** The user jumped to another position: the wait that follows is theirs, not the connection's. */
    fun onSeek() {
        seeking = true
        stallStartedAtMs = null
    }

    /** Playback was paused: a wait for data no longer holds anything up. */
    fun onPaused() {
        stallStartedAtMs = null
    }

    /** The player ran out of data. Returns a step down if this is the second stall in the window. */
    fun onBuffering(nowMs: Long, playWhenReady: Boolean): AutoChange? {
        if (level == null || pending || !ready || seeking || !playWhenReady) return null
        stallStartedAtMs = nowMs
        cleanPlayMs = 0
        recentStalls.addLast(nowMs)
        while (recentStalls.isNotEmpty() && nowMs - recentStalls.first() > STALL_WINDOW_MS) recentStalls.removeFirst()
        return if (recentStalls.size >= STALLS_TO_STEP_DOWN) propose(stepDown(AutoReason.TWO_STALLS)) else null
    }

    /**
     * Call about once a second.
     *
     * @param playing frames are moving right now.
     * @param estimateBps current measured throughput, or null if unknown.
     */
    fun onTick(nowMs: Long, playing: Boolean, estimateBps: Long?): AutoChange? {
        val sinceLastTick = lastTickMs?.let { (nowMs - it).coerceIn(0, MAX_TICK_MS) } ?: 0
        lastTickMs = nowMs
        val current = level ?: return null
        if (pending) return null

        val stallStartedAt = stallStartedAtMs
        if (stallStartedAt != null) {
            return if (nowMs - stallStartedAt > LONG_STALL_MS) propose(stepDown(AutoReason.LONG_STALL)) else null
        }
        if (!playing) return null

        cleanPlayMs += sinceLastTick
        if (cleanPlayMs < STEP_UP_AFTER_MS) return null
        val target = AutoQuality.higher(current, screen, upperBitrate) ?: return null
        val hasRoom = estimateBps == null || AutoQuality.usableShare(estimateBps) >= target.bitrate
        return if (hasRoom) propose(AutoChange(current, target, AutoReason.CLEAN_RUN)) else null
    }

    /** The proposed change is now playing. */
    fun applied(change: AutoChange, nowMs: Long) {
        pending = false
        val to = change.to ?: return
        if (change.isStepDown) {
            lastStepDownAtMs = nowMs
            // Stalled again after stepping up: stay at or below this level for the rest of the item.
            if (steppedUp) upperBitrate = to.bitrate
            steppedUp = false
        } else {
            steppedUp = true
        }
        level = to
        resetStalls()
    }

    /** The proposed change could not be made (server unreachable, or playback moved on). Try again on fresh evidence. */
    fun failed(nowMs: Long) {
        pending = false
        recentStalls.clear()
        cleanPlayMs = 0
        if (stallStartedAtMs != null) stallStartedAtMs = nowMs
    }

    private fun stepDown(reason: AutoReason): AutoChange {
        val current = checkNotNull(level)
        return AutoChange(current, AutoQuality.lower(current, screen, directSourceBitrate), reason)
    }

    private fun propose(change: AutoChange): AutoChange {
        if (change.to == null) {
            // Nothing lower to try: stop timing this stall rather than report the same thing every second.
            stallStartedAtMs = null
        } else {
            pending = true
        }
        return change
    }

    private fun resetStalls() {
        recentStalls.clear()
        stallStartedAtMs = null
        cleanPlayMs = 0
    }

    companion object {
        const val STALL_WINDOW_MS = 60_000L
        const val STALLS_TO_STEP_DOWN = 2
        const val LONG_STALL_MS = 5_000L
        const val STEP_UP_AFTER_MS = 600_000L

        /** A gap between ticks longer than this (the app was frozen) is not counted as clean playing time. */
        private const val MAX_TICK_MS = 2_000L
    }
}

/**
 * The limits one stream is requested with.
 *
 * @property maxBitrate total bitrate cap in bits per second (`MaxStreamingBitrate`).
 * @property directPlayMaxWidth files wider than this are not played directly; null = any width.
 * @property transcodeSize the largest picture the server may encode; null = no limit beyond the above.
 */
data class StreamLimits(
    val maxBitrate: Int,
    val directPlayMaxWidth: Int?,
    val transcodeSize: PictureSize?,
) {
    companion object {
        /** A tier's own limits with no car screen involved: downloads, and requests built without a car. */
        fun forTier(tier: QualityTier): StreamLimits =
            if (tier == QualityTier.AUTO) forCar(tier, screen = null) else StreamLimits(tier.streamingBitrate, tier.maxWidth, null)

        /**
         * Limits for playing on a car screen.
         *
         * - Original: no limits.
         * - Auto: [autoLevel]'s bitrate and picture size; any file within the bitrate plays directly.
         * - Fixed tiers: the tier's bitrate and width as before, and transcodes no larger than the
         *   screen can show (1280×720 if the screen size is unknown).
         */
        fun forCar(tier: QualityTier, screen: ScreenSize?, autoLevel: AutoLevel? = null): StreamLimits {
            val tierWidth = tier.maxWidth
            return when {
                tier == QualityTier.AUTO -> {
                    val level = autoLevel ?: AutoQuality.startLevel(estimateBps = null, screen)
                    StreamLimits(level.bitrate, null, level.picture)
                }
                tierWidth == null -> StreamLimits(tier.streamingBitrate, null, null)
                else -> StreamLimits(tier.streamingBitrate, tierWidth, minOf(PictureSize.forWidth(tierWidth), PictureSize.forScreen(screen)))
            }
        }
    }
}
