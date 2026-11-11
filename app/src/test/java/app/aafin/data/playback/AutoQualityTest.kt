package app.aafin.data.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Auto quality policy: where it starts, how far the screen lets it go, and when it steps down and back up. */
class AutoQualityTest {

    private val deskUnit = ScreenSize(800, 400)
    private val hdScreen = ScreenSize(1280, 720)
    private val fullHd = ScreenSize(1920, 1080)

    private fun mbps(value: Double): Long = (value * 1_000_000).toLong()

    // --- Screen ceiling ------------------------------------------------------------------

    @Test
    fun `the picture ceiling is the standard size that fills the screen`() {
        assertEquals(PictureSize.P480, PictureSize.forScreen(deskUnit)) // a 16:9 picture is 711x400 there
        assertEquals(PictureSize.P360, PictureSize.forScreen(ScreenSize(640, 360)))
        assertEquals(PictureSize.P480, PictureSize.forScreen(ScreenSize(800, 480)))
        assertEquals(PictureSize.P720, PictureSize.forScreen(ScreenSize(1024, 600)))
        assertEquals(PictureSize.P720, PictureSize.forScreen(hdScreen))
        assertEquals(PictureSize.P720, PictureSize.forScreen(ScreenSize(1280, 800)))
        assertEquals(PictureSize.P720, PictureSize.forScreen(ScreenSize(1920, 720))) // wide but only 720 tall
        assertEquals(PictureSize.P1080, PictureSize.forScreen(fullHd))
        assertEquals(PictureSize.P1080, PictureSize.forScreen(ScreenSize(2560, 1440)))
        assertEquals(PictureSize.P480, PictureSize.forScreen(ScreenSize(768, 1024))) // portrait: width decides
    }

    @Test
    fun `an unknown screen is treated as 1280 by 720`() {
        assertEquals(PictureSize.P720, PictureSize.forScreen(null))
        assertEquals(PictureSize.P720, PictureSize.forScreen(ScreenSize(0, 0)))
        assertEquals(1280 to 720, PictureSize.UNKNOWN_SCREEN.width to PictureSize.UNKNOWN_SCREEN.height)
    }

    @Test
    fun `tier widths map to their standard sizes`() {
        assertEquals(PictureSize.P480, PictureSize.forWidth(854))
        assertEquals(PictureSize.P720, PictureSize.forWidth(1280))
        assertEquals(PictureSize.P1080, PictureSize.forWidth(1920))
        assertEquals(854 to 480, PictureSize.P480.width to PictureSize.P480.height)
    }

    // --- Start level ---------------------------------------------------------------------

    @Test
    fun `the start cap is 70 percent of the estimate`() {
        assertEquals(AutoLevel(3_500_000, PictureSize.P720), AutoQuality.startLevel(mbps(5.0), fullHd))
        assertEquals(AutoLevel(2_100_000, PictureSize.P480), AutoQuality.startLevel(mbps(3.0), fullHd))
        assertEquals(AutoLevel(7_000_000, PictureSize.P1080), AutoQuality.startLevel(mbps(10.0), fullHd))
    }

    @Test
    fun `the start cap is kept between 1_5 and 8 Mbps`() {
        assertEquals(1_500_000, AutoQuality.startLevel(mbps(0.4), fullHd).bitrate)
        assertEquals(1_500_000, AutoQuality.startLevel(mbps(2.0), fullHd).bitrate)
        assertEquals(8_000_000, AutoQuality.startLevel(mbps(300.0), fullHd).bitrate)
        assertEquals(PictureSize.P1080, AutoQuality.startLevel(mbps(300.0), fullHd).picture)
    }

    @Test
    fun `the start cap never exceeds what the screen can use`() {
        // Fast Wi-Fi, small screen: 480p needs no more than 2.5 Mbps.
        assertEquals(AutoLevel(2_500_000, PictureSize.P480), AutoQuality.startLevel(mbps(300.0), deskUnit))
        assertEquals(AutoLevel(4_000_000, PictureSize.P720), AutoQuality.startLevel(mbps(300.0), hdScreen))
        assertEquals(AutoLevel(4_000_000, PictureSize.P720), AutoQuality.startLevel(mbps(300.0), null))
        assertEquals(2_500_000, AutoQuality.ceilingBitrate(PictureSize.P480))
        assertEquals(4_000_000, AutoQuality.ceilingBitrate(PictureSize.P720))
        assertEquals(8_000_000, AutoQuality.ceilingBitrate(PictureSize.P1080))
    }

    @Test
    fun `with no estimate the start cap is 3 Mbps, or the screen's ceiling if that is lower`() {
        assertEquals(AutoLevel(3_000_000, PictureSize.P480), AutoQuality.startLevel(null, hdScreen))
        assertEquals(AutoLevel(3_000_000, PictureSize.P480), AutoQuality.startLevel(null, null))
        assertEquals(AutoLevel(2_500_000, PictureSize.P480), AutoQuality.startLevel(null, deskUnit))
    }

    @Test
    fun `the picture size follows the bitrate but never exceeds the screen`() {
        assertEquals(PictureSize.P360, AutoQuality.level(1_000_000, fullHd).picture)
        assertEquals(PictureSize.P480, AutoQuality.level(1_500_000, fullHd).picture)
        assertEquals(PictureSize.P480, AutoQuality.level(2_500_000, fullHd).picture)
        assertEquals(PictureSize.P720, AutoQuality.level(4_000_000, fullHd).picture)
        assertEquals(PictureSize.P720, AutoQuality.level(6_000_000, fullHd).picture)
        assertEquals(PictureSize.P1080, AutoQuality.level(8_000_000, fullHd).picture)
        assertEquals(PictureSize.P480, AutoQuality.level(8_000_000, deskUnit).picture)
    }

    // --- Ladder --------------------------------------------------------------------------

    @Test
    fun `stepping down walks the ladder to a floor of 1 Mbps at 360p`() {
        var level: AutoLevel? = AutoLevel(2_500_000, PictureSize.P480)
        val visited = mutableListOf<AutoLevel>()
        while (true) {
            level = AutoQuality.lower(level!!, deskUnit) ?: break
            visited += level
        }
        assertEquals(listOf(AutoLevel(1_500_000, PictureSize.P480), AutoLevel(1_000_000, PictureSize.P360)), visited)
        assertEquals(1_000_000, AutoQuality.LADDER.first())
    }

    @Test
    fun `a start cap between two steps drops to the step below`() {
        assertEquals(2_500_000, AutoQuality.lower(AutoLevel(3_000_000, PictureSize.P480), hdScreen)?.bitrate)
        assertEquals(4_000_000, AutoQuality.lower(AutoLevel(4_300_000, PictureSize.P720), fullHd)?.bitrate)
    }

    @Test
    fun `a direct-played file steps down to below its own bitrate, so the server has to transcode`() {
        val level = AutoLevel(4_000_000, PictureSize.P720)
        // A 2 Mbps file fits under 2.5 Mbps too, so that step would change nothing.
        assertEquals(AutoLevel(1_500_000, PictureSize.P480), AutoQuality.lower(level, hdScreen, belowBitrate = 2_000_000))
        assertEquals(AutoLevel(2_500_000, PictureSize.P480), AutoQuality.lower(level, hdScreen, belowBitrate = 3_900_000))
        // A file already smaller than the floor cannot be helped by transcoding.
        assertNull(AutoQuality.lower(level, hdScreen, belowBitrate = 900_000))
    }

    @Test
    fun `stepping up goes one step at a time and stops at the limit`() {
        val low = AutoLevel(1_000_000, PictureSize.P360)
        assertEquals(AutoLevel(1_500_000, PictureSize.P480), AutoQuality.higher(low, hdScreen, upTo = 4_000_000))
        assertEquals(AutoLevel(4_000_000, PictureSize.P720), AutoQuality.higher(AutoLevel(2_500_000, PictureSize.P480), hdScreen, upTo = 4_000_000))
        // The limit can sit between two steps (a start cap of 3 Mbps).
        assertEquals(3_000_000, AutoQuality.higher(AutoLevel(2_500_000, PictureSize.P480), hdScreen, upTo = 3_000_000)?.bitrate)
        assertNull(AutoQuality.higher(AutoLevel(4_000_000, PictureSize.P720), hdScreen, upTo = 4_000_000))
        assertNull(AutoQuality.higher(AutoLevel(8_000_000, PictureSize.P1080), fullHd, upTo = 8_000_000))
    }

    // --- Text ----------------------------------------------------------------------------

    @Test
    fun `levels are described for the Options screen`() {
        assertEquals("480p · 2.5 Mbps", AutoLevel(2_500_000, PictureSize.P480).label)
        assertEquals("720p · 4 Mbps", AutoLevel(4_000_000, PictureSize.P720).label)
        assertEquals("360p · 1 Mbps", AutoLevel(1_000_000, PictureSize.P360).label)
        assertEquals("480p · 2.5 Mbps", AutoQuality.describe(AutoLevel(2_500_000, PictureSize.P480), PlayMethod.TRANSCODE))
        assertEquals("Original picture · up to 2.5 Mbps", AutoQuality.describe(AutoLevel(2_500_000, PictureSize.P480), PlayMethod.DIRECT_PLAY))
        assertEquals("Original picture · up to 4 Mbps", AutoQuality.describe(AutoLevel(4_000_000, PictureSize.P720), PlayMethod.DIRECT_STREAM))
        assertEquals("6.2 Mbps", AutoQuality.mbps(6_234_000))
    }

    // --- Stalls --------------------------------------------------------------------------

    /** A tracker that has started an item and whose stream has begun playing. */
    private fun playing(estimate: Long? = mbps(300.0), screen: ScreenSize? = hdScreen, atMs: Long = 0): AutoQualityTracker =
        AutoQualityTracker().apply {
            start(atMs, estimate, screen)
            onStreamLoading(directSourceBitrate = null)
            onReady()
        }

    /** Applies a proposed change the way the controller does: new stream, then ready. */
    private fun AutoQualityTracker.switchTo(change: AutoChange, atMs: Long) {
        onStreamLoading(directSourceBitrate = null)
        applied(change, atMs)
        onReady()
    }

    /** Ticks once a second from [fromMs] (exclusive) to [toMs] while playing; returns the first proposal. */
    private fun AutoQualityTracker.playUntil(fromMs: Long, toMs: Long, estimate: Long? = null): Pair<Long, AutoChange>? {
        var now = fromMs
        while (now < toMs) {
            now += 1_000
            onTick(now, playing = true, estimateBps = estimate)?.let { return now to it }
        }
        return null
    }

    @Test
    fun `one short stall changes nothing`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = true))
        assertTrue(tracker.isStalled)
        assertNull(tracker.onTick(11_000, playing = false, estimateBps = null))
        assertNull(tracker.onTick(14_900, playing = false, estimateBps = null))
        tracker.onReady()
        assertFalse(tracker.isStalled)
        assertNull(tracker.onTick(20_000, playing = true, estimateBps = null))
        assertEquals(4_000_000, tracker.level?.bitrate)
    }

    @Test
    fun `two stalls within 60 seconds step down`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = true))
        tracker.onReady()

        val change = tracker.onBuffering(65_000, playWhenReady = true)

        assertEquals(AutoChange(AutoLevel(4_000_000, PictureSize.P720), AutoLevel(2_500_000, PictureSize.P480), AutoReason.TWO_STALLS), change)
        assertTrue(change!!.isStepDown)
    }

    @Test
    fun `two stalls more than 60 seconds apart do not step down`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = true))
        tracker.onReady()
        assertNull(tracker.onBuffering(70_001, playWhenReady = true))
        tracker.onReady()
        // ...but the next one inside the window of the second does.
        assertNotNull(tracker.onBuffering(100_000, playWhenReady = true))
    }

    @Test
    fun `one stall longer than 5 seconds steps down`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = true))
        assertNull(tracker.onTick(15_000, playing = false, estimateBps = null)) // exactly 5 s: not yet

        val change = tracker.onTick(15_500, playing = false, estimateBps = null)

        assertEquals(AutoReason.LONG_STALL, change?.reason)
        assertEquals(2_500_000, change?.to?.bitrate)
    }

    @Test
    fun `waiting for the first frames of a stream is not a stall`() {
        val tracker = AutoQualityTracker()
        tracker.start(0, mbps(300.0), hdScreen)
        tracker.onStreamLoading(directSourceBitrate = null)

        assertNull(tracker.onBuffering(100, playWhenReady = true))
        assertFalse(tracker.isStalled)
        assertNull("a slow start is not a long stall either", tracker.onTick(20_000, playing = false, estimateBps = null))
        assertEquals(0, tracker.stallCount)
    }

    @Test
    fun `waiting after a seek is not a stall, however long or often`() {
        val tracker = playing()
        repeat(3) { i ->
            tracker.onSeek()
            assertNull(tracker.onBuffering(10_000L + i * 1_000, playWhenReady = true))
            assertNull(tracker.onTick(30_000L + i * 1_000, playing = false, estimateBps = null))
            tracker.onReady()
        }
        assertEquals(0, tracker.stallCount)

        // Once playing again, real stalls count as usual.
        assertNull(tracker.onBuffering(40_000, playWhenReady = true))
        tracker.onReady()
        assertNotNull(tracker.onBuffering(45_000, playWhenReady = true))
    }

    @Test
    fun `a seek during a stall takes over the wait`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = true))
        tracker.onSeek()
        assertNull(tracker.onTick(30_000, playing = false, estimateBps = null))
    }

    @Test
    fun `buffering while paused is not a stall, and pausing ends one`() {
        val tracker = playing()
        assertNull(tracker.onBuffering(10_000, playWhenReady = false))
        assertEquals(0, tracker.stallCount)

        tracker.onReady()
        assertNull(tracker.onBuffering(20_000, playWhenReady = true))
        tracker.onPaused()
        assertNull(tracker.onTick(60_000, playing = false, estimateBps = null))
    }

    @Test
    fun `the restart after a step down is not counted as a stall`() {
        val tracker = playing()
        tracker.onBuffering(10_000, playWhenReady = true)
        tracker.onReady()
        val change = tracker.onBuffering(20_000, playWhenReady = true)!!

        // The controller loads the new stream: the player buffers again while it starts.
        tracker.onStreamLoading(directSourceBitrate = null)
        assertNull(tracker.onBuffering(21_000, playWhenReady = true))
        assertNull(tracker.onTick(40_000, playing = false, estimateBps = null))
        tracker.applied(change, 22_000)
        tracker.onReady()

        assertEquals(AutoLevel(2_500_000, PictureSize.P480), tracker.level)
        assertEquals(0, tracker.stallCount)
        assertNull("stalls before the change do not count against the new level", tracker.onBuffering(30_000, playWhenReady = true))
    }

    @Test
    fun `nothing more is proposed while a change is being made`() {
        val tracker = playing()
        tracker.onBuffering(10_000, playWhenReady = true)
        assertNotNull(tracker.onTick(15_500, playing = false, estimateBps = null))

        assertNull(tracker.onTick(16_500, playing = false, estimateBps = null))
        assertNull(tracker.onTick(30_000, playing = false, estimateBps = null))
    }

    @Test
    fun `a change that could not be made is tried again after another 5 seconds of stalling`() {
        val tracker = playing()
        tracker.onBuffering(10_000, playWhenReady = true)
        assertNotNull(tracker.onTick(15_500, playing = false, estimateBps = null))

        tracker.failed(17_000) // the server could not be reached
        assertEquals(4_000_000, tracker.level?.bitrate)
        assertNull(tracker.onTick(21_000, playing = false, estimateBps = null))
        assertEquals(2_500_000, tracker.onTick(22_500, playing = false, estimateBps = null)?.to?.bitrate)
    }

    @Test
    fun `stalls keep stepping down to the floor and no further`() {
        val tracker = playing()
        var now = 0L
        val levels = mutableListOf<Int>()
        while (true) {
            now += 10_000
            tracker.onBuffering(now, playWhenReady = true)
            val change = tracker.onTick(now + 5_500, playing = false, estimateBps = null)!!
            val to = change.to ?: break
            levels += to.bitrate
            now += 6_000
            tracker.switchTo(change, now)
        }
        assertEquals(listOf(2_500_000, 1_500_000, 1_000_000), levels)
        assertEquals(AutoLevel(1_000_000, PictureSize.P360), tracker.level)

        // At the floor the long stall is reported once, not every second.
        assertNull(tracker.onTick(now + 7_500, playing = false, estimateBps = null))
        assertNull(tracker.onTick(now + 60_000, playing = false, estimateBps = null))
    }

    @Test
    fun `a direct-played file that stalls goes straight to a cap that makes the server transcode`() {
        val tracker = AutoQualityTracker()
        tracker.start(0, mbps(300.0), hdScreen)
        tracker.onStreamLoading(directSourceBitrate = 1_800_000)
        tracker.onReady()

        tracker.onBuffering(10_000, playWhenReady = true)
        val change = tracker.onTick(15_500, playing = false, estimateBps = null)

        assertEquals(AutoLevel(1_500_000, PictureSize.P480), change?.to)
    }

    // --- Step up -------------------------------------------------------------------------

    /** Starts at 4 Mbps and is knocked down to 2.5 Mbps by a long stall at 10 s; the new stream is ready at 20 s. */
    private fun steppedDown(): AutoQualityTracker = playing().apply {
        onBuffering(10_000, playWhenReady = true)
        switchTo(onTick(15_500, playing = false, estimateBps = null)!!, 20_000)
    }

    @Test
    fun `ten minutes of clean playing allows one step back up`() {
        val tracker = steppedDown()

        val (at, change) = tracker.playUntil(20_000, 2_000_000)!!

        assertEquals(AutoChange(AutoLevel(2_500_000, PictureSize.P480), AutoLevel(4_000_000, PictureSize.P720), AutoReason.CLEAN_RUN), change)
        assertFalse(change.isStepDown)
        // Ten minutes after the new stream started, to within a couple of one-second ticks.
        assertTrue("stepped up at $at", kotlin.math.abs(at - (20_000 + AutoQualityTracker.STEP_UP_AFTER_MS)) <= 2_000)
    }

    @Test
    fun `a stall restarts the ten minutes`() {
        val tracker = steppedDown()
        assertNull(tracker.playUntil(20_000, 500_000))
        assertNull(tracker.onBuffering(500_500, playWhenReady = true))
        tracker.onReady()

        assertNull(tracker.playUntil(501_000, 1_090_000))
        assertNotNull(tracker.playUntil(1_090_000, 1_110_000))
    }

    @Test
    fun `paused time does not count towards the ten minutes`() {
        val tracker = steppedDown()
        var now = 20_000L
        while (now < 2_000_000) {
            now += 1_000
            assertNull(tracker.onTick(now, playing = false, estimateBps = null))
        }
    }

    @Test
    fun `it never steps above where the item started`() {
        val tracker = playing() // started at the screen's ceiling, 4 Mbps
        assertNull(tracker.playUntil(0, 3_000_000))

        val modest = playing(estimate = mbps(3.0)) // started at 2.1 Mbps
        assertNull(modest.playUntil(0, 3_000_000))
    }

    @Test
    fun `it steps up only while the measured throughput has room for the higher level`() {
        val tracker = steppedDown()
        // 4 Mbps needs an estimate of about 5.7 Mbps.
        assertNull(tracker.playUntil(20_000, 900_000, estimate = mbps(5.0)))

        assertEquals(4_000_000, tracker.onTick(901_000, playing = true, estimateBps = mbps(6.0))?.to?.bitrate)
    }

    @Test
    fun `a level that stalls again after stepping up is not tried again for this item`() {
        val tracker = steppedDown()
        val (upAt, up) = tracker.playUntil(20_000, 2_000_000)!!
        tracker.switchTo(up, upAt)
        assertEquals(4_000_000, tracker.level?.bitrate)

        // It stalls at the higher level and comes back down...
        tracker.onBuffering(upAt + 30_000, playWhenReady = true)
        val down = tracker.onTick(upAt + 35_500, playing = false, estimateBps = null)!!
        assertEquals(2_500_000, down.to?.bitrate)
        tracker.switchTo(down, upAt + 40_000)

        // ...and stays there, however long it then plays cleanly.
        assertNull(tracker.playUntil(upAt + 40_000, upAt + 5_000_000))
        assertEquals(2_500_000, tracker.level?.bitrate)
    }

    @Test
    fun `after a failed step up it can still recover from further step downs, up to the barred level`() {
        val tracker = steppedDown()
        val (upAt, up) = tracker.playUntil(20_000, 2_000_000)!!
        tracker.switchTo(up, upAt)
        tracker.onBuffering(upAt + 30_000, playWhenReady = true)
        tracker.switchTo(tracker.onTick(upAt + 35_500, playing = false, estimateBps = null)!!, upAt + 40_000) // back at 2.5
        tracker.onBuffering(upAt + 50_000, playWhenReady = true)
        tracker.switchTo(tracker.onTick(upAt + 55_500, playing = false, estimateBps = null)!!, upAt + 60_000) // down to 1.5
        assertEquals(1_500_000, tracker.level?.bitrate)

        val (_, again) = tracker.playUntil(upAt + 60_000, upAt + 2_000_000)!!
        assertEquals(2_500_000, again.to?.bitrate)
    }

    // --- From one item to the next -------------------------------------------------------

    @Test
    fun `a recent step down carries over to the next item`() {
        val tracker = steppedDown() // stepped down at 20 s

        val next = tracker.start(300_000, mbps(300.0), hdScreen)

        assertEquals(AutoLevel(2_500_000, PictureSize.P480), next)
        // The new item may still climb back to where it would have started.
        tracker.onStreamLoading(directSourceBitrate = null)
        tracker.onReady()
        assertEquals(4_000_000, tracker.playUntil(300_000, 2_000_000)?.second?.to?.bitrate)
    }

    @Test
    fun `a step down older than ten minutes is forgotten`() {
        val tracker = steppedDown()

        assertEquals(4_000_000, tracker.start(20_000 + AutoQualityTracker.STEP_UP_AFTER_MS, mbps(300.0), hdScreen).bitrate)
    }

    @Test
    fun `a new item never starts above its own estimate because of an earlier one`() {
        val tracker = steppedDown() // at 2.5 Mbps

        assertEquals(1_500_000, tracker.start(100_000, mbps(1.0), hdScreen).bitrate)
    }

    @Test
    fun `starting a new item clears stalls counted against the last one`() {
        val tracker = playing()
        tracker.onBuffering(10_000, playWhenReady = true)
        tracker.onReady()

        tracker.start(20_000, mbps(300.0), hdScreen)
        tracker.onStreamLoading(directSourceBitrate = null)
        tracker.onReady()

        assertNull(tracker.onBuffering(30_000, playWhenReady = true))
    }

    @Test
    fun `nothing is decided before Auto has been started`() {
        val tracker = AutoQualityTracker()
        tracker.onReady()
        assertNull(tracker.onBuffering(1_000, playWhenReady = true))
        assertNull(tracker.onTick(10_000, playing = false, estimateBps = null))
        assertNull(tracker.level)
    }
}
