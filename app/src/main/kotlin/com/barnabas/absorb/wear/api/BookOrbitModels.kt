package com.barnabas.absorb.wear.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/**
 * Slim projections of the BookOrbit `/api/v1` payloads the watch reads.
 * Shapes follow the phone client's BookOrbit mapper; fields the watch has no
 * use for are dropped by `ignoreUnknownKeys`. Book ids are numbers on the
 * wire, so they are read as [JsonPrimitive] and used as strings.
 */

/** `GET /api/v1/dashboard/scrollers/continue-listening`. */
@Serializable
data class BoScroller(val books: List<BoCard> = emptyList())

@Serializable
data class BoCard(
    val id: JsonPrimitive,
    val title: String? = null,
    /** Author names on a card; a detail payload carries objects instead. */
    val authors: List<JsonElement> = emptyList(),
) {
    val itemId: String get() = id.content

    val authorName: String
        get() = authors.mapNotNull {
            when (it) {
                is JsonPrimitive -> it.contentOrNull
                is JsonObject -> it["name"]?.jsonPrimitive?.contentOrNull
                else -> null
            }
        }.filter { it.isNotBlank() }.joinToString(", ")
}

/** `GET /api/v1/audiobooks/:id/manifest` — the playback order of a book's audio. */
@Serializable
data class BoManifest(
    val revision: JsonElement? = null,
    val totalDurationMs: Double? = null,
    val assets: List<BoAsset> = emptyList(),
    val chapters: List<BoChapter> = emptyList(),
) {
    val totalSeconds: Double get() = (totalDurationMs ?: 0.0) / 1000.0
}

@Serializable
data class BoAsset(
    val assetId: String,
    val fileId: JsonPrimitive? = null,
    val durationMs: Double? = null,
    val format: String? = null,
    val etag: String? = null,
)

@Serializable
data class BoChapter(
    val title: String? = null,
    val startMs: Double = 0.0,
    val endMs: Double = 0.0,
)

/** `GET`/`PUT /api/v1/audiobooks/:id/playback-state` — a track-local position. */
@Serializable
data class BoPlaybackState(
    val assetId: String? = null,
    val positionMs: Double = 0.0,
    val percentage: Double = 0.0,
    val completed: Boolean = false,
    val capturedAt: String? = null,
    val revision: Int? = null,
)

/** `GET /api/v1/app-info` — what this server version supports. */
@Serializable
data class BoAppInfo(
    val version: String? = null,
    val features: List<String> = emptyList(),
)

/** `GET /api/v1/books/:id` — only the files, to find a narrated EPUB. */
@Serializable
data class BoBookDetail(val files: List<BoFile> = emptyList())

@Serializable
data class BoFile(
    val id: JsonPrimitive? = null,
    val role: String? = null,
    val format: String? = null,
    val mediaOverlay: BoMediaOverlayInfo? = null,
) {
    /** An EPUB 3 that reads itself aloud, with a known length. */
    val isNarratedEpub: Boolean
        get() = (role == "primary" || role == "content") &&
            format.equals("epub", ignoreCase = true) &&
            mediaOverlay?.available == true &&
            (mediaOverlay.durationSeconds ?: 0.0) > 0.0
}

@Serializable
data class BoMediaOverlayInfo(
    val available: Boolean = false,
    val durationSeconds: Double? = null,
)

/** `GET /api/v1/epub/:id/media-overlay?fileId=` — the narration playlist. */
@Serializable
data class BoPlaylist(
    val resources: List<BoResource> = emptyList(),
    val sections: List<BoSection> = emptyList(),
    val items: List<BoPlaylistItem> = emptyList(),
)

@Serializable
data class BoResource(val href: String? = null, val mediaType: String? = null)

@Serializable
data class BoSection(val index: Int? = null, val href: String? = null, val label: String? = null)

@Serializable
data class BoPlaylistItem(
    val audioHref: String? = null,
    val textHref: String? = null,
    val textFragment: String? = null,
    val clipBeginSeconds: Double? = null,
    val clipEndSeconds: Double? = null,
    val sectionIndex: Int? = null,
    val label: String? = null,
)

/** One row of `GET /api/v1/books/:id/progress`: a file's reading spot. */
@Serializable
data class BoProgressRow(
    val fileId: JsonPrimitive? = null,
    val positionSeconds: Double? = null,
    val mediaOverlayFragment: String? = null,
    val mediaOverlaySectionIndex: Int? = null,
    val updatedAt: String? = null,
)
