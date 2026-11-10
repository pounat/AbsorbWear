package com.barnabas.absorb.wear.playback

import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.api.BoAppInfo
import com.barnabas.absorb.wear.api.BoBookDetail
import com.barnabas.absorb.wear.api.BoManifest
import com.barnabas.absorb.wear.api.BoPlaybackState
import com.barnabas.absorb.wear.api.BoPlaylist
import com.barnabas.absorb.wear.api.BoProgressRow
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.sync.WatchSessionLog
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * Playback sessions against a BookOrbit server, shaped like the ABS ones
 * [PlaybackManager] already drives (start, sync every 15s, close).
 *
 * BookOrbit has no server-side play session. Starting reads either the
 * audiobook manifest (the track order) and the saved playback state, or,
 * for an EPUB that reads itself aloud with no audiobook beside it, the
 * narration playlist and the reading spot kept on the EPUB file. Each sync
 * PUTs the new position and records listening time as a listening session
 * on the audio's file, the same calls the phone client makes so progress
 * and statistics line up across devices.
 *
 * Session state lives in a process-wide registry keyed by the session id,
 * because [PlaybackManager] builds a fresh repository per call. The id
 * encodes the book and start time so a session can be rebuilt after the
 * process was reclaimed mid-listen.
 */
class BookOrbitPlayback(private val client: AbsorbHttpClient, private val creds: Credentials) {

    private class Session(val bookId: String, val uuid: String, val startedAtMs: Long) {
        var manifest: BoManifest? = null
        var narration: NarrationOverlay? = null
        /** The file listening time is recorded on: the first audio asset,
         *  or the narrated EPUB. */
        var fileId: String? = null
        var revision: Int? = null
        var listenedSec = 0.0
        var startPercent: Double? = null
        var lastPercent: Double? = null
        var loaded = false
        var announced = false

        val totalSeconds: Double
            get() = manifest?.totalSeconds?.takeIf { it > 0 } ?: narration?.duration ?: 0.0
    }

    suspend fun start(bookId: String): Result<PlaybackSession> {
        val now = System.currentTimeMillis()
        val session = Session(bookId, UUID.randomUUID().toString(), now)
        when (val r = load(session)) {
            is Result.Ok -> Unit
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val manifest = session.manifest
        val narration = session.narration
        val started = when {
            manifest != null -> startAudiobook(session, manifest)
            narration != null -> startNarration(session, narration)
            else -> return Result.Failed("No audiobook on this book")
        }
        if (started is Result.Ok) sessions[started.value.id] = session
        return started
    }

    private suspend fun startAudiobook(session: Session, manifest: BoManifest): Result<PlaybackSession> {
        val bookId = session.bookId
        if (manifest.assets.isEmpty()) return Result.Failed("No audio tracks")
        val state = when (val r = playbackState(session)) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> {
                Log.w(TAG, "playback state for $bookId: ${r.message} (HTTP ${r.code})")
                return Result.Failed(r.message, r.code)
            }
        }
        Log.i(TAG, "playback state for $bookId: asset=${state?.assetId} positionMs=${state?.positionMs} revision=${state?.revision}")
        val current = if (state == null) 0.0 else absolutePosition(manifest, state)
        val total = manifest.totalSeconds
        if (total > 0) session.startPercent = (current / total * 100).coerceIn(0.0, 100.0)

        var acc = 0.0
        val tracks = manifest.assets.mapIndexed { i, a ->
            val durationSec = (a.durationMs ?: 0.0) / 1000.0
            val version = a.etag?.takeIf { it.isNotEmpty() }?.take(16)?.let { "?v=$it" } ?: ""
            AudioTrack(
                index = i + 1,
                contentUrl = "/api/v1/audiobooks/$bookId/assets/${a.assetId}/content$version",
                duration = durationSec,
                startOffset = acc,
                mimeType = MIME_TYPES[a.format?.lowercase()] ?: "audio/mpeg",
            ).also { acc += durationSec }
        }
        val chapters = manifest.chapters.mapIndexed { i, c ->
            Chapter(id = i, start = c.startMs / 1000.0, end = c.endMs / 1000.0, title = c.title)
        }
        return Result.Ok(
            PlaybackSession(
                id = "bo-$bookId-${session.startedAtMs}",
                libraryItemId = bookId,
                audioTracks = tracks,
                chapters = chapters,
                duration = if (total > 0) total else acc,
                currentTime = current,
            ),
        )
    }

    private suspend fun startNarration(session: Session, narration: NarrationOverlay): Result<PlaybackSession> {
        val bookId = session.bookId
        val fileId = session.fileId ?: return Result.Failed("No audiobook on this book")
        val current = narrationPosition(session, narration).coerceIn(0.0, narration.duration)
        if (narration.duration > 0) session.startPercent = (current / narration.duration * 100).coerceIn(0.0, 100.0)
        val fromEpub = !narration.tracks.all { narrationHrefServable(it.href) } && openEpub(bookId, fileId, narration)
        val tracks = narration.tracks.mapIndexed { i, t ->
            AudioTrack(
                index = i + 1,
                contentUrl = if (fromEpub) AbsNarration.trackUri(bookId, i) else narrationTrackPath(bookId, fileId, t.href),
                duration = t.duration,
                startOffset = t.start,
                mimeType = t.mediaType,
            )
        }
        Log.i(
            TAG,
            "narration session for $bookId: ${tracks.size} track(s), ${narration.clips.size} sentences, " +
                "${narration.duration.roundToInt()}s, from ${current.roundToInt()}s" +
                if (fromEpub) ", played out of the EPUB" else "",
        )
        return Result.Ok(
            PlaybackSession(
                id = "bo-$bookId-${session.startedAtMs}",
                libraryItemId = bookId,
                audioTracks = tracks,
                chapters = narration.chapters(),
                duration = narration.duration,
                currentTime = current,
            ),
        )
    }

    /** Open the EPUB to play the audio from, for a book BookOrbit can't
     *  serve. False when it can't be read, leaving the book to BookOrbit. */
    private suspend fun openEpub(bookId: String, fileId: String, narration: NarrationOverlay): Boolean = try {
        AbsNarration.prepareBookOrbit(creds, bookId, fileId, narration)
        true
    } catch (e: Exception) {
        Log.w(TAG, "narration for $bookId could not be read from its EPUB", e)
        false
    }

    suspend fun sync(
        sessionId: String,
        currentTime: Double,
        timeListened: Double,
    ): Result<Unit> {
        val s = sessionFor(sessionId) ?: return Result.Failed("Unknown session")
        if (!s.loaded) load(s)
        val saved = putPosition(s, currentTime)
        // Counted only once the server has the spot: a failed save hands the
        // time back to the caller, which sends it again with the next one.
        if (saved is Result.Ok && timeListened > 0) s.listenedSec += timeListened
        val total = s.totalSeconds
        if (total > 0) {
            s.lastPercent = (currentTime / total * 100).coerceIn(0.0, 100.0)
            if (s.startPercent == null) s.startPercent = s.lastPercent
        }
        checkpoint(s)
        return saved
    }

    suspend fun close(sessionId: String, currentTime: Double?): Result<Unit> {
        val s = sessions.remove(sessionId) ?: sessionFor(sessionId) ?: return Result.Ok(Unit)
        sessions.remove(sessionId)
        if (!s.loaded) load(s)
        if (currentTime != null) putPosition(s, currentTime)
        checkpoint(s)
        return Result.Ok(Unit)
    }

    /** Read what the book plays with: its audiobook manifest, or failing
     *  that its narrated EPUB. */
    private suspend fun load(s: Session): Result<Unit> {
        if (s.loaded) return Result.Ok(Unit)
        when (val r = client.get("/api/v1/audiobooks/${s.bookId}/manifest", BoManifest.serializer())) {
            is Result.Ok -> {
                s.manifest = r.value
                s.fileId = r.value.assets.firstOrNull()?.fileId?.content
                s.loaded = true
                Log.i(TAG, "manifest for ${s.bookId}: ${r.value.assets.size} asset(s), ${r.value.chapters.size} chapter(s), total=${r.value.totalSeconds}s")
                return Result.Ok(Unit)
            }
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> {
                Log.i(TAG, "manifest for ${s.bookId}: ${r.message} (HTTP ${r.code}); looking for a narrated EPUB")
                if (r.code != 404) return Result.Failed(r.message, r.code)
            }
        }
        val detail = when (val r = client.get("/api/v1/books/${s.bookId}", BoBookDetail.serializer())) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> {
                Log.w(TAG, "book ${s.bookId}: ${r.message} (HTTP ${r.code})")
                return Result.Failed(r.message, r.code)
            }
        }
        val epub = detail.files.firstOrNull { it.isNarratedEpub }
        val fileId = epub?.id?.content
        if (fileId == null) {
            Log.w(TAG, "book ${s.bookId} has no audiobook and no narrated EPUB")
            s.loaded = true
            return Result.Ok(Unit)
        }
        val playlist = when (val r = client.get("/api/v1/epub/${s.bookId}/media-overlay?fileId=$fileId", BoPlaylist.serializer())) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> {
                Log.w(TAG, "narration playlist for ${s.bookId}: ${r.message} (HTTP ${r.code})")
                return Result.Failed(r.message, r.code)
            }
        }
        val overlay = NarrationOverlay.fromBookOrbit(playlist)
        if (overlay == null || overlay.isEmpty) {
            Log.w(TAG, "narration playlist for ${s.bookId} has no narrated sentences")
        } else {
            s.narration = overlay
            s.fileId = fileId
        }
        s.loaded = true
        return Result.Ok(Unit)
    }

    /** The saved position, or null when the book was never played. Remembers
     *  the revision the next PUT has to build on. */
    private suspend fun playbackState(s: Session): Result<BoPlaybackState?> =
        when (val r = client.get("/api/v1/audiobooks/${s.bookId}/playback-state", BoPlaybackState.serializer())) {
            is Result.Ok -> {
                s.revision = r.value.revision ?: 0
                Result.Ok(r.value)
            }
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> if (r.code == 404) {
                s.revision = 0
                Result.Ok(null)
            } else {
                Result.Failed(r.message, r.code)
            }
        }

    /** Where BookOrbit's own read along left this EPUB: seconds when it has
     *  them, else the sentence it reached placed on the narration. */
    private suspend fun narrationPosition(s: Session, narration: NarrationOverlay): Double {
        val fileId = s.fileId ?: return 0.0
        val rows = when (val r = client.get("/api/v1/books/${s.bookId}/progress", ListSerializer(BoProgressRow.serializer()))) {
            is Result.Ok -> r.value
            else -> {
                Log.w(TAG, "progress rows for ${s.bookId}: $r")
                return 0.0
            }
        }
        val row = rows.firstOrNull { it.fileId?.content == fileId } ?: return 0.0
        row.positionSeconds?.let { return it }
        val fragment = row.mediaOverlayFragment?.takeIf { it.isNotEmpty() } ?: return 0.0
        val clip = narration.clipByFragment(fragment, row.mediaOverlaySectionIndex) ?: return 0.0
        return narration.startOf(clip)
    }

    private suspend fun putPosition(s: Session, absoluteSec: Double): Result<Unit> {
        s.manifest?.let { return putAudiobookPosition(s, it, absoluteSec) }
        s.narration?.let { return putNarrationPosition(s, it, absoluteSec) }
        return Result.Failed("No audiobook on this book")
    }

    /** Save a book-absolute position, retrying on a revision conflict. When
     *  the server already holds a newer position (another device moved on)
     *  ours is dropped rather than rolling theirs back. */
    private suspend fun putAudiobookPosition(s: Session, manifest: BoManifest, absoluteSec: Double): Result<Unit> {
        val pos = trackPosition(manifest, absoluteSec) ?: return Result.Failed("No audio tracks")
        val nowMs = System.currentTimeMillis()
        val operationId = UUID.randomUUID().toString()
        repeat(3) {
            var base = s.revision
            if (base == null) {
                val state = when (val r = playbackState(s)) {
                    is Result.Ok -> r.value
                    is Result.Unauthorized -> return Result.Unauthorized(r.code)
                    is Result.Failed -> return Result.Failed(r.message, r.code)
                }
                val serverAt = state?.capturedAt?.let(::parseIso)
                if (serverAt != null && serverAt > nowMs) return Result.Ok(Unit)
                base = s.revision ?: 0
            }
            val body = buildJsonObject {
                put("assetId", pos.first)
                put("positionMs", pos.second)
                put("capturedAt", iso(nowMs))
                put("operationId", operationId)
                put("baseRevision", base)
                manifest.revision?.let { put("manifestRevision", it) }
            }.toString()
            when (val r = client.put("/api/v1/audiobooks/${s.bookId}/playback-state", body, BoPlaybackState.serializer())) {
                is Result.Ok -> {
                    r.value.revision?.let { s.revision = it }
                    return Result.Ok(Unit)
                }
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> if (r.code == 409) s.revision = null else return Result.Failed(r.message, r.code)
            }
        }
        return Result.Failed("Position conflict", 409)
    }

    /** Saved where BookOrbit's own read along keeps its place: the narration
     *  position on the EPUB, with the sentence it reached. It goes through
     *  the route BookOrbit's own web reader saves by; BookOrbit 3.7 lets
     *  only its iOS app at the per-file route, and a server from before 3.6
     *  has only that one, so it is tried next. */
    private suspend fun putNarrationPosition(s: Session, narration: NarrationOverlay, seconds: Double): Result<Unit> {
        val fileId = s.fileId ?: return Result.Failed("No audiobook on this book")
        val total = narration.duration
        val clip = narration.clipAt(seconds)
        val percentage = if (total > 0) (seconds / total * 100).coerceIn(0.0, 100.0) else 0.0
        val position = max(0.0, seconds)
        val id = fileId.toIntOrNull()
        if (id != null) {
            val grouped = buildJsonObject {
                putJsonArray("writes") {
                    addJsonObject {
                        put("fileId", id)
                        put("kind", "narration")
                        put("percentage", percentage)
                        putJsonObject("narration") {
                            put("seconds", position)
                            clip?.let {
                                put("fragment", it.textSrc)
                                put("sectionIndex", it.section)
                            }
                        }
                    }
                }
            }.toString()
            val r = client.putForStatus("/api/v1/reading-progress", grouped)
            if (r !is Result.Failed || r.code != 404) {
                if (r !is Result.Ok) Log.w(TAG, "narration position for ${s.bookId} not saved: $r")
                return r
            }
        }
        val body = buildJsonObject {
            put("percentage", percentage)
            put("positionSeconds", position)
            clip?.let {
                put("mediaOverlayFragment", it.textSrc)
                put("mediaOverlaySectionIndex", it.section)
            }
            put("source", "narration")
        }.toString()
        val r = client.postForStatus("/api/v1/books/files/$fileId/progress", body)
        if (r !is Result.Ok) Log.w(TAG, "narration position for ${s.bookId} not saved: $r")
        return r
    }

    /** Record the listening time so far as one session on the audio's file.
     *  The same session id is sent every time, so the server updates the one
     *  row rather than adding another. Best effort. */
    private suspend fun checkpoint(s: Session) {
        val seconds = s.listenedSec.roundToInt()
        if (seconds < 10) return
        val fileId = s.fileId ?: return
        val endMs = System.currentTimeMillis()
        val span = ((endMs - s.startedAtMs) / 1000).toInt()
        val start = s.startPercent
        val last = s.lastPercent
        val body = buildJsonObject {
            put("sessionId", s.uuid)
            put("startedAt", iso(s.startedAtMs))
            put("endedAt", iso(endMs))
            put("durationSeconds", if (span < seconds) max(span, 0) else seconds)
            if (start != null && last != null) put("progressDelta", ((last - start) * 10_000).roundToLong() / 10_000.0)
            if (last != null) put("endProgress", last.coerceIn(0.0, 100.0))
            put("sessionType", "listen")
            put("source", "android")
            if (supportsClientName()) put("clientName", "Absorb")
        }.toString()
        val r = client.postForStatus("/api/v1/books/files/$fileId/sessions", body)
        if (r !is Result.Ok) {
            Log.w(TAG, "Listening session checkpoint failed: $r")
        } else if (!s.announced) {
            s.announced = true
            WatchSessionLog.record(s.bookId, iso(s.startedAtMs))
        }
    }

    private suspend fun supportsClientName(): Boolean {
        val key = client.serverKey
        features[key]?.let { return it.contains("client-name") }
        val r = client.get("/api/v1/app-info", BoAppInfo.serializer())
        if (r !is Result.Ok) return false
        val set = r.value.features.toSet()
        features[key] = set
        return set.contains("client-name")
    }

    private fun sessionFor(sessionId: String): Session? {
        sessions[sessionId]?.let { return it }
        val m = SESSION_ID.matchEntire(sessionId) ?: return null
        return Session(m.groupValues[1], UUID.randomUUID().toString(), m.groupValues[2].toLong())
            .also { sessions[sessionId] = it }
    }

    companion object {
        private const val TAG = "AbsorbWearPlayback"
        private val SESSION_ID = Regex("^bo-(\\d+)-(\\d+)$")
        private val sessions = ConcurrentHashMap<String, Session>()
        private val features = ConcurrentHashMap<String, Set<String>>()

        private val MIME_TYPES = mapOf(
            "m4b" to "audio/mp4",
            "m4a" to "audio/mp4",
            "mp3" to "audio/mpeg",
            "opus" to "audio/ogg",
            "ogg" to "audio/ogg",
            "flac" to "audio/flac",
        )

        private val ISO_MILLIS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
            .withZone(ZoneOffset.UTC)

        /** ISO-8601 in UTC to the millisecond, the precision the server keeps. */
        internal fun iso(epochMs: Long): String = ISO_MILLIS.format(Instant.ofEpochMilli(epochMs))

        internal fun parseIso(text: String): Long? = try {
            OffsetDateTime.parse(text).toInstant().toEpochMilli()
        } catch (_: Exception) {
            try {
                Instant.parse(text).toEpochMilli()
            } catch (_: Exception) {
                null
            }
        }

        // BookOrbit undoes a requested name's escapes with decodeURI, which
        // leaves these alone, then looks the name up in the EPUB as it is.
        private val UNSERVABLE_HREF = Regex("%(23|24|26|2B|2C|2F|3A|3B|3D|3F|40)", RegexOption.IGNORE_CASE)

        /** False when BookOrbit's playlist keeps an escape in a file's name
         *  (a comma as %2C) that its stream route never finds the file by. */
        internal fun narrationHrefServable(href: String): Boolean = !UNSERVABLE_HREF.containsMatchIn(href)

        /** The stream route for one audio file inside a narrated EPUB. */
        internal fun narrationTrackPath(bookId: String, fileId: String, href: String): String {
            val path = href.split('/').joinToString("/") { URLEncoder.encode(it, "UTF-8").replace("+", "%20") }
            return "/api/v1/epub/$bookId/media-overlay/file/$path?fileId=$fileId"
        }

        /** Book-absolute seconds for a track-local playback state. */
        internal fun absolutePosition(manifest: BoManifest, state: BoPlaybackState): Double {
            val local = state.positionMs / 1000.0
            var elapsed = 0.0
            for (a in manifest.assets) {
                if (a.assetId == state.assetId) return elapsed + local
                elapsed += (a.durationMs ?: 0.0) / 1000.0
            }
            return local
        }

        /** The asset and track-local milliseconds for a book-absolute position. */
        internal fun trackPosition(manifest: BoManifest, absoluteSec: Double): Pair<String, Long>? {
            val assets = manifest.assets
            if (assets.isEmpty()) return null
            var remaining = (absoluteSec * 1000).roundToLong().coerceAtLeast(0)
            for ((i, a) in assets.withIndex()) {
                val dur = a.durationMs?.roundToLong()
                val last = i == assets.lastIndex
                if (dur == null || last || remaining < dur) {
                    val pos = if (dur != null && remaining > dur) dur else remaining
                    return a.assetId to pos
                }
                remaining -= dur
            }
            return null
        }
    }
}
