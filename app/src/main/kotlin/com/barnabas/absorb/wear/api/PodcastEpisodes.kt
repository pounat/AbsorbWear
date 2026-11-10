package com.barnabas.absorb.wear.api

import com.barnabas.absorb.wear.api.AbsorbHttpClient.Result
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.Chapter
import kotlinx.serialization.Serializable

/** A podcast's episodes as Audiobookshelf has them, newest first. */
object PodcastEpisodes {

    @Serializable
    data class Episode(
        val id: String,
        val title: String? = null,
        /** Epoch millis. */
        val publishedAt: Long? = null,
        val index: Int? = null,
        val duration: Double? = null,
        val audioFile: AudioFile? = null,
        val chapters: List<Chapter> = emptyList(),
    ) {
        val lengthSec: Double
            get() = audioFile?.duration?.takeIf { it > 0 } ?: duration ?: 0.0
    }

    @Serializable
    data class AudioFile(
        val ino: String? = null,
        val duration: Double? = null,
        val mimeType: String? = null,
    )

    @Serializable
    private data class Item(val media: Media? = null)

    @Serializable
    private data class Media(val episodes: List<Episode> = emptyList())

    suspend fun load(creds: Credentials, itemId: String): Result<List<Episode>> =
        when (val r = AbsorbHttpClient(creds).get("/api/items/$itemId?expanded=1", Item.serializer())) {
            is Result.Ok -> Result.Ok(
                r.value.media?.episodes.orEmpty().sortedWith(
                    compareByDescending<Episode> { it.publishedAt ?: 0L }.thenByDescending { it.index ?: 0 },
                ),
            )
            is Result.Unauthorized -> Result.Unauthorized(r.code)
            is Result.Failed -> Result.Failed(r.message, r.code)
        }
}
