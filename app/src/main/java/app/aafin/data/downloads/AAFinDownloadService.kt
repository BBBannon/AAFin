package app.aafin.data.downloads

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.annotation.OptIn
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import app.aafin.AAFinApp
import app.aafin.R
import app.aafin.phone.MainActivity

/**
 * Foreground service (type `dataSync`) that keeps downloads running while the app is in the
 * background, with a progress notification. Media3 creates the notification channel.
 *
 * No scheduler is used: after the process dies, unfinished downloads resume the next time the
 * app is opened (`DownloadRepo.resumePending`). Restarting a foreground service from a background
 * job is restricted on Android 12+, so this is the dependable option.
 */
@OptIn(UnstableApi::class)
class AAFinDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.download_channel_name,
    R.string.download_channel_description,
) {

    override fun getDownloadManager(): DownloadManager = AAFinApp.graph(this).downloadEnvironment.downloadManager

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return DownloadNotificationHelper(this, CHANNEL_ID).buildProgressNotification(
            this,
            R.drawable.ic_notification,
            openApp,
            null,
            downloads,
            notMetRequirements,
        )
    }

    private companion object {
        const val NOTIFICATION_ID = 4101
        const val CHANNEL_ID = "downloads"
    }
}
