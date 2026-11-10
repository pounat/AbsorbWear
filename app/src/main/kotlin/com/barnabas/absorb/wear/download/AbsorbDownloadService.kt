package com.barnabas.absorb.wear.download

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.offline.Download
import androidx.media3.exoplayer.offline.DownloadManager
import androidx.media3.exoplayer.offline.DownloadNotificationHelper
import androidx.media3.exoplayer.offline.DownloadService
import androidx.media3.exoplayer.scheduler.Scheduler
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.R

/**
 * Foreground service that runs Media3's download manager while books are
 * being fetched into the media cache. The notification is the system's
 * progress template; tapping it opens the app.
 */
@UnstableApi
class AbsorbDownloadService : DownloadService(
    NOTIFICATION_ID,
    DEFAULT_FOREGROUND_NOTIFICATION_UPDATE_INTERVAL,
    CHANNEL_ID,
    R.string.downloads_channel_name,
    R.string.downloads_channel_description,
) {
    override fun getDownloadManager(): DownloadManager = BookDownloads.manager(this)

    override fun getScheduler(): Scheduler? = null

    override fun getForegroundNotification(downloads: MutableList<Download>, notMetRequirements: Int): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val titles = downloads.mapNotNull { d -> DownloadStore[BookDownloads.keyOf(d.request.id)]?.title }.distinct()
        val message = when (titles.size) {
            0 -> getString(R.string.downloads_in_progress)
            1 -> titles.first()
            else -> getString(R.string.downloads_count, titles.size)
        }
        return DownloadNotificationHelper(this, CHANNEL_ID)
            .buildProgressNotification(this, R.mipmap.ic_launcher, openApp, message, downloads, notMetRequirements)
    }

    companion object {
        const val CHANNEL_ID = "absorb_downloads"
        private const val NOTIFICATION_ID = 7
    }
}
