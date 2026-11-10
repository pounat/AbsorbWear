package com.barnabas.absorb.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.R
import com.barnabas.absorb.wear.player.MediaCommandReceiver
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerStateClient
import kotlinx.coroutines.withTimeoutOrNull

/**
 * A play or pause button on the watch face: a tap toggles the book that
 * is playing, on the watch or on the phone, without leaving the face. With
 * nothing loaded anywhere the tap opens the app instead.
 */
class PlayPauseComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, NowPlayingSnapshot("The Hobbit", 0.43f, isPlaying = false, onPhone = false, itemId = null))

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (PlayerStateStore.state.value == null) {
            withTimeoutOrNull(3_000L) { PlayerStateClient.seedFromPhone(this@PlayPauseComplicationService) }
        }
        return build(request.complicationType, NowPlayingSnapshot.current())
    }

    private fun build(type: ComplicationType, book: NowPlayingSnapshot?): ComplicationData? {
        val playing = book?.isPlaying == true
        val icon = Icon.createWithResource(this, if (playing) R.drawable.ic_pause else R.drawable.ic_play)
        val word = if (playing) "Pause" else "Play"
        val description = plain(if (book == null) "Absorb, nothing to play" else "$word ${book.title}")
        val tap = if (book == null) openApp() else toggle()

        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(
                MonochromaticImage.Builder(icon).build(),
                description,
            )
                .setTapAction(tap)
                .build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(
                SmallImage.Builder(icon, SmallImageType.ICON).build(),
                description,
            )
                .setTapAction(tap)
                .build()
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(word), description)
                .setMonochromaticImage(MonochromaticImage.Builder(icon).build())
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    /** The receiver that toggles whichever player has the book. */
    private fun toggle(): PendingIntent {
        val intent = Intent(this, MediaCommandReceiver::class.java).setAction(MediaCommandReceiver.ACTION_PLAY_PAUSE)
        return PendingIntent.getBroadcast(this, 2, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun openApp(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_NEW_TASK }
        return PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()
}
