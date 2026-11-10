package com.barnabas.absorb.wear.playback

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * The watch's own sleep timer for a book playing here: a number of minutes
 * that count down only while playing, or the end of the current chapter.
 * When it is up the player pauses. Playing on the phone uses the phone's
 * timer instead, through commands.
 */
object SleepTimer {
    private const val TAG = "AbsorbWearSleep"

    /** What is set: minutes left, or the chapter whose end stops play. */
    data class State(
        val remainingMs: Long = 0L,
        val chapterEndMs: Long = 0L,
    ) {
        val isChapter: Boolean get() = chapterEndMs > 0L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var job: Job? = null

    private val _state = MutableStateFlow<State?>(null)
    val state: StateFlow<State?> = _state.asStateFlow()

    fun start(minutes: Int) {
        set(State(remainingMs = minutes * 60_000L))
        Log.i(TAG, "sleep in $minutes min")
    }

    /** Stops at the end of the chapter playing now; nothing when the book
     *  has no chapters. */
    fun endOfChapter() {
        val s = PlaybackManager.uiState.value
        if (s.chapterEndMs <= s.chapterStartMs) return
        set(State(chapterEndMs = s.chapterEndMs))
        Log.i(TAG, "sleep at chapter end ${s.chapterEndMs / 1000}s")
    }

    fun addMinutes(minutes: Int) {
        val s = _state.value ?: return
        if (s.isChapter) return
        _state.value = s.copy(remainingMs = s.remainingMs + minutes * 60_000L)
    }

    fun cancel() {
        job?.cancel()
        job = null
        _state.value = null
    }

    private fun set(state: State) {
        job?.cancel()
        _state.value = state
        job = scope.launch {
            var last = System.currentTimeMillis()
            while (true) {
                delay(1_000)
                val now = System.currentTimeMillis()
                val playing = PlaybackManager.uiState.value
                val current = _state.value ?: break
                if (current.isChapter) {
                    // Past the chapter's end, or already in a later chapter.
                    if (playing.positionMs >= current.chapterEndMs - 500 || playing.chapterStartMs >= current.chapterEndMs) {
                        fire()
                        break
                    }
                } else if (playing.isPlaying) {
                    val left = current.remainingMs - (now - last)
                    if (left <= 0L) {
                        fire()
                        break
                    }
                    _state.value = current.copy(remainingMs = left)
                }
                last = now
            }
        }
    }

    private fun fire() {
        Log.i(TAG, "sleep timer up, pausing")
        if (PlaybackManager.uiState.value.isPlaying) PlaybackManager.playPause()
        _state.value = null
        job = null
    }
}
