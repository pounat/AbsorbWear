package com.barnabas.absorb.wear.player

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.R

/**
 * Posts / updates / cancels the cold-start "now playing" notification — the
 * AbsorbWear-owned control surface that appears on the watch the moment the
 * phone starts playing, *without the app being open*. Tapping it opens our
 * now-playing screen; the action buttons drive the phone directly via
 * [MediaCommandReceiver].
 *
 * Why a notification rather than the MediaSessionService: we can't reliably
 * start a foreground media service from a background data push on Android
 * 12+ (ForegroundServiceStartNotAllowedException). Posting a notification
 * has no such restriction, so this is the dependable cold-start path across
 * Wear OS versions. The MediaSession remains for the system-bind path and
 * future on-watch playback.
 */
object NowPlayingNotifier {

    private const val CHANNEL_ID = "absorb_now_playing"
    private const val NOTIFICATION_ID = 42

    /** Reflect [state] into the notification: show while a book is loaded,
     *  cancel when there's nothing to control. Safe to call repeatedly. */
    fun update(context: Context, state: PlayerState) {
        val manager = NotificationManagerCompat.from(context)
        if (!state.hasBook) {
            manager.cancel(NOTIFICATION_ID)
            return
        }
        ensureChannel(context)

        // Tapping opens the phone-remote screen, the same way Wear OS would
        // open it for the phone's session once its own auto-launch exists.
        val contentIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_REMOTE_MEDIA_ACTIVITY)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )

        val playPauseIcon =
            if (state.isPlaying) android.R.drawable.ic_media_pause
            else android.R.drawable.ic_media_play
        val playPauseLabel = if (state.isPlaying) "Pause" else "Play"

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(state.title?.takeIf { it.isNotBlank() } ?: "Now playing")
            .setContentText(state.chapter?.takeIf { it.isNotBlank() } ?: state.author.orEmpty())
            .setContentIntent(contentIntent)
            .setOngoing(state.isPlaying)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            // Tied to the mirror session, so the system treats it as the
            // media notification for that session.
            .apply {
                PhoneMirrorSession.current?.let {
                    setStyle(androidx.media3.session.MediaStyleNotificationHelper.MediaStyle(it).setShowActionsInCompactView(0, 1, 2))
                }
            }
            .addAction(
                android.R.drawable.ic_media_rew, "Back ${state.skipBackSec}s",
                broadcast(context, MediaCommandReceiver.ACTION_SKIP_BACK, 1),
            )
            .addAction(
                playPauseIcon, playPauseLabel,
                broadcast(context, MediaCommandReceiver.ACTION_PLAY_PAUSE, 2),
            )
            .addAction(
                android.R.drawable.ic_media_ff, "Fwd ${state.skipForwardSec}s",
                broadcast(context, MediaCommandReceiver.ACTION_SKIP_FORWARD, 3),
            )
            .build()

        if (NotificationManagerCompat.from(context).areNotificationsEnabled()) {
            manager.notify(NOTIFICATION_ID, notification)
        }
    }

    fun cancel(context: Context) {
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    private fun broadcast(context: Context, action: String, requestCode: Int): PendingIntent {
        val intent = Intent(context, MediaCommandReceiver::class.java).setAction(action)
        return PendingIntent.getBroadcast(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    "Now playing",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "Playback controls while a book is playing"
                    setShowBadge(false)
                },
            )
        }
    }
}
