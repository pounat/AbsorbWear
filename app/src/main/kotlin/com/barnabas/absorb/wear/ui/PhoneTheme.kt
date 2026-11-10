package com.barnabas.absorb.wear.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.wear.compose.material.Colors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone app's theme colour, as it last sent it in its player state:
 * the colour the user picked, or the one it took from the playing book's
 * cover. The watch dresses in it so both apps look like one. Kept across
 * restarts so the watch does not flash teal before the phone speaks.
 */
object PhoneTheme {
    private const val PREFS = "absorb_wear_playback"
    private const val KEY_COLOR = "phone_theme_color"

    private var prefs: SharedPreferences? = null
    private val _color = MutableStateFlow<Color?>(null)
    val color: StateFlow<Color?> = _color.asStateFlow()

    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs = p
        val stored = p.getLong(KEY_COLOR, 0L)
        if (stored != 0L) _color.value = Color(stored.toInt())
    }

    /** [argb] as the phone sends it; zero means it sent none. */
    fun adopt(argb: Long) {
        if (argb == 0L) return
        val c = Color(argb.toInt())
        if (_color.value == c) return
        _color.value = c
        prefs?.edit()?.putLong(KEY_COLOR, argb)?.apply()
    }

    /** The watch's palette built on the phone's colour: lifted enough to
     *  read on black, with a lighter companion for the secondary. */
    fun colors(seed: Color, base: Colors): Colors {
        val primary = readable(seed)
        return base.copy(
            primary = primary,
            primaryVariant = darken(primary, 0.15f),
            secondary = lighten(primary, 0.35f),
            onPrimary = if (primary.luminance() > 0.45f) Color.Black else Color.White,
            onSecondary = Color.Black,
        )
    }

    private fun readable(color: Color): Color {
        val lum = color.luminance()
        return when {
            lum < 0.08f -> lighten(color, 0.55f)
            lum < 0.18f -> lighten(color, 0.3f)
            else -> color
        }
    }

    private fun lighten(c: Color, amount: Float): Color =
        Color(
            red = c.red + (1f - c.red) * amount,
            green = c.green + (1f - c.green) * amount,
            blue = c.blue + (1f - c.blue) * amount,
            alpha = c.alpha,
        )

    private fun darken(c: Color, amount: Float): Color =
        Color(red = c.red * (1f - amount), green = c.green * (1f - amount), blue = c.blue * (1f - amount), alpha = c.alpha)
}
