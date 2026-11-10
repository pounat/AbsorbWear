package com.barnabas.absorb.wear.player

import kotlinx.serialization.Serializable

/**
 * Snapshot of phone-side ABS playback at one instant. Pushed via the
 * Data Layer every time HomeWidgetService updates (throttled to ~2s).
 *
 * Position interpolation: the watch uses [issuedAt] + [positionMs] +
 * [speed] to tick the displayed time forward smoothly between pushes,
 * so the user sees second-by-second progress without flooding the
 * Bluetooth pipe.
 */
@Serializable
data class PlayerState(
    val hasBook: Boolean,
    val itemId: String? = null,
    val title: String? = null,
    val author: String? = null,
    val chapter: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0L,
    val durationMs: Long = 0L,
    val speed: Float = 1.0f,
    val skipBackSec: Int = 10,
    val skipForwardSec: Int = 30,
    /** The current chapter's bounds, absolute in the item; both zero when
     *  the phone does not know them. */
    val chapterStartMs: Long = 0L,
    val chapterEndMs: Long = 0L,
    /** The phone's playback settings the watch copies; the defaults are
     *  the phone app's own. */
    val autoRewindEnabled: Boolean = true,
    val autoRewindMinSec: Double = 1.0,
    val autoRewindMaxSec: Double = 30.0,
    val autoRewindDelaySec: Double = 0.0,
    val autoRewindChapterBarrier: Boolean = false,
    val autoRewindSessionStart: Boolean = false,
    val skipChapterBarrier: Boolean = true,
    val speedAdjustedTime: Boolean = true,
    /** Where the chapter sits in the book's list; -1 when unknown. */
    val chapterIndex: Int = -1,
    val chapterCount: Int = 0,
    /** The phone's sleep timer: "time", "chapters", "episodes" or null
     *  when off, with when a timed one ends (epoch millis, counted only
     *  while playing) or how many chapters are left. */
    val sleepMode: String? = null,
    val sleepEndsAtMs: Long = 0L,
    val sleepChaptersLeft: Int = 0,
    /** Minutes a tap on the running timer adds, the phone's shake amount. */
    val sleepAddMinutes: Int = 5,
    /** The speeds the phone's speed button steps through, its saved set;
     *  empty from an older phone app. */
    val speedPresets: List<Float> = emptyList(),
    /** The phone app's theme colour as ARGB; zero when it sent none. */
    val themeColor: Long = 0L,
    /** Epoch millis the phone wrote this snapshot. */
    val issuedAt: Long = 0L,
) {
    companion object {
        val EMPTY = PlayerState(hasBook = false)
    }
}
