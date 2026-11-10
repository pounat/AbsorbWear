package com.barnabas.absorb.wear.playback

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.download.keepKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Opens, syncs, and closes playback sessions for on-watch streaming.
 * Endpoints + payloads mirror the Absorb Flutter client so progress stays
 * consistent across phone and watch. On a BookOrbit server the same three
 * calls are answered by [BookOrbitPlayback] instead.
 */
class PlaybackRepository(private val creds: Credentials) {

    private val client = AbsorbHttpClient(creds)
    private val bookOrbit = if (creds.isBookOrbit) BookOrbitPlayback(client, creds) else null
    private val narration = if (creds.isBookOrbit) null else AbsNarrationPlayback(client, creds)
    private val json = Json { encodeDefaults = true }

    /** `POST /api/items/:id/play` (book) or `.../play/:episodeId` (podcast).
     *  An Audiobookshelf book the server cannot open a session on (an ebook
     *  to it) is tried as a narrated EPUB. */
    suspend fun start(
        itemId: String,
        episodeId: String?,
        device: DeviceInfo,
    ): AbsorbHttpClient.Result<PlaybackSession> = withContext(Dispatchers.IO) {
        // Off the main thread: a narrated book's start builds thousands of
        // sentence timings, which froze the screen for seconds.
        lastDevice = device
        kept(itemId, episodeId)?.let { return@withContext it }
        bookOrbit?.let { return@withContext it.start(itemId) }
        val path = if (episodeId != null) {
            "/api/items/$itemId/play/$episodeId"
        } else {
            "/api/items/$itemId/play"
        }
        val body = json.encodeToString(PlayRequest.serializer(), PlayRequest(deviceInfo = device))
        val session = client.post(path, body, PlaybackSession.serializer())
        if (session is AbsorbHttpClient.Result.Ok && session.value.audioTracks.isEmpty() ||
            session is AbsorbHttpClient.Result.Failed && session.code in setOf(400, 404, 500)
        ) {
            narration?.let { n ->
                if (episodeId == null) {
                    val narrated = n.start(itemId)
                    if (narrated is AbsorbHttpClient.Result.Ok) return@withContext narrated
                    android.util.Log.i("AbsorbWearPlayback", "no narration for $itemId either: $narrated")
                }
            }
        }
        session
    }

    /** `POST /api/session/:id/sync`: report current position + listened time. */
    suspend fun sync(
        sessionId: String,
        currentTime: Double,
        timeListened: Double,
        duration: Double,
    ): AbsorbHttpClient.Result<Unit> = withContext(Dispatchers.IO) {
        bookOrbit?.let { return@withContext it.sync(sessionId, currentTime, timeListened) }
        if (AbsNarrationPlayback.isNarrationSession(sessionId)) {
            return@withContext narration?.sync(sessionId, currentTime, timeListened, duration, device())
                ?: AbsorbHttpClient.Result.Failed("Unknown session")
        }
        if (AbsLocalPlayback.isLocalSession(sessionId)) {
            return@withContext AbsLocalPlayback(client, device()).sync(sessionId, currentTime, timeListened, duration)
        }
        val payload = SyncRequest(
            currentTime = currentTime,
            timeListened = timeListened,
            duration = duration,
        )
        client.postForStatus(
            "/api/session/$sessionId/sync",
            json.encodeToString(SyncRequest.serializer(), payload),
        )
    }

    /** `POST /api/session/:id/close`, no payload. [currentTime] is the last
     *  position, which only BookOrbit saves on close (ABS took it from the
     *  sync just before). */
    suspend fun close(sessionId: String, currentTime: Double? = null): AbsorbHttpClient.Result<Unit> =
        withContext(Dispatchers.IO) {
            bookOrbit?.let { return@withContext it.close(sessionId, currentTime) }
            // Narration and local sessions have nothing to close; the sync
            // just before saved the spot.
            if (AbsNarrationPlayback.isNarrationSession(sessionId) || AbsLocalPlayback.isLocalSession(sessionId)) {
                return@withContext AbsorbHttpClient.Result.Ok(Unit)
            }
            client.postForStatus("/api/session/$sessionId/close")
        }

    /**
     * A book kept on the watch starts from its copy, with no server needed:
     * the saved tracks and chapters, and the newest position the server
     * will give within a few seconds, else the one the watch last had.
     * BookOrbit sessions keep their usual id so progress goes up the usual
     * way once the server is in reach; Audiobookshelf ones become local
     * sessions.
     */
    private suspend fun kept(itemId: String, episodeId: String?): AbsorbHttpClient.Result<PlaybackSession>? {
        val key = keepKey(itemId, episodeId)
        val book = DownloadStore[key] ?: return null
        if (book.serverUrl != creds.serverUrl || !BookDownloads.isDownloaded(key)) return null
        val serverPosition = withTimeoutOrNull(2_500) { serverPosition(itemId, episodeId, book.session) }
        val position = serverPosition ?: book.lastPositionSec ?: book.session.currentTime
        val id = when {
            creds.isBookOrbit -> "bo-$itemId-${System.currentTimeMillis()}"
            book.isNarration -> "narr-$itemId-${System.currentTimeMillis()}"
            else -> AbsLocalPlayback.newSessionId(itemId, episodeId)
        }
        android.util.Log.i(
            "AbsorbWearPlayback",
            "starting $key from the watch's copy at ${position.toLong()}s (server ${if (serverPosition == null) "not asked" else "asked"})",
        )
        return AbsorbHttpClient.Result.Ok(book.session.copy(id = id, currentTime = position.coerceIn(0.0, book.session.duration)))
    }

    /** The server's position for [itemId] in one cheap request, or null
     *  when it cannot say. The kept session's tracks turn BookOrbit's
     *  track-local state into a book-absolute time without the manifest. */
    private suspend fun serverPosition(itemId: String, episodeId: String?, session: PlaybackSession): Double? {
        if (!creds.isBookOrbit) {
            val path = if (episodeId != null) "/api/me/progress/$itemId/$episodeId" else "/api/me/progress/$itemId"
            val r = client.get(path, AbsProgressRow.serializer())
            return (r as? AbsorbHttpClient.Result.Ok)?.value?.currentTime
        }
        val first = session.audioTracks.firstOrNull()?.contentUrl.orEmpty()
        val narrated = first.contains("/media-overlay/file/") || first.startsWith("${AbsNarration.SCHEME}://")
        if (narrated) {
            // A narrated EPUB: the spot is kept on the EPUB file's progress row.
            val fileId = Regex("[?&]fileId=([^&]+)").find(first)?.groupValues?.get(1)
                ?: AbsNarration.bookOrbitFileOf(itemId)
                ?: return null
            val r = client.get("/api/v1/books/$itemId/progress", kotlinx.serialization.builtins.ListSerializer(com.barnabas.absorb.wear.api.BoProgressRow.serializer()))
            val rows = (r as? AbsorbHttpClient.Result.Ok)?.value ?: return null
            return rows.firstOrNull { it.fileId?.content == fileId }?.positionSeconds
        }
        val r = client.get("/api/v1/audiobooks/$itemId/playback-state", com.barnabas.absorb.wear.api.BoPlaybackState.serializer())
        val state = (r as? AbsorbHttpClient.Result.Ok)?.value ?: return null
        val assetId = state.assetId ?: return null
        val track = session.audioTracks.firstOrNull { it.contentUrl.contains("/assets/$assetId/") } ?: return null
        return track.startOffset + state.positionMs / 1000.0
    }

    @Serializable
    private data class AbsProgressRow(val currentTime: Double = 0.0)

    @Serializable
    data class DeviceInfo(
        val clientName: String = "AbsorbWear",
        val clientVersion: String = "0.2.0",
        val deviceId: String,
        val deviceName: String,
        val manufacturer: String,
        val model: String,
    ) {
        companion object {
            fun of(context: Context): DeviceInfo {
                val androidId = try {
                    Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
                } catch (e: Exception) {
                    null
                } ?: "unknown"
                return DeviceInfo(
                    deviceId = "absorb-wear-$androidId",
                    deviceName = "${Build.MANUFACTURER} ${Build.MODEL}",
                    manufacturer = Build.MANUFACTURER ?: "unknown",
                    model = Build.MODEL ?: "unknown",
                )
            }
        }
    }

    /** The device a session is reported for: the one the last start sent,
     *  else this watch, for a sync replayed after the process was reclaimed. */
    private fun device(): DeviceInfo = lastDevice ?: DeviceInfo.of(CredentialStore.applicationContext).also { lastDevice = it }

    // No startOffset field: the phone client only sends it when explicitly
    // seeding a position. Always sending 0.0 risks the server treating it as
    // "start from the beginning" instead of resuming saved progress.
    @Serializable
    private data class PlayRequest(
        val deviceInfo: DeviceInfo,
        val forceDirectPlay: Boolean = true,
        val forceTranscode: Boolean = false,
        val mediaPlayer: String = "exo-player",
        val supportedMimeTypes: List<String> = SUPPORTED_MIME_TYPES,
    )

    @Serializable
    private data class SyncRequest(
        val currentTime: Double,
        val timeListened: Double,
        val duration: Double,
    )

    private companion object {
        /** The device this watch last opened a session as, for local sessions
         *  synced from a fresh repository. */
        @Volatile
        var lastDevice: DeviceInfo? = null

        val SUPPORTED_MIME_TYPES = listOf(
            "audio/flac",
            "audio/mpeg",
            "audio/mp4",
            "audio/ogg",
            "audio/aac",
            "audio/x-m4a",
            "audio/x-m4b",
            "audio/opus",
            "audio/webm",
            "audio/wav",
            "audio/x-wav",
            "audio/x-matroska",
        )
    }
}
