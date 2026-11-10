package com.barnabas.absorb.wear.api

import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/** A saved spot in a book. [id] is BookOrbit's; Audiobookshelf keys by time. */
data class Bookmark(
    val timeSec: Double,
    val title: String,
    val createdAtMs: Long,
    val id: String? = null,
)

/**
 * A book's bookmarks on the server, whichever server it is. The phone app
 * keeps them on the server too, so both devices see the same list, and a
 * title with a note is stored as "title - note" the way the phone does.
 */
class BookmarkRepository(private val creds: Credentials) {

    private val client = AbsorbHttpClient(creds)

    suspend fun list(itemId: String): Result<List<Bookmark>> {
        val r = if (creds.isBookOrbit) bookOrbitList(itemId) else absList(itemId)
        if (r is Result.Ok) cache.value = cache.value + (itemId to r.value)
        return r
    }

    suspend fun add(itemId: String, timeSec: Double, title: String): Result<Bookmark> {
        val r = if (creds.isBookOrbit) bookOrbitAdd(itemId, timeSec, title) else absAdd(itemId, timeSec, title)
        when (r) {
            is Result.Ok -> {
                Log.i(TAG, "bookmark saved at ${timeSec.toLong()}s in $itemId")
                cache.value = cache.value + (itemId to (listOf(r.value) + cache.value[itemId].orEmpty()))
            }
            is Result.Unauthorized -> Log.w(TAG, "bookmark: HTTP ${r.code}")
            is Result.Failed -> Log.w(TAG, "bookmark: ${r.message} (HTTP ${r.code})")
        }
        return r
    }

    suspend fun delete(itemId: String, bookmark: Bookmark): Result<Unit> {
        val r = if (creds.isBookOrbit) {
            val id = bookmark.id ?: return Result.Failed("No id")
            client.deleteForStatus("/api/v1/audiobooks/$itemId/bookmarks/$id")
        } else {
            client.deleteForStatus("/api/me/item/$itemId/bookmark/${bookmark.timeSec.toLong()}")
        }
        if (r is Result.Ok) cache.value = cache.value + (itemId to cache.value[itemId].orEmpty().filter { it !== bookmark })
        return r
    }

    private suspend fun absList(itemId: String): Result<List<Bookmark>> {
        return when (val r = client.get("/api/me", JsonElement.serializer())) {
            is Result.Ok -> {
                val all = (r.value as? JsonObject)?.get("bookmarks") as? JsonArray ?: JsonArray(emptyList())
                Result.Ok(
                    all.mapNotNull { e ->
                        val obj = e as? JsonObject ?: return@mapNotNull null
                        if (obj["libraryItemId"]?.jsonPrimitive?.content != itemId) return@mapNotNull null
                        Bookmark(
                            timeSec = obj["time"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                            title = obj["title"]?.jsonPrimitive?.content ?: "Bookmark",
                            createdAtMs = obj["createdAt"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L,
                        )
                    }.sortedByDescending { it.createdAtMs },
                )
            }
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private suspend fun absAdd(itemId: String, timeSec: Double, title: String): Result<Bookmark> {
        val body = buildJsonObject {
            put("time", timeSec.toLong())
            put("title", title)
        }.toString()
        return when (val r = client.post("/api/me/item/$itemId/bookmark", body, JsonElement.serializer())) {
            is Result.Ok -> {
                val obj = r.value as? JsonObject
                Result.Ok(
                    Bookmark(
                        timeSec = obj?.get("time")?.jsonPrimitive?.content?.toDoubleOrNull() ?: timeSec,
                        title = obj?.get("title")?.jsonPrimitive?.content ?: title,
                        createdAtMs = obj?.get("createdAt")?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                            ?: System.currentTimeMillis(),
                    ),
                )
            }
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private suspend fun bookOrbitList(itemId: String): Result<List<Bookmark>> {
        return when (val r = client.get("/api/v1/audiobooks/$itemId/bookmarks", JsonElement.serializer())) {
            is Result.Ok -> Result.Ok((r.value as? JsonArray).orEmpty().mapNotNull { bookOrbitBookmark(it) }.sortedByDescending { it.createdAtMs })
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            // A book without any gives 404 on some versions.
            is Result.Failed -> if (r.code == 404 || r.code == 400) Result.Ok(emptyList()) else Result.Failed(r.message, r.code)
        }
    }

    private suspend fun bookOrbitAdd(itemId: String, timeSec: Double, title: String): Result<Bookmark> {
        val body = buildJsonObject {
            put("clientId", UUID.randomUUID().toString())
            put("positionMs", (timeSec * 1000).toLong())
            put("title", title.take(500))
        }.toString()
        return when (val r = client.post("/api/v1/audiobooks/$itemId/bookmarks", body, JsonElement.serializer())) {
            is Result.Ok -> Result.Ok(
                bookOrbitBookmark(r.value) ?: Bookmark(timeSec, title, System.currentTimeMillis()),
            )
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private fun bookOrbitBookmark(e: JsonElement): Bookmark? {
        val obj = e as? JsonObject ?: return null
        val createdAt = obj["createdAt"]?.jsonPrimitive?.content
        return Bookmark(
            timeSec = (obj["positionMs"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0) / 1000.0,
            title = obj["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() } ?: "Bookmark",
            createdAtMs = createdAt?.toLongOrNull()
                ?: createdAt?.let { runCatching { java.time.Instant.parse(it).toEpochMilli() }.getOrNull() }
                ?: 0L,
            id = obj["id"]?.jsonPrimitive?.content,
        )
    }

    companion object {
        private const val TAG = "AbsorbWearBookmarks"
        private val json = Json { ignoreUnknownKeys = true }

        /** Bookmarks by item id as last fetched, so a list opens at once. */
        val cache = MutableStateFlow<Map<String, List<Bookmark>>>(emptyMap())
    }
}
