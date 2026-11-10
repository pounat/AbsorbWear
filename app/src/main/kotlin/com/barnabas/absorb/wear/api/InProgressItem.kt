package com.barnabas.absorb.wear.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Slim projection of an ABS LibraryItem as it appears in the
 * `/api/me/items-in-progress` response. We only deserialize the fields
 * the watch needs — everything else is ignored thanks to
 * [Json.ignoreUnknownKeys].
 */
@Serializable
data class InProgressItem(
    val id: String,
    val libraryId: String? = null,
    /** "book" or "podcast" on Audiobookshelf; absent from BookOrbit. */
    val mediaType: String? = null,
    val media: Media? = null,
    @SerialName("recentEpisode") val recentEpisode: RecentEpisode? = null,
) {
    @Serializable
    data class Media(
        val metadata: Metadata? = null,
        val duration: Double = 0.0,
    )

    @Serializable
    data class Metadata(
        val title: String? = null,
        val authorName: String? = null,
        /** A podcast's author; books use [authorName]. */
        val author: String? = null,
    )

    @Serializable
    data class RecentEpisode(
        val id: String? = null,
        val title: String? = null,
    )

    val displayTitle: String
        get() = recentEpisode?.title?.takeIf { it.isNotBlank() }
            ?: media?.metadata?.title
            ?: "Untitled"

    /** The author, or for an episode the podcast it is from. */
    val displaySubtitle: String?
        get() = if (recentEpisode != null) {
            media?.metadata?.title?.takeIf { it.isNotBlank() }
        } else {
            (media?.metadata?.authorName ?: media?.metadata?.author)?.takeIf { it.isNotBlank() }
        }

    val episodeId: String?
        get() = recentEpisode?.id

    val isPodcast: Boolean
        get() = mediaType == "podcast"

    /** A whole podcast rather than one of its episodes: opens its episode
     *  list instead of playing. */
    val isPodcastShow: Boolean
        get() = isPodcast && episodeId == null
}

@Serializable
data class InProgressResponse(
    val libraryItems: List<InProgressItem> = emptyList(),
)
