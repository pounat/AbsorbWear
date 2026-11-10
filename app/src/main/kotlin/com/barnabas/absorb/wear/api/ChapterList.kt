package com.barnabas.absorb.wear.api

import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.Chapter
import kotlinx.serialization.Serializable

/** A book's chapters straight from the server, for when the phone's copy
 *  is not at hand. Null when the server could not say. */
object ChapterList {

    suspend fun load(creds: Credentials, itemId: String): List<Chapter>? {
        val client = AbsorbHttpClient(creds)
        if (creds.isBookOrbit) {
            val manifest = (client.get("/api/v1/audiobooks/$itemId/manifest", BoManifest.serializer()) as? Result.Ok)?.value ?: return null
            return manifest.chapters.mapIndexed { i, c ->
                Chapter(id = i, start = c.startMs / 1000.0, end = c.endMs / 1000.0, title = c.title)
            }
        }
        return (client.get("/api/items/$itemId", AbsItem.serializer()) as? Result.Ok)?.value?.media?.chapters
    }

    @Serializable
    private data class AbsItem(val media: Media? = null) {
        @Serializable
        data class Media(val chapters: List<Chapter> = emptyList())
    }
}
