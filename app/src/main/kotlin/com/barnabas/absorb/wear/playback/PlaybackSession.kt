package com.barnabas.absorb.wear.playback

import kotlinx.serialization.Serializable

/**
 * Slim projection of the ABS play-session response
 * (`POST /api/items/:id/play`). We only deserialize what the watch needs to
 * stream + resume + sync; everything else (chapters, codec, metadata) is
 * dropped via Json.ignoreUnknownKeys.
 */
@Serializable
data class PlaybackSession(
    val id: String,
    val libraryItemId: String? = null,
    val audioTracks: List<AudioTrack> = emptyList(),
    val chapters: List<Chapter> = emptyList(),
    /** Total item duration in seconds. */
    val duration: Double = 0.0,
    /** Server-side resume position in seconds (saved progress). */
    val currentTime: Double = 0.0,
)

@Serializable
data class Chapter(
    val id: Int = 0,
    /** Chapter start/end in seconds, absolute within the item. */
    val start: Double = 0.0,
    val end: Double = 0.0,
    val title: String? = null,
)

@Serializable
data class AudioTrack(
    val index: Int = 0,
    /** Server-relative stream path, e.g. `/public/session/<uuid>/track/0`. */
    val contentUrl: String,
    /** Track length in seconds. */
    val duration: Double = 0.0,
    /** Absolute start of this track within the item, in seconds (when the
     *  server provides it; otherwise we derive it from cumulative durations). */
    val startOffset: Double = 0.0,
    val mimeType: String? = null,
)
