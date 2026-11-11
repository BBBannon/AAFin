package app.aafin

import android.content.Context
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.aafin.data.accounts.Account
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.playback.DeviceProfileBuilder
import app.aafin.data.playback.PlaybackState
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.QueuedPosition
import app.aafin.data.playback.WorkManagerPositionQueue
import app.aafin.data.profiles.CarProfile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device smoke tests: every singleton in [AppGraph] can be created and used on a real
 * Android runtime (DataStore files, Media3 player/session/cache/download manager, WorkManager,
 * the Jellyfin SDK's HTTP stack). No Jellyfin server is needed.
 *
 * Run with a phone or emulator attached: `gradlew.bat connectedDebugAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class GraphSmokeTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val graph: AppGraph = AAFinApp.graph(context)

    private fun <T> onMain(block: () -> T): T {
        var result: Result<T>? = null
        InstrumentationRegistry.getInstrumentation().runOnMainSync { result = runCatching(block) }
        return result!!.getOrThrow()
    }

    @Test
    fun accountStorePersistsOnAndroid() = runBlocking {
        val store = graph.accountStore
        val deviceId = store.deviceId()
        assertTrue(deviceId.isNotBlank())
        assertEquals(deviceId, store.deviceId())

        // Not made active (unless it is the only account), so a real sign-in on this device is left alone.
        val account = Account("smoke:1", "https://example.invalid", "Smoke", "user", "Smoke Test", "token")
        store.upsert(account, makeActive = false)
        assertEquals(account, store.get(account.id))
        store.remove(account.id)
        assertNull(store.get(account.id))
    }

    @Test
    fun carProfilesSurviveRepeatedWrites() = runBlocking {
        val store = graph.carProfiles
        val key = "screen:smoke-test"
        store.remove(key)

        val created = store.getOrCreate(key, "Smoke car")
        store.setQuality(key, QualityTier.P1080_8M)
        store.rename(key, "Renamed")

        assertEquals(CarProfile(key, "Smoke car", created.quality), created)
        assertEquals(CarProfile(key, "Renamed", QualityTier.P1080_8M), store.get(key))
        store.remove(key)
    }

    @Test
    fun deviceProfileReflectsThisDevicesDecoders() {
        val profile = DeviceProfileBuilder.build(QualityTier.DEFAULT, graph.codecCapabilities)
        val direct = profile.directPlayProfiles.single()
        Log.i("AAFin.Smoke", "Direct play video=${direct.videoCodec} audio=${direct.audioCodec}")

        assertTrue(direct.videoCodec!!.split(",").contains("h264"))
        assertTrue(direct.audioCodec!!.split(",").contains("aac"))
        assertTrue("every Android device has an AVC decoder", graph.codecCapabilities.hasDecoder("video/avc"))
    }

    @Test
    fun playerAndMediaSessionCanBeCreated() {
        onMain {
            val playback = graph.playback
            assertNotNull(playback.player)
            assertEquals(PlaybackState(), playback.state.value)
            playback.togglePlayPause() // nothing loaded: must be a no-op
            playback.seekBy(10_000)
            playback.stop()
            assertEquals(PlaybackState(), playback.state.value)
        }
    }

    @Test
    fun downloadsStartEmpty() {
        val downloads = onMain { graph.downloads }
        runBlocking {
            assertNull(downloads.find("0f8fad5b-d9cb-469f-a165-70867728950e"))
            assertNull(downloads.offlineMedia("0f8fad5b-d9cb-469f-a165-70867728950e"))
            assertTrue(downloads.storageUsedBytes() >= 0)
        }
        onMain { downloads.resumePending() }
    }

    @Test
    fun positionQueueAcceptsAndCancelsWork() {
        val queue = WorkManagerPositionQueue(context)
        queue.enqueue(QueuedPosition("smoke:1", "0f8fad5b-d9cb-469f-a165-70867728950e", "source", 1_000))
        queue.cancel("smoke:1", "0f8fad5b-d9cb-469f-a165-70867728950e")
    }

    @Test
    fun unreachableServerIsReportedAsNetworkError() = runBlocking {
        // Nothing listens on this port of the loopback address, so the connection is refused at once.
        val error = runCatching { graph.auth.findServer("http://127.0.0.1:9") }.exceptionOrNull()
        assertTrue("expected JellyfinException but was $error", error is JellyfinException)
        assertEquals(JellyfinException.Kind.NETWORK, (error as JellyfinException).kind)
    }

    @Test
    fun signedOutBrowsingFailsCleanly() = runBlocking {
        // Only meaningful (and only run) when nobody is signed in on this device.
        assumeTrue(graph.accountStore.active() == null)
        val error = runCatching { graph.jellyfin.libraries() }.exceptionOrNull()
        assertEquals(JellyfinException.Kind.NOT_SIGNED_IN, (error as? JellyfinException)?.kind)
        assertNull(graph.jellyfin.imageUrl("abc", maxWidth = 200))
    }
}
