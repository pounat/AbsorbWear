package com.barnabas.absorb.wear.playback

/**
 * What the UI needs to render the now-playing surface, derived from the
 * on-watch ExoPlayer by [PlaybackManager]. Positions are absolute across
 * the whole item (tracks flattened), in milliseconds.
 */
data class PlaybackUiState(
    val itemId: String? = null,
    val episodeId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val chapterTitle: String? = null,
    /** The current chapter's bounds, absolute in the item, when known. */
    val chapterStartMs: Long = 0L,
    val chapterEndMs: Long = 0L,
    /** The book's chapters, sorted, set once when it loads. */
    val chapters: List<Chapter> = emptyList(),
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    /** The player has enough of the book to start at once. */
    val isReady: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1f,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    /** Playback is held because no Bluetooth audio device is connected. */
    val needsAudioOutput: Boolean = false,
    val error: String? = null,
) {
    val hasItem: Boolean get() = itemId != null
}
