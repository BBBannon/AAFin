package app.aafin.data.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.annotation.MainThread
import androidx.annotation.OptIn
import androidx.datastore.core.DataStore
import androidx.media3.common.util.NetworkTypeObserver
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.IOException

/** How fast the connection to the server has recently been. */
fun interface ThroughputEstimator {
    /** Measured download speed in bits per second on the network in use now, or null if nothing is known about it. */
    suspend fun estimateBps(): Long?
}

/**
 * The last good measurement, kept on disk so the first thing played after a cold start has
 * something to go on.
 *
 * @property networkType Media3's `C.NetworkType` at the time (Wi-Fi, 4G, 5G…): a measurement only
 *   says something about the kind of network it was made on.
 */
@Serializable
data class SavedThroughput(
    val bitrate: Long = 0,
    val networkType: Int = 0,
)

/**
 * Decides which of the bandwidth meter's reports are real measurements. Pure, so it is unit-tested.
 *
 * Media3's meter always has a number: before it has measured anything, and again whenever the
 * kind of network changes, that number is a built-in guess for the country and network type.
 * Only values backed by enough transferred data on the current kind of network count here - the
 * same threshold the meter itself uses before it trusts its samples.
 */
class ThroughputTracker {
    private var saved: SavedThroughput? = null
    private var measured: SavedThroughput? = null
    private var sampleNetworkType: Int? = null
    private var sampledMs = 0L
    private var sampledBytes = 0L
    private var lastSavedAtMs: Long? = null

    /** The value read back from disk at start-up. */
    fun restore(value: SavedThroughput) {
        saved = value.takeIf { it.bitrate > 0 }
    }

    /**
     * One report from the meter.
     *
     * @return a measurement that should be written to disk now, or null.
     */
    fun onSample(elapsedMs: Int, bytesTransferred: Long, bitrateEstimate: Long, networkType: Int, nowMs: Long): SavedThroughput? {
        if (networkType != sampleNetworkType) {
            // The meter has just gone back to its built-in guess for the new network: start counting again.
            sampleNetworkType = networkType
            sampledMs = 0
            sampledBytes = 0
            return null
        }
        if (elapsedMs <= 0 || bytesTransferred <= 0 || bitrateEstimate <= 0) return null
        sampledMs += elapsedMs
        sampledBytes += bytesTransferred
        if (sampledMs < MIN_SAMPLED_MS && sampledBytes < MIN_SAMPLED_BYTES) return null

        val value = SavedThroughput(bitrateEstimate, networkType)
        measured = value
        val savedAt = lastSavedAtMs
        if (savedAt != null && nowMs - savedAt < SAVE_EVERY_MS) return null
        lastSavedAtMs = nowMs
        return value
    }

    /** The newest measurement made on [networkType]: from this run if there is one, else from disk. */
    fun estimate(networkType: Int): Long? =
        (measured ?: saved)?.takeIf { it.networkType == networkType }?.bitrate

    companion object {
        const val MIN_SAMPLED_MS = 2_000L
        const val MIN_SAMPLED_BYTES = 512 * 1024L
        const val SAVE_EVERY_MS = 30_000L
    }
}

/**
 * [ThroughputEstimator] on top of Media3's process-wide `DefaultBandwidthMeter`, which watches
 * every byte the player loads. Being a singleton, its estimate carries over from one item to the
 * next; [store] carries the last good value over a restart.
 *
 * Create on the main thread.
 */
@MainThread
@OptIn(UnstableApi::class)
class MeasuredThroughput(
    context: Context,
    private val store: DataStore<SavedThroughput>,
    private val scope: CoroutineScope,
) : ThroughputEstimator {

    /** The meter to give the player, so that what it loads is what gets measured. */
    val meter: DefaultBandwidthMeter = DefaultBandwidthMeter.getSingletonInstance(context)

    private val networkTypes = NetworkTypeObserver.getInstance(context)
    private val tracker = ThroughputTracker()

    private val restored: Deferred<Unit> = scope.async {
        try {
            tracker.restore(store.data.first())
        } catch (e: IOException) {
            Log.w(TAG, "The saved connection speed could not be read: ${e.javaClass.simpleName}")
        }
    }

    init {
        meter.addEventListener(Handler(Looper.getMainLooper())) { elapsedMs, bytesTransferred, bitrateEstimate ->
            tracker.onSample(elapsedMs, bytesTransferred, bitrateEstimate, networkTypes.networkType, SystemClock.elapsedRealtime())
                ?.let(::save)
        }
    }

    override suspend fun estimateBps(): Long? {
        restored.await()
        return tracker.estimate(networkTypes.networkType)
    }

    private fun save(value: SavedThroughput) {
        scope.launch {
            try {
                store.updateData { value }
            } catch (e: IOException) {
                Log.w(TAG, "The connection speed could not be saved: ${e.javaClass.simpleName}")
            }
        }
    }

    private companion object {
        const val TAG = "AAFin/Playback"
    }
}
