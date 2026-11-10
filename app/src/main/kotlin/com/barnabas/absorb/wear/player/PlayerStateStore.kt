package com.barnabas.absorb.wear.player

import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.playback.Chapter
import com.barnabas.absorb.wear.playback.PlaybackManager
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide in-memory holder for the latest phone-side player state.
 *
 * Intentionally NOT persisted to disk: stale playback metadata after a
 * cold start is worse than a one-frame blank. The watch falls back to
 * the "no book playing" view until the phone publishes a fresh state.
 */
object PlayerStateStore {

    private val _state = MutableStateFlow<PlayerState?>(null)
    val state: StateFlow<PlayerState?> = _state.asStateFlow()

    fun update(state: PlayerState) {
        val current = _state.value
        // Drop stale-by-clock pushes — out-of-order DataItem delivery
        // shouldn't rewind the displayed position.
        if (current != null && state.issuedAt < current.issuedAt) return
        _state.value = state
        rememberKeptPosition(state, current)
        // A little slack for the two clocks.
        val start = _starting.value
        if (start != null && state.isPlaying && state.itemId != null &&
            start.isFor(state.itemId, state.title) && state.issuedAt >= start.at - 2_000L
        ) {
            cancelStart()
        }
    }

    /** A book the watch asked the phone to play, until the phone reports
     *  it playing or [START_TIMEOUT_MS] passes. The phone can take a while:
     *  its app may have to start and the stream to open. A podcast episode
     *  carries its title, since the phone names no episode. */
    data class PhoneStart(val itemId: String, val episodeTitle: String?, val at: Long) {
        fun isFor(itemId: String, title: String?): Boolean =
            this.itemId == itemId && (episodeTitle == null || episodeTitle == title)
    }

    private const val START_TIMEOUT_MS = 45_000L
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startTimeout: Job? = null
    private val _starting = MutableStateFlow<PhoneStart?>(null)
    val starting: StateFlow<PhoneStart?> = _starting.asStateFlow()

    fun expectStart(itemId: String, episodeTitle: String? = null) {
        val start = PhoneStart(itemId, episodeTitle, System.currentTimeMillis())
        _starting.value = start
        startTimeout?.cancel()
        startTimeout = scope.launch {
            delay(START_TIMEOUT_MS)
            _starting.compareAndSet(start, null)
        }
    }

    fun cancelStart() {
        startTimeout?.cancel()
        _starting.value = null
    }

    /**
     * The phone's spot in a book the watch has kept, so a start with no
     * server in reach picks up where the phone was. Written on a pause and
     * every ten seconds of play, not on every push.
     */
    private fun rememberKeptPosition(state: PlayerState, previous: PlayerState?) {
        val itemId = state.itemId ?: return
        if (!state.hasBook || state.durationMs <= 0L) return
        if (PlaybackManager.uiState.value.itemId == itemId) return
        val moved = previous?.itemId != itemId || abs(state.positionMs - previous.positionMs) >= 10_000L
        val paused = !state.isPlaying && previous?.isPlaying == true
        if (moved || paused) DownloadStore.setLastPosition(itemId, state.positionMs / 1000.0)
    }

    fun clear() {
        _state.value = null
    }

    /** The phone's media volume as it last reported it, with when. */
    data class PhoneVolume(val index: Int, val max: Int, val at: Long)

    private val _phoneVolume = MutableStateFlow<PhoneVolume?>(null)
    val phoneVolume: StateFlow<PhoneVolume?> = _phoneVolume.asStateFlow()

    fun updateVolume(index: Int, max: Int) {
        _phoneVolume.value = PhoneVolume(index, max.coerceAtLeast(1), System.currentTimeMillis())
    }

    /** The chapter list of the book the phone has, as it last sent it. */
    data class PhoneChapters(val itemId: String, val chapters: List<Chapter>)

    private val _chapters = MutableStateFlow<PhoneChapters?>(null)
    val chapters: StateFlow<PhoneChapters?> = _chapters.asStateFlow()

    fun updateChapters(itemId: String, chapters: List<Chapter>) {
        _chapters.value = PhoneChapters(itemId, chapters)
    }
}
