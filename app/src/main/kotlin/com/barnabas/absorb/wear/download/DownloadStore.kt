package com.barnabas.absorb.wear.download

import android.content.Context
import android.content.SharedPreferences
import com.barnabas.absorb.wear.playback.PlaybackSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/**
 * A book kept on the watch: everything the player needs to start it with no
 * server in reach. [session] holds the tracks (stable URLs, or the cached
 * files of a narrated EPUB), chapters and length the way a fresh session
 * would; [lastPositionSec] is where the watch last had it, used when the
 * server cannot be asked.
 */
@Serializable
data class DownloadedBook(
    val itemId: String,
    val serverUrl: String,
    val backend: String,
    val title: String?,
    val author: String?,
    /** "url" for tracks fetched into the media cache, "narration" for a
     *  narrated EPUB whose audio files were pulled into the narration cache. */
    val kind: String,
    val session: PlaybackSession,
    /** Media3 download ids for a "url" book, one per track. */
    val downloadIds: List<String> = emptyList(),
    val addedAtMs: Long = 0L,
    val lastPositionSec: Double? = null,
    /** Set when this is one episode of the podcast [itemId]. */
    val episodeId: String? = null,
) {
    val isUrl: Boolean get() = kind == KIND_URL
    val isNarration: Boolean get() = kind == KIND_NARRATION
    val key: String get() = keepKey(itemId, episodeId)

    companion object {
        const val KIND_URL = "url"
        const val KIND_NARRATION = "narration"
    }
}

/** What a kept book is filed under: its id, or for a podcast episode the
 *  podcast's id and the episode's, so episodes of one show are kept apart. */
fun keepKey(itemId: String, episodeId: String?): String =
    if (episodeId == null) itemId else "$itemId~$episodeId"

/** The books kept on the watch, persisted as JSON in plain prefs, by
 *  [keepKey]. */
object DownloadStore {

    private const val PREFS_NAME = "absorb_wear_downloads"
    private const val KEY_BOOKS = "books"

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var prefs: SharedPreferences? = null

    private val _books = MutableStateFlow<Map<String, DownloadedBook>>(emptyMap())
    val books: StateFlow<Map<String, DownloadedBook>> = _books.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        prefs = context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs?.getString(KEY_BOOKS, null) ?: return
        val list = runCatching { json.decodeFromString(ListSerializer(DownloadedBook.serializer()), raw) }.getOrNull()
            ?: return
        _books.value = list.associateBy { it.key }
    }

    operator fun get(key: String): DownloadedBook? = _books.value[key]

    fun put(book: DownloadedBook) {
        _books.value = _books.value + (book.key to book)
        save()
    }

    fun remove(key: String) {
        if (!_books.value.containsKey(key)) return
        _books.value = _books.value - key
        save()
    }

    /** Remember where the watch last had the book filed under [key], for a
     *  start with no server. */
    fun setLastPosition(key: String, positionSec: Double) {
        val book = _books.value[key] ?: return
        if (book.lastPositionSec != null && kotlin.math.abs(book.lastPositionSec - positionSec) < 1.0) return
        put(book.copy(lastPositionSec = positionSec))
    }

    private fun save() {
        val list = _books.value.values.sortedByDescending { it.addedAtMs }
        prefs?.edit()?.putString(KEY_BOOKS, json.encodeToString(ListSerializer(DownloadedBook.serializer()), list))?.apply()
    }
}
