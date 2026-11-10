package com.barnabas.absorb.wear.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Foreground service that pulls a narrated EPUB's audio files out of the
 * EPUB on the server, one file at a time, so the pull keeps
 * going with the screen dark and the app closed, like the media download
 * service does for plain tracks. One item per start; more starts queue up.
 */
class NarrationPullService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queue = ArrayDeque<Pair<String, Int>>()
    private var running = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val itemId = intent?.getStringExtra(EXTRA_ITEM) ?: return START_NOT_STICKY
        val count = intent.getIntExtra(EXTRA_COUNT, 0)
        startInForeground(DownloadStore[itemId]?.title)
        queue.addLast(itemId to count)
        if (!running) drain()
        return START_NOT_STICKY
    }

    private fun drain() {
        val next = queue.removeFirstOrNull()
        if (next == null) {
            running = false
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return
        }
        running = true
        val (itemId, count) = next
        startInForeground(DownloadStore[itemId]?.title)
        scope.launch {
            try {
                BookDownloads.pullNarration(itemId, count) { done ->
                    startInForeground(DownloadStore[itemId]?.title, done, count)
                }
            } catch (e: Exception) {
                Log.w(TAG, "narration pull for $itemId stopped", e)
            }
            drain()
        }
    }

    private fun startInForeground(title: String?, done: Int = 0, total: Int = 0) {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(AbsorbDownloadService.CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    AbsorbDownloadService.CHANNEL_ID,
                    getString(R.string.downloads_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply { description = getString(R.string.downloads_channel_description) },
            )
        }
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, AbsorbDownloadService.CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title ?: getString(R.string.downloads_in_progress))
            .setContentText(if (total > 0) "$done of $total files" else getString(R.string.downloads_in_progress))
            .setProgress(total, done, total == 0)
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "AbsorbWearDownload"
        private const val NOTIFICATION_ID = 8
        private const val EXTRA_ITEM = "item"
        private const val EXTRA_COUNT = "count"

        fun start(context: Context, itemId: String, trackCount: Int) {
            val intent = Intent(context.applicationContext, NarrationPullService::class.java)
                .putExtra(EXTRA_ITEM, itemId)
                .putExtra(EXTRA_COUNT, trackCount)
            context.applicationContext.startForegroundService(intent)
        }
    }
}
