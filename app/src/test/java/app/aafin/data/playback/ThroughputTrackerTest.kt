package app.aafin.data.playback

import app.aafin.data.AppJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Which of the bandwidth meter's reports count as measurements, and what is kept for a cold start. */
class ThroughputTrackerTest {

    private val wifi = 2
    private val mobile5g = 9

    /** A tracker that has already seen which network it is on (the first report only establishes that). */
    private fun onNetwork(type: Int) = ThroughputTracker().apply { onSample(0, 0, 9_000_000, type, nowMs = 0) }

    @Test
    fun `nothing is known until enough data has been measured`() {
        val tracker = onNetwork(wifi)
        assertNull(tracker.estimate(wifi))

        // 1 s and 300 kB: below both of the meter's own thresholds (2 s or 512 kB).
        assertNull(tracker.onSample(1_000, 300_000, 9_000_000, wifi, nowMs = 1_000))
        assertNull(tracker.estimate(wifi))

        val saved = tracker.onSample(1_200, 300_000, 6_000_000, wifi, nowMs = 2_200)
        assertEquals(SavedThroughput(6_000_000, wifi), saved)
        assertEquals(6_000_000L, tracker.estimate(wifi))
    }

    @Test
    fun `the meter's built-in guess after a change of network is not a measurement`() {
        val tracker = onNetwork(wifi)
        tracker.onSample(3_000, 4_000_000, 40_000_000, wifi, nowMs = 3_000)
        assertEquals(40_000_000L, tracker.estimate(wifi))

        // The phone leaves Wi-Fi: the meter reports its guess for 5G, bytes still in flight included.
        assertNull(tracker.onSample(900, 2_000_000, 12_000_000, mobile5g, nowMs = 4_000))
        assertNull("a Wi-Fi measurement says nothing about mobile data", tracker.estimate(mobile5g))

        tracker.onSample(2_500, 900_000, 3_100_000, mobile5g, nowMs = 40_000)
        assertEquals(3_100_000L, tracker.estimate(mobile5g))
        assertNull(tracker.estimate(wifi))
    }

    @Test
    fun `empty reports are ignored`() {
        val tracker = onNetwork(wifi)
        assertNull(tracker.onSample(0, 0, 5_000_000, wifi, nowMs = 10))
        assertNull(tracker.onSample(5_000, 0, 5_000_000, wifi, nowMs = 20))
        assertNull(tracker.onSample(5_000, 1_000_000, 0, wifi, nowMs = 30))
        assertNull(tracker.estimate(wifi))
    }

    @Test
    fun `the value is written to disk at most every 30 seconds but the estimate is always the newest`() {
        val tracker = onNetwork(mobile5g)
        assertEquals(SavedThroughput(5_000_000, mobile5g), tracker.onSample(3_000, 1_000_000, 5_000_000, mobile5g, nowMs = 3_000))
        assertNull(tracker.onSample(3_000, 1_000_000, 4_000_000, mobile5g, nowMs = 20_000))
        assertEquals(4_000_000L, tracker.estimate(mobile5g))
        assertEquals(SavedThroughput(3_500_000, mobile5g), tracker.onSample(3_000, 1_000_000, 3_500_000, mobile5g, nowMs = 33_000))
    }

    @Test
    fun `after a cold start the saved value is used on the same kind of network only`() {
        val tracker = ThroughputTracker()
        assertNull(tracker.estimate(mobile5g))

        tracker.restore(SavedThroughput(4_200_000, mobile5g))
        assertEquals(4_200_000L, tracker.estimate(mobile5g))
        assertNull(tracker.estimate(wifi))

        // A fresh measurement replaces it.
        tracker.onSample(0, 0, 1, mobile5g, nowMs = 0)
        tracker.onSample(4_000, 800_000, 1_600_000, mobile5g, nowMs = 4_000)
        assertEquals(1_600_000L, tracker.estimate(mobile5g))
    }

    @Test
    fun `an empty saved file means nothing is known`() {
        val tracker = ThroughputTracker()
        tracker.restore(SavedThroughput())
        assertNull(tracker.estimate(0))
    }

    @Test
    fun `the saved value round-trips as JSON`() {
        val json = AppJson.encodeToString(SavedThroughput.serializer(), SavedThroughput(4_200_000, mobile5g))
        assertEquals("""{"bitrate":4200000,"networkType":9}""", json)
        assertEquals(SavedThroughput(4_200_000, mobile5g), AppJson.decodeFromString(SavedThroughput.serializer(), json))
        assertEquals(SavedThroughput(), AppJson.decodeFromString(SavedThroughput.serializer(), "{}"))
    }
}
