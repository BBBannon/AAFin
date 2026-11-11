package app.aafin.car

import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.ScreenSize
import app.aafin.data.profiles.CarProfileStore
import app.aafin.data.profiles.CarProfilesState
import app.aafin.testing.InMemoryDataStore
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Car detection: model first, screen second, and a timeout so a silent car never blocks playback. */
@OptIn(ExperimentalCoroutinesApi::class)
class CarProfileSessionTest {

    private val store = CarProfileStore(InMemoryDataStore(CarProfilesState()))

    @Test
    fun `a car that reports its model gets a profile named after it`() = runTest {
        val session = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })

        assertEquals(QualityTier.AUTO, session.tier())

        val profile = store.profiles.first().single()
        assertEquals("model:dacia|duster|2025", profile.key)
        assertEquals("Dacia Duster", profile.name)
        assertNotNull("connecting records when the car was last used", profile.lastUsedAtMs)
    }

    @Test
    fun `a car that never answers is recognised by its screen after the timeout`() = runTest {
        val session = CarProfileSession(backgroundScope, store, fetchModel = { awaitCancellation() })
        session.onSurface(SurfaceSpec(1920, 720, 200))
        session.onSurface(SurfaceSpec(800, 480, 160)) // later surfaces do not change the answer

        advanceTimeBy(CarProfileSession.MODEL_TIMEOUT_MS + 1)
        assertEquals(QualityTier.AUTO, session.tier())

        assertEquals("screen:1920x720@200", store.profiles.first().single().key)
    }

    @Test
    fun `with neither a model nor a surface there is still a profile`() = runTest {
        val session = CarProfileSession(backgroundScope, store, fetchModel = { null })

        advanceUntilIdle()
        assertEquals(QualityTier.AUTO, session.tier())

        assertEquals(CarIdentity.UNKNOWN_KEY, store.profiles.first().single().key)
    }

    @Test
    fun `the same car gets the same profile back with its saved quality`() = runTest {
        val first = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })
        first.setTier(QualityTier.P1080_8M)

        // Next journey: wireless this time - nothing about the connection is part of the key.
        val second = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })

        assertEquals(QualityTier.P1080_8M, second.tier())
        assertEquals(1, store.profiles.first().size)
    }

    @Test
    fun `a quality change made on the phone applies to the next thing played`() = runTest {
        val session = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })
        assertEquals(QualityTier.AUTO, session.tier())

        store.setQuality("model:dacia|duster|2025", QualityTier.P480_2M)

        assertEquals(QualityTier.P480_2M, session.tier())
    }

    @Test
    fun `the car's screen size is the latest surface and is saved on its profile`() = runTest {
        val session = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })
        assertNull("nothing known before any surface", session.screen())

        session.onSurface(SurfaceSpec(800, 400, 160))
        session.onSurface(SurfaceSpec(1280, 720, 160)) // e.g. the head unit changed its layout
        session.tier() // the car has been identified...
        runCurrent() // ...and the saving, which runs in the session's own scope, has finished

        assertEquals(ScreenSize(1280, 720), session.screen())
        assertEquals(ScreenSize(1280, 720), store.get("model:dacia|duster|2025")?.screen)
    }

    @Test
    fun `before a surface arrives the screen size saved last time is used`() = runTest {
        val first = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })
        first.onSurface(SurfaceSpec(800, 400, 160))
        first.tier()
        runCurrent()

        val nextJourney = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Dacia", "Duster", 2025) })

        assertEquals(ScreenSize(800, 400), nextJourney.screen())
    }

    @Test
    fun `a new car starts with the default quality from settings`() = runTest {
        store.setDefaultQuality(QualityTier.ORIGINAL)
        val session = CarProfileSession(backgroundScope, store, fetchModel = { CarModel("Skoda", "Octavia", null) })

        assertEquals(QualityTier.ORIGINAL, session.tier())
    }
}
