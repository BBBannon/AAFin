package app.aafin.data.playback

import app.aafin.data.AppJson
import app.aafin.data.jellyfin.ImageKind
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.JellyfinUrls
import kotlinx.coroutines.test.runTest
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressReporterTest {

    private class FakeGateway : PlaystateGateway {
        var online = true
        val calls = mutableListOf<String>()

        private fun record(call: String) {
            if (!online) throw JellyfinException(JellyfinException.Kind.NETWORK)
            calls += call
        }

        override suspend fun reportStart(snapshot: PlaybackSnapshot) = record("start@${snapshot.positionMs}")
        override suspend fun reportProgress(snapshot: PlaybackSnapshot) = record("progress@${snapshot.positionMs}")
        override suspend fun reportStopped(snapshot: PlaybackSnapshot) = record("stopped@${snapshot.positionMs}")
        override suspend fun reportPosition(position: QueuedPosition) = record("position@${position.positionMs}")
    }

    private class FakeQueue : PositionQueue {
        val queued = mutableMapOf<Pair<String, String>, QueuedPosition>()
        var cancels = 0

        override fun enqueue(position: QueuedPosition) {
            queued[position.accountId to position.itemId] = position
        }

        override fun cancel(accountId: String, itemId: String) {
            cancels++
            queued.remove(accountId to itemId)
        }
    }

    private val gateway = FakeGateway()
    private val queue = FakeQueue()
    private val reporter = ProgressReporter(gateway, queue)

    private fun snapshot(positionMs: Long, method: PlayMethod = PlayMethod.TRANSCODE) = PlaybackSnapshot(
        accountId = "acc",
        itemId = "item",
        mediaSourceId = "source",
        playSessionId = "session",
        playMethod = method,
        positionMs = positionMs,
        isPaused = false,
    )

    @Test
    fun `online reports go straight to the server and nothing is queued`() = runTest {
        assertTrue(reporter.started(snapshot(0)))
        assertTrue(reporter.progress(snapshot(10_000)))
        assertTrue(reporter.stopped(snapshot(20_000)))

        assertEquals(listOf("start@0", "progress@10000", "stopped@20000"), gateway.calls)
        assertTrue(queue.queued.isEmpty())
        assertEquals(0, queue.cancels)
    }

    @Test
    fun `failed progress and stop reports are queued, latest position wins`() = runTest {
        gateway.online = false

        assertFalse(reporter.started(snapshot(0)))
        assertTrue("a failed start carries no position worth queueing", queue.queued.isEmpty())

        assertFalse(reporter.progress(snapshot(10_000)))
        assertFalse(reporter.stopped(snapshot(25_000)))

        assertEquals(QueuedPosition("acc", "item", "source", 25_000), queue.queued.values.single())
    }

    @Test
    fun `a later successful report cancels the stale queued position`() = runTest {
        gateway.online = false
        reporter.progress(snapshot(10_000))
        assertEquals(1, queue.queued.size)

        gateway.online = true
        assertTrue(reporter.progress(snapshot(20_000)))

        assertTrue(queue.queued.isEmpty())
        assertEquals(1, queue.cancels)

        reporter.progress(snapshot(30_000))
        assertEquals("nothing left to cancel", 1, queue.cancels)
    }

    @Test
    fun `offline playback never calls the server and queues its position on stop`() = runTest {
        assertFalse(reporter.started(snapshot(0, PlayMethod.OFFLINE)))
        assertFalse(reporter.progress(snapshot(10_000, PlayMethod.OFFLINE)))
        assertTrue(queue.queued.isEmpty())

        assertFalse(reporter.stopped(snapshot(42_000, PlayMethod.OFFLINE)))

        assertTrue(gateway.calls.isEmpty())
        assertEquals(42_000L, queue.queued.values.single().positionMs)
    }

    @Test
    fun `queued positions are replayed through the gateway and survive json`() = runTest {
        val position = QueuedPosition("acc", "item", "source", 42_000)
        val decoded = AppJson.decodeFromString(QueuedPosition.serializer(), AppJson.encodeToString(QueuedPosition.serializer(), position))
        assertEquals(position, decoded)

        reporter.sendQueued(decoded)
        assertEquals(listOf("position@42000"), gateway.calls)
    }

    // --- URLs and the auth header rule -------------------------------------------------------

    @Test
    fun `urls are built against the server base path`() {
        val base = "https://example.com/jellyfin"
        assertEquals(
            "https://example.com/jellyfin/Items/abc/Images/Primary?maxWidth=256&quality=90&tag=t1",
            JellyfinUrls.image(base, "abc", ImageKind.PRIMARY, maxWidth = 256, tag = "t1"),
        )
        assertEquals(
            "https://example.com/jellyfin/Items/abc/Images/Backdrop?maxWidth=800&quality=90",
            JellyfinUrls.image(base, "abc", ImageKind.BACKDROP, maxWidth = 800),
        )
        assertEquals("https://example.com/jellyfin/Items/abc/Download", JellyfinUrls.download(base, "abc"))
        assertEquals("https://example.com/jellyfin/videos/abc/master.m3u8?x=1", JellyfinUrls.resolve(base, "/videos/abc/master.m3u8?x=1"))
        assertEquals("https://example.com/jellyfin/videos/abc", JellyfinUrls.resolve("$base/", "videos/abc"))
        assertEquals("https://other.example.org/a.srt", JellyfinUrls.resolve(base, "https://other.example.org/a.srt"))
    }

    @Test
    fun `auth header is only for requests to the account's own server`() {
        val server = "https://example.com/jellyfin"
        fun onServer(url: String) = AuthHeaderInterceptor.isOnServer(url.toHttpUrl(), server)

        assertTrue(onServer("https://example.com/jellyfin/Videos/abc/stream?static=true"))
        assertTrue(onServer("https://EXAMPLE.com:443/jellyfin/Items/abc/Download"))
        assertFalse("different host", onServer("https://evil.example.org/jellyfin/Videos/abc/stream"))
        assertFalse("different scheme", onServer("http://example.com/jellyfin/Videos/abc/stream"))
        assertFalse("different port", onServer("https://example.com:8920/jellyfin/Videos/abc/stream"))
        assertFalse("outside the base path", onServer("https://example.com/other/Videos/abc/stream"))
        assertFalse("base path is a prefix of another folder", onServer("https://example.com/jellyfin2/Videos/abc"))
        assertTrue(AuthHeaderInterceptor.isOnServer("https://example.com/Videos/abc".toHttpUrl(), "https://example.com"))
    }
}
