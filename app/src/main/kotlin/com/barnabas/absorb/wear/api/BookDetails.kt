package com.barnabas.absorb.wear.api

import android.util.Log
import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** What the details sheet shows about a book beyond its title and author. */
data class BookDetails(
    val narrator: String? = null,
    /** "Name #3" when the book sits in a series. */
    val series: String? = null,
    val genres: List<String> = emptyList(),
    val publishedYear: String? = null,
    val publisher: String? = null,
    val description: String? = null,
    val chapterCount: Int = 0,
    val sizeBytes: Long = 0L,
    val durationSec: Double = 0.0,
)

/** One request per server type for a book's details. */
object BookDetailsLoader {
    private const val TAG = "AbsorbWearDetails"

    suspend fun load(creds: Credentials, itemId: String): Result<BookDetails> {
        val r = if (creds.isBookOrbit) bookOrbit(creds, itemId) else abs(creds, itemId)
        when (r) {
            is Result.Ok -> Log.i(TAG, "details for $itemId: ${r.value.chapterCount} chapters, series=${r.value.series}")
            is Result.Unauthorized -> Log.w(TAG, "details: HTTP ${r.code}")
            is Result.Failed -> Log.w(TAG, "details: ${r.message} (HTTP ${r.code})")
        }
        return r
    }

    private suspend fun abs(creds: Credentials, itemId: String): Result<BookDetails> {
        return when (val r = AbsorbHttpClient(creds).get("/api/items/$itemId?expanded=1", JsonElement.serializer())) {
            is Result.Ok -> {
                val item = r.value as? JsonObject ?: return Result.Failed("Not a book")
                val media = item["media"] as? JsonObject
                val meta = media?.get("metadata") as? JsonObject
                Result.Ok(
                    BookDetails(
                        narrator = meta.str("narratorName"),
                        series = meta.str("seriesName"),
                        genres = (meta?.get("genres") as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                        publishedYear = meta.str("publishedYear"),
                        publisher = meta.str("publisher"),
                        description = meta.str("description")?.plain(),
                        chapterCount = (media?.get("chapters") as? JsonArray)?.size ?: 0,
                        sizeBytes = item["size"]?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
                            ?: (media?.get("audioFiles") as? JsonArray).orEmpty().sumOf { f ->
                                (f as? JsonObject)?.get("metadata")?.jsonObject?.get("size")?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L
                            },
                        durationSec = media?.get("duration")?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    ),
                )
            }
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
    }

    private suspend fun bookOrbit(creds: Credentials, itemId: String): Result<BookDetails> {
        val client = AbsorbHttpClient(creds)
        val book = when (val r = client.get("/api/v1/books/$itemId", JsonElement.serializer())) {
            is Result.Ok -> r.value as? JsonObject ?: return Result.Failed("Not a book")
            is Result.Unauthorized -> return Result.Unauthorized(r.code)
            is Result.Failed -> return Result.Failed(r.message, r.code)
        }
        val narrators = (book["audioMetadata"] as? JsonObject)?.get("narrators") as? JsonArray
        val narrator = narrators.orEmpty().mapNotNull { n ->
            (n as? JsonObject)?.get("name")?.jsonPrimitive?.content ?: (n as? JsonPrimitive)?.content
        }.filter { it.isNotBlank() }.joinToString(", ").takeIf { it.isNotBlank() }
        val memberships = (book["seriesMemberships"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
            .sortedBy { it["displayOrder"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0 }
        val series = when {
            memberships.isNotEmpty() -> memberships.joinToString(", ") { m ->
                val name = m.str("seriesName").orEmpty()
                val index = m.str("seriesIndex")
                if (index != null) "$name #$index" else name
            }
            book.str("seriesName") != null -> {
                val index = book.str("seriesIndex")
                if (index != null) "${book.str("seriesName")} #$index" else book.str("seriesName")
            }
            else -> null
        }
        val files = (book["files"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        val size = files.sumOf { f ->
            (f["sizeBytes"] ?: f["size"])?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong() ?: 0L
        }
        // The manifest has the chapter count and the exact length.
        var chapterCount = 0
        var durationSec = 0.0
        when (val m = client.get("/api/v1/audiobooks/$itemId/manifest", BoManifest.serializer())) {
            is Result.Ok -> {
                chapterCount = m.value.chapters.size
                durationSec = m.value.totalSeconds
            }
            else -> Unit
        }
        return Result.Ok(
            BookDetails(
                narrator = narrator,
                series = series,
                genres = (book["genres"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.content },
                publishedYear = book.str("publishedYear"),
                publisher = book.str("publisher"),
                description = book.str("description")?.plain(),
                chapterCount = chapterCount,
                sizeBytes = size,
                durationSec = durationSec,
            ),
        )
    }

    private fun JsonObject?.str(key: String): String? =
        (this?.get(key) as? JsonPrimitive)?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content?.takeIf { it.isNotBlank() }

    /** Server descriptions carry HTML; the watch shows words. */
    private fun String.plain(): String? =
        replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</p>", RegexOption.IGNORE_CASE), "\n\n")
            .replace(Regex("<[^>]+>"), " ")
            .replace("&nbsp;", " ").replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'")
            .replace("&lt;", "<").replace("&gt;", ">")
            .replace(Regex("[ \\t]+"), " ")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
            .takeIf { it.isNotBlank() }
}
