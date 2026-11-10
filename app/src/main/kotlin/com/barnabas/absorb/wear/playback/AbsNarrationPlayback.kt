package com.barnabas.absorb.wear.playback

import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * A narrated EPUB on an Audiobookshelf server, played like an audiobook.
 *
 * The server has no audio session to open for it (it only sees an ebook),
 * so the "session" is local: the EPUB's narration is read through
 * [AbsNarration], and the position is kept on the item's media progress row
 * the way the phone client keeps it, with the narration's length as the
 * duration so both devices resume at the same second.
 */
class AbsNarrationPlayback(private val client: AbsorbHttpClient, private val creds: Credentials) {

    @Serializable
    private data class ItemDetail(val id: String? = null, val media: Media? = null) {
        @Serializable
        data class Media(val ebookFile: EbookFile? = null, val numAudioFiles: Int = 0)

        @Serializable
        data class EbookFile(val ino: String? = null, val ebookFormat: String? = null)
    }

    @Serializable
    private data class ProgressRow(val currentTime: Double = 0.0, val duration: Double = 0.0, val lastUpdate: Long = 0)

    suspend fun start(itemId: String): Result<PlaybackSession> {
        val detail = when (val r = client.get("/api/items/$itemId", ItemDetail.serializer())) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val ebook = detail.media?.ebookFile
        val ino = ebook?.ino
        if (ino == null || !ebook.ebookFormat.equals("epub", ignoreCase = true)) {
            return Result.Failed("No audiobook on this book")
        }
        val book = try {
            AbsNarration.prepare(creds, itemId, ino)
        } catch (e: Exception) {
            Log.w(TAG, "narration for $itemId could not be read", e)
            return Result.Failed(e.message ?: "Could not read the EPUB")
        }
        val overlay = book.overlay
        val saved = when (val r = client.get("/api/me/progress/$itemId", ProgressRow.serializer())) {
            is Result.Ok -> r.value.currentTime
            else -> 0.0
        }
        val tracks = overlay.tracks.mapIndexed { i, t ->
            AudioTrack(
                index = i + 1,
                contentUrl = AbsNarration.trackUri(itemId, i),
                duration = t.duration,
                startOffset = t.start,
                mimeType = t.mediaType,
            )
        }
        val now = System.currentTimeMillis()
        Log.i(TAG, "narration session for $itemId: ${tracks.size} file(s), ${overlay.duration.toLong()}s, from ${saved.toLong()}s")
        return Result.Ok(
            PlaybackSession(
                id = "narr-$itemId-$now",
                libraryItemId = itemId,
                audioTracks = tracks,
                chapters = overlay.chapters(),
                duration = overlay.duration,
                currentTime = saved.coerceIn(0.0, overlay.duration),
            ),
        )
    }

    /**
     * `PATCH /api/me/progress/:id` with the narration's length as the
     * duration, then the listening time as a local session the way the
     * phone logs it. The server sizes a new session by the item's own
     * length, which is zero for an ebook, and copies a session's position
     * onto the progress row unless the row is newer, so the session is
     * dated a second before the row's save: the time is logged and the
     * position is left as the PATCH put it.
     */
    suspend fun sync(
        sessionId: String,
        currentTime: Double,
        timeListened: Double,
        duration: Double,
        device: PlaybackRepository.DeviceInfo,
    ): Result<Unit> {
        val itemId = itemIdOf(sessionId) ?: return Result.Failed("Unknown session")
        val total = AbsNarration.book(itemId)?.overlay?.duration?.takeIf { it > 0 } ?: duration
        val body = buildJsonObject {
            put("currentTime", currentTime)
            put("duration", total)
            put("progress", if (total > 0) (currentTime / total).coerceIn(0.0, 1.0) else 0.0)
        }.toString()
        val r = client.patchForStatus("/api/me/progress/$itemId", body)
        if (r !is Result.Ok) {
            Log.w(TAG, "narration progress for $itemId not saved: $r")
            return r
        }
        val rowAt = when (val row = client.get("/api/me/progress/$itemId", ProgressRow.serializer())) {
            is Result.Ok -> row.value.lastUpdate.takeIf { it > 0 }
            else -> null
        }
        if (rowAt == null) {
            Log.w(TAG, "narration session for $itemId not logged: the progress row could not be read")
            return Result.Failed("Progress row not readable")
        }
        // Streamed out of the EPUB on the server unless the book is kept here.
        val playMethod = if (BookDownloads.isDownloaded(itemId)) AbsLocalPlayback.PLAY_METHOD_LOCAL else AbsLocalPlayback.PLAY_METHOD_DIRECT
        return AbsLocalPlayback(client, device).sync(sessionId, currentTime, timeListened, total, updatedAtMs = rowAt - 1000, playMethod = playMethod)
    }

    companion object {
        private const val TAG = "AbsorbWearPlayback"
        private val SESSION_ID = Regex("^narr-(.+)-(\\d+)$")

        fun isNarrationSession(sessionId: String): Boolean = sessionId.startsWith("narr-")

        fun itemIdOf(sessionId: String): String? = SESSION_ID.matchEntire(sessionId)?.groupValues?.get(1)
    }
}
