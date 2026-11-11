package app.aafin.data.playback

import android.util.Log
import app.aafin.data.Ticks
import app.aafin.data.accounts.AccountStore
import app.aafin.data.jellyfin.JellyfinClients
import app.aafin.data.jellyfin.JellyfinException
import app.aafin.data.jellyfin.jellyfinCall
import kotlinx.serialization.Serializable
import java.util.concurrent.ConcurrentHashMap
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.model.api.PlaybackOrder
import org.jellyfin.sdk.model.api.PlaybackProgressInfo
import org.jellyfin.sdk.model.api.PlaybackStartInfo
import org.jellyfin.sdk.model.api.PlaybackStopInfo
import org.jellyfin.sdk.model.api.RepeatMode
import org.jellyfin.sdk.model.serializer.toUUID
import org.jellyfin.sdk.model.api.PlayMethod as SdkPlayMethod

/** Where playback is right now; the payload of every progress report. */
data class PlaybackSnapshot(
    val accountId: String,
    val itemId: String,
    val mediaSourceId: String,
    val playSessionId: String?,
    val playMethod: PlayMethod,
    val positionMs: Long,
    val isPaused: Boolean,
    val audioStreamIndex: Int? = null,
    val subtitleStreamIndex: Int? = null,
)

/** A watch position waiting to be sent to the server once there is a connection. */
@Serializable
data class QueuedPosition(
    val accountId: String,
    val itemId: String,
    val mediaSourceId: String,
    val positionMs: Long,
)

/** Durable queue for positions that could not be sent. The production implementation is WorkManager. */
interface PositionQueue {
    /** Queues [position], replacing any older queued position for the same account and item. */
    fun enqueue(position: QueuedPosition)

    /** Drops the queued position for this account and item, if any (a newer one reached the server). */
    fun cancel(accountId: String, itemId: String)
}

/** The Jellyfin play-state endpoints. Implementations throw [JellyfinException]. */
interface PlaystateGateway {
    /** `POST /Sessions/Playing` */
    suspend fun reportStart(snapshot: PlaybackSnapshot)

    /** `POST /Sessions/Playing/Progress` */
    suspend fun reportProgress(snapshot: PlaybackSnapshot)

    /** `POST /Sessions/Playing/Stopped` - also ends the server's transcode for this play session. */
    suspend fun reportStopped(snapshot: PlaybackSnapshot)

    /**
     * Saves a watch position after the fact, as a stop report without a play session id
     * (so it cannot end a transcode that has started since).
     */
    suspend fun reportPosition(position: QueuedPosition)
}

/**
 * Keeps Jellyfin's watch progress in step with playback.
 *
 * Reports never throw: a failed report must not interrupt the film. Instead each call returns
 * whether the server received it, and failed position-bearing reports (progress, stopped) are
 * handed to the [PositionQueue] to be retried when the network returns. Offline playback has no
 * server session, so it skips start/progress reports and queues its position when it stops.
 */
class ProgressReporter(
    private val gateway: PlaystateGateway,
    private val queue: PositionQueue,
) {
    /** Account/item pairs with a position queued by this process. */
    private val queuedKeys: MutableSet<Pair<String, String>> = ConcurrentHashMap.newKeySet()

    suspend fun started(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.playMethod == PlayMethod.OFFLINE) return false
        return send("start") { gateway.reportStart(snapshot) }
    }

    suspend fun progress(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.playMethod == PlayMethod.OFFLINE) return false
        return send("progress") { gateway.reportProgress(snapshot) }.also { settle(snapshot, it) }
    }

    suspend fun stopped(snapshot: PlaybackSnapshot): Boolean {
        if (snapshot.playMethod == PlayMethod.OFFLINE) {
            settle(snapshot, delivered = false)
            return false
        }
        return send("stopped") { gateway.reportStopped(snapshot) }.also { settle(snapshot, it) }
    }

    /**
     * Queues the position if it was not delivered. If it was, drops any older position this
     * process queued for the same item, so a stale retry cannot overwrite the newer one.
     */
    private fun settle(snapshot: PlaybackSnapshot, delivered: Boolean) {
        val key = snapshot.accountId to snapshot.itemId
        if (!delivered) {
            queue.enqueue(snapshot.toQueuedPosition())
            queuedKeys += key
        } else if (queuedKeys.remove(key)) {
            queue.cancel(snapshot.accountId, snapshot.itemId)
        }
    }

    /**
     * Sends a queued position. Used by the WorkManager worker.
     * @throws JellyfinException so the worker can decide between retrying and giving up.
     */
    suspend fun sendQueued(position: QueuedPosition) = gateway.reportPosition(position)

    private suspend fun send(what: String, block: suspend () -> Unit): Boolean = try {
        block()
        true
    } catch (e: JellyfinException) {
        // Kind only: exception messages from the HTTP layer could contain URLs.
        Log.w(TAG, "Playback $what report not delivered (${e.kind})")
        false
    }

    private fun PlaybackSnapshot.toQueuedPosition() = QueuedPosition(accountId, itemId, mediaSourceId, positionMs)

    private companion object {
        const val TAG = "AAFin.Progress"
    }
}

/** [PlaystateGateway] backed by the Jellyfin Kotlin SDK. */
class SdkPlaystateGateway(
    private val clients: JellyfinClients,
    private val accountStore: AccountStore,
) : PlaystateGateway {

    override suspend fun reportStart(snapshot: PlaybackSnapshot) = call(snapshot.accountId) { api ->
        api.playStateApi.reportPlaybackStart(
            PlaybackStartInfo(
                canSeek = true,
                itemId = snapshot.itemId.toUUID(),
                mediaSourceId = snapshot.mediaSourceId,
                audioStreamIndex = snapshot.audioStreamIndex,
                subtitleStreamIndex = snapshot.subtitleStreamIndex,
                isPaused = snapshot.isPaused,
                isMuted = false,
                positionTicks = Ticks.fromMs(snapshot.positionMs),
                playMethod = snapshot.playMethod.toSdk(),
                playSessionId = snapshot.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    override suspend fun reportProgress(snapshot: PlaybackSnapshot) = call(snapshot.accountId) { api ->
        api.playStateApi.reportPlaybackProgress(
            PlaybackProgressInfo(
                canSeek = true,
                itemId = snapshot.itemId.toUUID(),
                mediaSourceId = snapshot.mediaSourceId,
                audioStreamIndex = snapshot.audioStreamIndex,
                subtitleStreamIndex = snapshot.subtitleStreamIndex,
                isPaused = snapshot.isPaused,
                isMuted = false,
                positionTicks = Ticks.fromMs(snapshot.positionMs),
                playMethod = snapshot.playMethod.toSdk(),
                playSessionId = snapshot.playSessionId,
                repeatMode = RepeatMode.REPEAT_NONE,
                playbackOrder = PlaybackOrder.DEFAULT,
            ),
        )
    }

    override suspend fun reportStopped(snapshot: PlaybackSnapshot) = call(snapshot.accountId) { api ->
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = snapshot.itemId.toUUID(),
                mediaSourceId = snapshot.mediaSourceId,
                positionTicks = Ticks.fromMs(snapshot.positionMs),
                playSessionId = snapshot.playSessionId,
                failed = false,
            ),
        )
    }

    override suspend fun reportPosition(position: QueuedPosition) = call(position.accountId) { api ->
        api.playStateApi.reportPlaybackStopped(
            PlaybackStopInfo(
                itemId = position.itemId.toUUID(),
                mediaSourceId = position.mediaSourceId,
                positionTicks = Ticks.fromMs(position.positionMs),
                failed = false,
            ),
        )
    }

    private suspend fun call(accountId: String, block: suspend (ApiClient) -> Any?) {
        val account = accountStore.get(accountId) ?: throw JellyfinException(JellyfinException.Kind.NOT_SIGNED_IN)
        val api = clients.forAccount(account)
        jellyfinCall { block(api) }
    }

    private fun PlayMethod.toSdk(): SdkPlayMethod = when (this) {
        PlayMethod.DIRECT_PLAY, PlayMethod.OFFLINE -> SdkPlayMethod.DIRECT_PLAY
        PlayMethod.DIRECT_STREAM -> SdkPlayMethod.DIRECT_STREAM
        PlayMethod.TRANSCODE -> SdkPlayMethod.TRANSCODE
    }
}
