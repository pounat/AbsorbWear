package com.barnabas.absorb.wear.complication

import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.core.graphics.drawable.toBitmap
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.SmallImage
import androidx.wear.watchface.complications.data.SmallImageComplicationData
import androidx.wear.watchface.complications.data.SmallImageType
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.barnabas.absorb.wear.MainActivity
import com.barnabas.absorb.wear.R
import com.barnabas.absorb.wear.api.CoverArt
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.download.CoverStore
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.sync.PlayerStateClient
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt

/**
 * The watch face complication: the book that is playing, on the watch or
 * on the phone, with how far through it is. A tap opens the app on that
 * book's player. Offered as a progress arc, short and long text, the
 * cover, and the wave icon, so it fits whatever slot the face has.
 */
class NowPlayingComplicationService : SuspendingComplicationDataSourceService() {

    override fun getPreviewData(type: ComplicationType): ComplicationData? =
        build(type, NowPlayingSnapshot("The Hobbit", 0.43f, isPlaying = true, onPhone = false, itemId = null), cover = null)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData? {
        if (PlayerStateStore.state.value == null) {
            withTimeoutOrNull(3_000L) { PlayerStateClient.seedFromPhone(this@NowPlayingComplicationService) }
        }
        val book = NowPlayingSnapshot.current()
        val wantsCover = request.complicationType == ComplicationType.LONG_TEXT || request.complicationType == ComplicationType.SMALL_IMAGE
        return build(request.complicationType, book, cover = if (wantsCover) coverImage(book) else null)
    }

    private fun build(type: ComplicationType, book: NowPlayingSnapshot?, cover: SmallImage?): ComplicationData? {
        val tap = tapAction(book)
        val wave = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_wave)).build()
        val percent = book?.let { "${(it.fraction * 100).roundToInt()}%" }
        val status = when {
            book == null -> "Nothing playing"
            book.isPlaying && book.onPhone -> "Playing on phone"
            book.isPlaying -> "Playing"
            book.onPhone -> "Paused on phone"
            else -> "Paused"
        }
        val title = book?.title ?: "Absorb"
        val description = plain(if (book == null) "Absorb, nothing playing" else "$title, $status, $percent")

        return when (type) {
            ComplicationType.SHORT_TEXT -> ShortTextComplicationData.Builder(plain(percent ?: "Absorb"), description)
                .apply { if (book != null) setTitle(plain(title)) }
                .setMonochromaticImage(wave)
                .setTapAction(tap)
                .build()
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(plain(title), description)
                .setTitle(plain(if (percent != null) "$status · $percent" else status))
                .setSmallImage(cover ?: appImage())
                .setTapAction(tap)
                .build()
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                value = book?.fraction?.coerceIn(0f, 1f)?.times(100f) ?: 0f,
                min = 0f,
                max = 100f,
                contentDescription = description,
            )
                .setText(plain(percent ?: "Absorb"))
                .apply { if (book != null) setTitle(plain(title)) }
                .setMonochromaticImage(wave)
                .setTapAction(tap)
                .build()
            ComplicationType.SMALL_IMAGE -> SmallImageComplicationData.Builder(cover ?: appImage(), description)
                .setTapAction(tap)
                .build()
            ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(wave, description)
                .setTapAction(tap)
                .build()
            else -> null
        }
    }

    private fun appImage() =
        SmallImage.Builder(Icon.createWithResource(this, R.mipmap.ic_launcher), SmallImageType.ICON).build()

    /** The book's cover as a photo, from the kept copy or the server. */
    private suspend fun coverImage(book: NowPlayingSnapshot?): SmallImage? {
        val itemId = book?.itemId ?: return null
        val creds = CredentialStore.credentials.value ?: return null
        val kept = CoverStore.existing(itemId)
        val request = ImageRequest.Builder(this)
            .data(kept ?: CoverArt.url(creds, itemId))
            .allowHardware(false)
            .size(96)
            .apply {
                if (kept == null) {
                    addHeader("Authorization", "Bearer ${creds.accessToken}")
                    creds.customHeaders.forEach { (key, value) -> addHeader(key, value) }
                }
            }
            .build()
        val bitmap = (imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return null
        return SmallImage.Builder(Icon.createWithBitmap(bitmap), SmallImageType.PHOTO).build()
    }

    private fun tapAction(book: NowPlayingSnapshot?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
            if (book?.onPhone == true) action = MainActivity.ACTION_REMOTE_MEDIA_ACTIVITY
        }
        val code = if (book?.onPhone == true) 1 else 0
        return PendingIntent.getActivity(this, code, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun plain(text: String): ComplicationText = PlainComplicationText.Builder(text).build()
}

/** What the complication shows: the watch's own book first, else the phone's. */
data class NowPlayingSnapshot(
    val title: String,
    val fraction: Float,
    val isPlaying: Boolean,
    val onPhone: Boolean,
    val itemId: String?,
) {
    companion object {
        fun current(nowMs: Long = System.currentTimeMillis()): NowPlayingSnapshot? {
            val watch = PlaybackManager.uiState.value
            val selection = PlaybackManager.selection.value
            if (watch.itemId != null) {
                return NowPlayingSnapshot(
                    title = watch.title ?: selection?.title ?: "Absorb",
                    fraction = if (watch.durationMs > 0) watch.positionMs.toFloat() / watch.durationMs else 0f,
                    isPlaying = watch.isPlaying,
                    onPhone = false,
                    itemId = watch.itemId,
                )
            }
            val phone = PlayerStateStore.state.value
            if (phone != null && phone.hasBook && phone.itemId != null) {
                val elapsed = if (phone.isPlaying && phone.issuedAt > 0) ((nowMs - phone.issuedAt) * phone.speed).toLong() else 0L
                val position = (phone.positionMs + elapsed).coerceIn(0L, phone.durationMs.coerceAtLeast(0L))
                return NowPlayingSnapshot(
                    title = phone.title ?: "Absorb",
                    fraction = if (phone.durationMs > 0) position.toFloat() / phone.durationMs else 0f,
                    isPlaying = phone.isPlaying,
                    onPhone = true,
                    itemId = phone.itemId,
                )
            }
            return null
        }
    }
}
