package app.aafin.data.playback

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import app.aafin.AAFinApp
import app.aafin.data.AppJson
import app.aafin.data.jellyfin.JellyfinException
import java.util.concurrent.TimeUnit

/**
 * [PositionQueue] on WorkManager: each queued position is a unique job that runs as soon as the
 * phone has a network connection, survives app restarts and reboots, and retries with back-off.
 */
class WorkManagerPositionQueue(private val context: Context) : PositionQueue {

    override fun enqueue(position: QueuedPosition) {
        val request = OneTimeWorkRequestBuilder<PositionSyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInputData(workDataOf(PositionSyncWorker.KEY_POSITION to AppJson.encodeToString(QueuedPosition.serializer(), position)))
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(workName(position.accountId, position.itemId), ExistingWorkPolicy.REPLACE, request)
    }

    override fun cancel(accountId: String, itemId: String) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(accountId, itemId))
    }

    private fun workName(accountId: String, itemId: String) = "position-sync:$accountId:$itemId"
}

/** Sends one [QueuedPosition] to Jellyfin. */
class PositionSyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val json = inputData.getString(KEY_POSITION) ?: return Result.failure()
        val position = AppJson.decodeFromString(QueuedPosition.serializer(), json)
        return try {
            AAFinApp.graph(applicationContext).progressReporter.sendQueued(position)
            Result.success()
        } catch (e: JellyfinException) {
            when (e.kind) {
                // The server may be back later: keep trying, but not for ever.
                JellyfinException.Kind.NETWORK,
                JellyfinException.Kind.TLS,
                JellyfinException.Kind.SERVER,
                -> if (runAttemptCount < MAX_ATTEMPTS) Result.retry() else giveUp(e)
                // Signed out, item deleted, access revoked: retrying cannot help.
                else -> giveUp(e)
            }
        }
    }

    private fun giveUp(error: JellyfinException): Result {
        Log.w(TAG, "Dropping queued watch position (${error.kind})")
        return Result.failure()
    }

    companion object {
        const val KEY_POSITION = "position"
        private const val MAX_ATTEMPTS = 20
        private const val TAG = "AAFin.PositionSync"
    }
}
