package com.barnabas.absorb.wear.playback

/** The phone app's own speed presets, until it sends its saved set. */
val DEFAULT_SPEED_PRESETS = listOf(0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f)

/**
 * The preset after [current]: the first one faster than it, or the slowest
 * again once past the top, the way the phone's speed button steps.
 */
fun nextSpeed(current: Float, presets: List<Float>): Float {
    val steps = presets.filter { it > 0f }.sorted().ifEmpty { DEFAULT_SPEED_PRESETS }
    return steps.firstOrNull { it > current + 0.001f } ?: steps.first()
}
