package app.aafin.car

import android.util.Log
import androidx.car.app.CarContext
import androidx.car.app.hardware.CarHardwareManager
import androidx.car.app.hardware.common.CarValue
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import app.aafin.data.playback.QualityTier
import app.aafin.data.playback.ScreenSize
import app.aafin.data.profiles.CarProfileStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import kotlin.coroutines.resume

/**
 * Works out which car this is and keeps its profile.
 *
 * Order of preference: the make/model/year the head unit reports; else the size of its screen;
 * else a single "unknown" profile. The answer is worked out once per connection and is the same
 * whether Android Auto is wired or wireless.
 *
 * @param fetchModel asks the car for its model; returns null if it will not say. May never return.
 */
class CarProfileSession(
    private val scope: CoroutineScope,
    private val store: CarProfileStore,
    fetchModel: suspend () -> CarModel?,
    modelTimeoutMs: Long = MODEL_TIMEOUT_MS,
    surfaceTimeoutMs: Long = SURFACE_TIMEOUT_MS,
) : CarQuality {
    private val firstSurface = CompletableDeferred<SurfaceSpec>()
    private var latestSurface: SurfaceSpec? = null

    private val identity: Deferred<CarIdentity> = scope.async {
        val model = withTimeoutOrNull(modelTimeoutMs) { fetchModel() }
        val fromModel = CarIdentity.of(model, surface = null)
        val identity = if (fromModel.key != CarIdentity.UNKNOWN_KEY) {
            fromModel
        } else {
            CarIdentity.of(model = null, surface = withTimeoutOrNull(surfaceTimeoutMs) { firstSurface.await() })
        }
        val profile = store.getOrCreate(identity.key, identity.suggestedName)
        store.markUsed(identity.key)
        Log.i(CarLog.CAR, "Car profile: key=${profile.key} name=${profile.name} quality=${profile.quality}")
        identity
    }

    /**
     * The renderer reports every surface it is given. The first one identifies a car that will not
     * name itself; the latest one is the screen size, which is also saved on the car's profile.
     */
    fun onSurface(spec: SurfaceSpec) {
        firstSurface.complete(spec)
        if (spec == latestSurface) return
        latestSurface = spec
        scope.launch {
            try {
                store.setScreen(identity.await().key, spec.width, spec.height)
            } catch (e: IOException) {
                // Only the copy for next time is lost; this connection still knows its screen.
                Log.w(CarLog.CAR, "The car's screen size could not be saved: ${e.javaClass.simpleName}")
            }
        }
    }

    /** Read afresh every time, so a change made on the phone applies to the next thing played. */
    override suspend fun tier(): QualityTier {
        val identity = identity.await()
        return store.getOrCreate(identity.key, identity.suggestedName).quality
    }

    override suspend fun setTier(tier: QualityTier) {
        val identity = identity.await()
        store.getOrCreate(identity.key, identity.suggestedName)
        store.setQuality(identity.key, tier)
        Log.i(CarLog.CAR, "Car profile ${identity.key}: quality set to $tier")
    }

    override suspend fun screen(): ScreenSize? {
        latestSurface?.let { return ScreenSize(it.width, it.height) }
        val identity = identity.await()
        return store.getOrCreate(identity.key, identity.suggestedName).screen
    }

    companion object {
        const val MODEL_TIMEOUT_MS = 2_000L
        const val SURFACE_TIMEOUT_MS = 3_000L
    }
}

/**
 * Asks the head unit for the car's make, model and year (`CarInfo.fetchModel`, car API level 3+).
 * Returns null on older hosts or if the host refuses; suspends for ever if the host never answers
 * (the caller applies a timeout).
 */
suspend fun fetchCarModel(carContext: CarContext): CarModel? {
    if (carContext.carAppApiLevel < CarAppApiLevels.LEVEL_3) return null
    val carInfo = try {
        carContext.getCarService(CarHardwareManager::class.java).carInfo
    } catch (e: RuntimeException) {
        // IllegalStateException: no hardware manager on this host. HostException: host went away.
        Log.w(CarLog.CAR, "Car hardware info is unavailable: ${e.javaClass.simpleName}")
        return null
    }
    return suspendCancellableCoroutine { continuation ->
        try {
            carInfo.fetchModel(ContextCompat.getMainExecutor(carContext)) { model ->
                if (continuation.isActive) {
                    continuation.resume(CarModel(model.manufacturer.known(), model.name.known(), model.year.known()))
                }
            }
        } catch (e: RuntimeException) {
            // SecurityException / HostException: treat as "the car will not say".
            Log.w(CarLog.CAR, "Could not ask for the car model: ${e.javaClass.simpleName}")
            if (continuation.isActive) continuation.resume(null)
        }
    }
}

private fun <T> CarValue<T>.known(): T? = if (status == CarValue.STATUS_SUCCESS) value else null
