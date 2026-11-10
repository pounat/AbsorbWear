package com.barnabas.absorb.wear.playback

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.download.keepKey
import org.json.JSONObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * A book played from the watch's own copy on an Audiobookshelf server.
 *
 * There is no server session to sync against, so progress goes up as a
 * client-owned local session (`POST /api/session/local`), the same call
 * the phone client makes for its downloads: the server records the
 * listening time and moves the item's position unless it already has a
 * newer one. The session id carries the item and start time so it can be
 * rebuilt after the process was reclaimed, and the listening time is kept
 * as a running total because the server stores the latest value, not a sum.
 */
class AbsLocalPlayback(private val client: AbsorbHttpClient, private val device: PlaybackRepository.DeviceInfo) {

    private class Session(val itemId: String, val episodeId: String?, val startedAtMs: Long) {
        var listenedSec = 0.0
        var startTimeSec: Double? = null
    }

    /** [updatedAtMs] dates the session somewhere other than now: a narrated
     *  EPUB's session is dated just before its progress row, see
     *  [AbsNarrationPlayback]. */
    suspend fun sync(
        sessionId: String,
        currentTime: Double,
        timeListened: Double,
        duration: Double,
        updatedAtMs: Long? = null,
        playMethod: Int = PLAY_METHOD_LOCAL,
    ): Result<Unit> {
        val s = sessionFor(sessionId) ?: return Result.Failed("Unknown session")
        // Counted into the session only once the server has it: a failed
        // save hands the time back to the caller to send again.
        val listened = s.listenedSec + timeListened.coerceAtLeast(0.0)
        if (s.startTimeSec == null) s.startTimeSec = currentTime
        val started = Instant.ofEpochMilli(s.startedAtMs).atZone(ZoneId.systemDefault())
        val body = buildJsonObject {
            put("id", sessionId)
            put("libraryItemId", s.itemId)
            if (s.episodeId != null) {
                put("episodeId", s.episodeId)
                put("mediaType", "podcast")
            } else {
                put("mediaType", "book")
            }
            put("duration", duration)
            put("startTime", s.startTimeSec ?: currentTime)
            put("currentTime", currentTime)
            put("timeListening", listened.toLong())
            put("startedAt", s.startedAtMs)
            put("updatedAt", updatedAtMs ?: System.currentTimeMillis())
            put("date", DATE.format(started))
            put("dayOfWeek", DAY.format(started))
            put("mediaPlayer", "exo-player")
            put("playMethod", playMethod)
            putJsonObject("deviceInfo") {
                put("clientName", device.clientName)
                put("clientVersion", device.clientVersion)
                put("deviceId", device.deviceId)
                put("deviceName", device.deviceName)
                put("manufacturer", device.manufacturer)
                put("model", device.model)
            }
        }.toString()
        val r = client.postForStatus("/api/session/local", body)
        if (r is Result.Ok) {
            s.listenedSec = listened
            remember(sessionId, s)
        } else {
            Log.w(TAG, "local session for ${s.itemId} not saved: $r")
        }
        return r
    }

    private fun sessionFor(sessionId: String): Session? {
        sessions[sessionId]?.let { return it }
        val m = SESSION_ID.matchEntire(sessionId) ?: return null
        // An episode's id carries the podcast and the episode, see [newSessionId].
        val item = m.groupValues[1]
        val s = Session(item.substringBefore('~'), item.substringAfter('~', "").ifEmpty { null }, m.groupValues[2].toLong())
        // Rebuilt after the process was reclaimed: carry on from the total
        // the server already has, or a replayed save would shrink it.
        synchronized(totalsLock) {
            totals()?.optJSONObject(sessionId)?.let { saved ->
                s.listenedSec = saved.optDouble("l", 0.0)
                if (saved.has("s")) s.startTimeSec = saved.optDouble("s")
            }
        }
        return s.also { sessions[sessionId] = it }
    }

    companion object {
        private const val TAG = "AbsorbWearPlayback"
        private val SESSION_ID = Regex("^(?:local|narr)-(.+)-(\\d+)$")

        /** Audiobookshelf's play methods: the file as it is, or the watch's copy. */
        const val PLAY_METHOD_DIRECT = 0
        const val PLAY_METHOD_LOCAL = 3
        private val sessions = ConcurrentHashMap<String, Session>()
        private val DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.US)
        private val DAY = DateTimeFormatter.ofPattern("EEEE", Locale.US)

        /** Each session's total as the server has it, by session id. */
        private const val KEY_TOTALS = "local_session_totals"
        private const val KEEP_TOTALS_MS = 14L * 24 * 60 * 60 * 1000
        private val totalsLock = Any()
        @Volatile
        private var prefs: SharedPreferences? = null

        fun init(context: Context) {
            prefs = context.applicationContext.getSharedPreferences("absorb_wear_playback", Context.MODE_PRIVATE)
        }

        private fun totals(): JSONObject? {
            val raw = prefs?.getString(KEY_TOTALS, null) ?: return null
            return runCatching { JSONObject(raw) }.getOrNull()
        }

        private fun remember(sessionId: String, s: Session) {
            val p = prefs ?: return
            synchronized(totalsLock) {
                val all = totals() ?: JSONObject()
                all.put(sessionId, JSONObject().put("l", s.listenedSec).apply { s.startTimeSec?.let { put("s", it) } })
                val cutoff = System.currentTimeMillis() - KEEP_TOTALS_MS
                for (key in all.keys().asSequence().toList()) {
                    val started = SESSION_ID.matchEntire(key)?.groupValues?.get(2)?.toLongOrNull() ?: 0L
                    if (started < cutoff) all.remove(key)
                }
                p.edit().putString(KEY_TOTALS, all.toString()).apply()
            }
        }

        fun newSessionId(itemId: String, episodeId: String? = null): String =
            "local-${keepKey(itemId, episodeId)}-${System.currentTimeMillis()}"

        fun isLocalSession(sessionId: String): Boolean = sessionId.startsWith("local-")
    }
}
