package com.barnabas.absorb.wear.api

import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.net.URLEncoder

/**
 * The library beyond Continue Listening: the server's home-row shelves,
 * a search over the whole library, and every book's saved progress in one
 * call, for the cards on the home screen.
 *
 * Audiobookshelf: `/api/libraries` -> each library's `/personalized`
 * shelves, `/api/libraries/:id/search`, and `/api/me` for all progress.
 * BookOrbit: the dashboard scrollers it has, a relevance search on its
 * first library, and no bulk progress (its progress is per book).
 */
class LibraryRepository(private val creds: Credentials) {

    private val client = AbsorbHttpClient(creds)

    data class Shelf(val id: String, val label: String, val items: List<InProgressItem>)

    data class ProgressRow(val currentTimeSec: Double, val durationSec: Double, val fraction: Float, val finished: Boolean)

    /** A library the watch can show: audiobooks or podcasts, never an
     *  ebook-only one. */
    data class Library(val id: String, val name: String)

    /** The libraries worth switching between. Audiobookshelf's ebook-only
     *  libraries (no audio at all in their stats) are left out; BookOrbit's
     *  podcast and ebook libraries likewise, and its import dock is not a
     *  library it lists. */
    suspend fun libraries(): Result<List<Library>> {
        if (creds.isBookOrbit) {
            val element = when (val r = client.get("/api/v1/libraries", JsonElement.serializer())) {
                is Result.Ok -> r.value
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> return Result.Failed(r.message, r.code)
            }
            val list = (element as? JsonArray)
                ?: (element as? JsonObject)?.let { it["items"] ?: it["libraries"] } as? JsonArray
                ?: return Result.Failed("No libraries")
            return Result.Ok(
                list.mapNotNull { e ->
                    val obj = e as? JsonObject ?: return@mapNotNull null
                    val type = obj["type"]?.jsonPrimitive?.content
                    if (type == "podcasts" || type == "ebooks") return@mapNotNull null
                    val id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null
                    Library(id, obj["name"]?.jsonPrimitive?.content ?: "Library")
                },
            )
        }
        val libs = when (val r = absLibraries()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val out = ArrayList<Library>()
        for (lib in libs) {
            if (lib.mediaType == "book") {
                // An ebook-only library has no audio in its stats.
                val stats = client.get("/api/libraries/${lib.id}/stats", JsonElement.serializer())
                val duration = ((stats as? Result.Ok)?.value as? JsonObject)?.get("totalDuration")?.jsonPrimitive?.content?.toDoubleOrNull()
                if (duration != null && duration <= 0.0) continue
            }
            out += Library(lib.id, lib.name.ifBlank { "Library" })
        }
        return Result.Ok(out)
    }

    /** The library the watch is looking at, remembered per server. */
    private fun selectedLibraryId(): String? = selectedLibrary.value?.takeIf { it.serverUrl == creds.serverUrl }?.id

    /** The Audiobookshelf libraries a call should cover: the chosen one,
     *  else every audio library. */
    private suspend fun absScope(): Result<List<AbsLibrary>> {
        val all = when (val r = absLibraries()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val chosen = selectedLibraryId()
        val scoped = all.filter { it.id == chosen }
        return Result.Ok(if (scoped.isNotEmpty()) scoped else all)
    }

    suspend fun shelves(): Result<List<Shelf>> {
        val r = if (creds.isBookOrbit) bookOrbitShelves() else absShelves()
        when (r) {
            is Result.Ok -> Log.i(TAG, "shelves (${creds.backend}): ${r.value.size}, ${r.value.sumOf { it.items.size }} item(s)")
            is Result.Unauthorized -> Log.w(TAG, "shelves: HTTP ${r.code}")
            is Result.Failed -> Log.w(TAG, "shelves: ${r.message} (HTTP ${r.code})")
        }
        return r
    }

    suspend fun search(query: String): Result<List<InProgressItem>> {
        val q = query.trim()
        if (q.isEmpty()) return Result.Ok(emptyList())
        return if (creds.isBookOrbit) bookOrbitSearch(q) else absSearch(q)
    }

    /** The user's playlists and the library's collections, each as a shelf,
     *  for the lists page. BookOrbit has collections only. */
    suspend fun lists(): Result<List<Shelf>> {
        val r = if (creds.isBookOrbit) bookOrbitLists() else absLists()
        when (r) {
            is Result.Ok -> Log.i(TAG, "lists (${creds.backend}): ${r.value.size}")
            is Result.Unauthorized -> Log.w(TAG, "lists: HTTP ${r.code}")
            is Result.Failed -> Log.w(TAG, "lists: ${r.message} (HTTP ${r.code})")
        }
        return r
    }

    private suspend fun absLists(): Result<List<Shelf>> {
        val out = ArrayList<Shelf>()
        when (val r = client.get("/api/playlists", JsonElement.serializer())) {
            is Result.Ok -> {
                val playlists = (r.value as? JsonObject)?.get("playlists") as? JsonArray ?: JsonArray(emptyList())
                for (p in playlists) {
                    val obj = p as? JsonObject ?: continue
                    val name = obj["name"]?.jsonPrimitive?.content ?: continue
                    val id = obj["id"]?.jsonPrimitive?.content ?: continue
                    val items = (obj["items"] as? JsonArray).orEmpty().mapNotNull { e ->
                        val li = (e as? JsonObject)?.get("libraryItem") ?: return@mapNotNull null
                        runCatching { json.decodeFromJsonElement(InProgressItem.serializer(), li) }.getOrNull()
                    }
                    out += Shelf("playlist:$id", name, items)
                }
            }
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> Log.w(TAG, "playlists: ${r.message} (HTTP ${r.code})")
        }
        val libraries = when (val r = absScope()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return if (out.isEmpty()) Result.Failed(r.message, r.code) else Result.Ok(out)
        }
        for (lib in libraries.take(4)) {
            val r = client.get("/api/libraries/${lib.id}/collections?minified=1", JsonElement.serializer())
            val results = when (r) {
                is Result.Ok -> (r.value as? JsonObject)?.get("results") as? JsonArray ?: continue
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> continue
            }
            for (c in results) {
                val obj = c as? JsonObject ?: continue
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val id = obj["id"]?.jsonPrimitive?.content ?: continue
                val books = (obj["books"] as? JsonArray).orEmpty().mapNotNull { e ->
                    runCatching { json.decodeFromJsonElement(InProgressItem.serializer(), e) }.getOrNull()
                }
                out += Shelf("collection:$id", name, books)
            }
        }
        return Result.Ok(out)
    }

    private suspend fun bookOrbitLists(): Result<List<Shelf>> {
        val collections = when (val r = client.get("/api/v1/collections", JsonElement.serializer())) {
            is Result.Ok -> (r.value as? JsonArray) ?: (r.value as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList())
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val out = ArrayList<Shelf>()
        for (c in collections.take(20)) {
            val obj = c as? JsonObject ?: continue
            val id = obj["id"]?.jsonPrimitive?.content ?: continue
            val name = obj["name"]?.jsonPrimitive?.content ?: continue
            val books = when (val r = client.get("/api/v1/collections/$id/books?page=0&size=50", JsonElement.serializer())) {
                is Result.Ok -> ((r.value as? JsonObject)?.get("items") as? JsonArray ?: (r.value as? JsonArray)).orEmpty()
                    .mapNotNull { e -> runCatching { json.decodeFromJsonElement(BoCard.serializer(), e) }.getOrNull()?.asItem() }
                else -> emptyList()
            }
            out += Shelf("collection:$id", name, books)
        }
        return Result.Ok(out)
    }

    /** Every book's saved spot, keyed by item id (`item-episode` for a podcast
     *  episode). Empty on BookOrbit, which keeps progress per book. */
    suspend fun progress(): Result<Map<String, ProgressRow>> {
        if (creds.isBookOrbit) return Result.Ok(emptyMap())
        return when (val r = client.get("/api/me", AbsMe.serializer())) {
            is Result.Ok -> Result.Ok(
                r.value.mediaProgress.associate { p ->
                    val key = if (p.episodeId != null) "${p.libraryItemId}-${p.episodeId}" else p.libraryItemId
                    key to ProgressRow(p.currentTime, p.duration, p.progress.toFloat().coerceIn(0f, 1f), p.isFinished)
                },
            )
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private suspend fun absLibraries(): Result<List<AbsLibrary>> =
        when (val r = client.get("/api/libraries", AbsLibraries.serializer())) {
            is Result.Ok -> Result.Ok(r.value.libraries.filter { it.mediaType == "book" || it.mediaType == "podcast" })
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }

    private suspend fun absShelves(): Result<List<Shelf>> {
        val libraries = when (val r = absScope()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val out = ArrayList<Shelf>()
        for (lib in libraries.take(4)) {
            val r = client.get("/api/libraries/${lib.id}/personalized?minified=1&limit=12", JsonElement.serializer())
            val shelves = when (r) {
                is Result.Ok -> r.value
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> if (out.isEmpty() && lib === libraries.last()) return Result.Failed(r.message, r.code) else continue
            }
            val list = (shelves as? JsonArray) ?: (shelves as? JsonObject)?.get("shelves") as? JsonArray ?: continue
            for (s in list) {
                val obj = s as? JsonObject ?: continue
                val type = obj["type"]?.jsonPrimitive?.content ?: continue
                if (type != "book" && type != "episode" && type != "podcast") continue
                val shelfId = obj["id"]?.jsonPrimitive?.content ?: continue
                // A podcast library's front page is its new episodes and the
                // shows added lately; a book library keeps its short list.
                val wanted = shelfId in FRONT_SHELVES || shelfId == "newest-episodes" ||
                    (shelfId == "recently-added" && type == "podcast")
                if (!wanted) continue
                val entities = obj["entities"] as? JsonArray ?: continue
                val items = entities.mapNotNull { e -> runCatching { json.decodeFromJsonElement(InProgressItem.serializer(), e) }.getOrNull() }
                if (items.isEmpty()) continue
                val label = obj["label"]?.jsonPrimitive?.content ?: obj["id"]?.jsonPrimitive?.content ?: "Shelf"
                val shown = if (libraries.size > 1) "$label · ${lib.name}" else label
                out += Shelf("${lib.id}:${obj["id"]?.jsonPrimitive?.content}", shown, items)
            }
        }
        return Result.Ok(out)
    }

    private suspend fun absSearch(q: String): Result<List<InProgressItem>> {
        val libraries = when (val r = absScope()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val encoded = URLEncoder.encode(q, "UTF-8")
        val out = ArrayList<InProgressItem>()
        for (lib in libraries.take(4)) {
            val r = client.get("/api/libraries/${lib.id}/search?q=$encoded&limit=20", JsonElement.serializer())
            val obj = when (r) {
                is Result.Ok -> r.value as? JsonObject ?: continue
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> if (out.isEmpty() && lib === libraries.last()) return Result.Failed(r.message, r.code) else continue
            }
            for (group in listOf("book", "podcast")) {
                val hits = obj[group] as? JsonArray ?: continue
                for (h in hits) {
                    val li = (h as? JsonObject)?.get("libraryItem") ?: continue
                    runCatching { json.decodeFromJsonElement(InProgressItem.serializer(), li) }.getOrNull()?.let { out += it }
                }
            }
        }
        return Result.Ok(out.distinctBy { it.id })
    }

    private suspend fun bookOrbitLibraryId(): Result<String> {
        selectedLibraryId()?.let { return Result.Ok(it) }
        val r = client.get("/api/v1/libraries", JsonElement.serializer())
        val element = when (r) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val list = (element as? JsonArray)
            ?: (element as? JsonObject)?.let { it["items"] ?: it["libraries"] } as? JsonArray
            ?: return Result.Failed("No libraries")
        val first = list.firstOrNull() as? JsonObject ?: return Result.Failed("No libraries")
        val id = first["id"]?.jsonPrimitive?.content ?: return Result.Failed("No libraries")
        return Result.Ok(id)
    }

    private suspend fun bookOrbitShelves(): Result<List<Shelf>> {
        val out = ArrayList<Shelf>()
        var lastFailure: Result.Failed<List<Shelf>>? = null
        for ((type, label) in BO_SHELVES) {
            when (val r = client.get("/api/v1/dashboard/scrollers/$type?limit=20", BoScroller.serializer())) {
                is Result.Ok -> if (r.value.books.isNotEmpty()) out += Shelf(type, label, r.value.books.map { it.asItem() })
                is Result.Unauthorized -> return Result.Unauthorized(r.code)
                is Result.Failed -> lastFailure = Result.Failed(r.message, r.code)
            }
        }
        val failure = lastFailure
        if (out.isEmpty() && failure != null) return failure
        return Result.Ok(out)
    }

    private suspend fun bookOrbitSearch(q: String): Result<List<InProgressItem>> {
        val lib = when (val r = bookOrbitLibraryId()) {
            is Result.Ok -> r.value
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val body = buildJsonObject {
            put("q", q.take(200))
            putJsonArray("sort") { add(buildJsonObject { put("field", "relevance"); put("dir", "desc") }) }
            putJsonObject("pagination") { put("page", 0); put("size", 20) }
        }.toString()
        return when (val r = client.post("/api/v1/libraries/$lib/books", body, JsonElement.serializer())) {
            is Result.Ok -> {
                val items = (r.value as? JsonObject)?.get("items") as? JsonArray ?: JsonArray(emptyList())
                Result.Ok(
                    items.mapNotNull { e -> runCatching { json.decodeFromJsonElement(BoCard.serializer(), e) }.getOrNull()?.asItem() },
                )
            }
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private fun BoCard.asItem() = InProgressItem(
        id = itemId,
        media = InProgressItem.Media(metadata = InProgressItem.Metadata(title = title, authorName = authorName)),
    )

    @Serializable
    private data class AbsLibraries(val libraries: List<AbsLibrary> = emptyList())

    @Serializable
    private data class AbsLibrary(val id: String, val name: String = "", val mediaType: String = "book")

    @Serializable
    private data class AbsMe(val mediaProgress: List<AbsProgress> = emptyList())

    @Serializable
    private data class AbsProgress(
        val libraryItemId: String,
        val episodeId: String? = null,
        val currentTime: Double = 0.0,
        val duration: Double = 0.0,
        val progress: Double = 0.0,
        val isFinished: Boolean = false,
    )

    companion object {
        private const val TAG = "AbsorbWearAuth"
        private val json = Json { ignoreUnknownKeys = true }
        private val BO_SHELVES = listOf(
            "continue-listening" to "Continue listening",
            "up-next-in-series" to "Continue series",
        )

        /** The Audiobookshelf shelves shown on the watch's front page. */
        private val FRONT_SHELVES = setOf("continue-listening", "continue-series", "discover")

        /** What the home screen last loaded, for the browse and shelf
         *  screens to read without fetching again. */
        val shelves = MutableStateFlow<List<Shelf>?>(null)
        val lists = MutableStateFlow<List<Shelf>?>(null)
        val libraries = MutableStateFlow<List<Library>?>(null)

        /** The chosen library, remembered per server in the playback prefs. */
        data class Selected(val serverUrl: String, val id: String, val name: String)
        val selectedLibrary = MutableStateFlow<Selected?>(null)

        private const val PREFS = "absorb_wear_playback"

        fun restoreSelection(context: android.content.Context) {
            val p = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            val server = p.getString("library_server", null) ?: return
            val id = p.getString("library_id", null) ?: return
            selectedLibrary.value = Selected(server, id, p.getString("library_name", null) ?: "Library")
        }

        fun select(context: android.content.Context, serverUrl: String, library: Library) {
            selectedLibrary.value = Selected(serverUrl, library.id, library.name)
            context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit()
                .putString("library_server", serverUrl)
                .putString("library_id", library.id)
                .putString("library_name", library.name)
                .apply()
            // What was loaded belonged to the old library.
            shelves.value = null
            lists.value = null
        }
        val progress = MutableStateFlow<Map<String, ProgressRow>>(emptyMap())
    }
}
