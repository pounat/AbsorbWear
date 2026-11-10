package com.barnabas.absorb.wear.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.MaterialTheme

// Primary sampled from app_icon_foreground.png's central waveform bar;
// secondary is a lighter teal taken from the icon's outer bars. These are
// the watch's own colours until the phone has sent its theme colour.
private val AbsorbTealPrimary = Color(0xFF62B0AC)
private val AbsorbTealVariant = Color(0xFF4F9D99)
private val AbsorbTealSecondary = Color(0xFF95CECC)

private val AbsorbWearColors = Colors(
    primary = AbsorbTealPrimary,
    primaryVariant = AbsorbTealVariant,
    secondary = AbsorbTealSecondary,
    background = Color.Black,
    surface = Color(0xFF1A1A1A),
    onPrimary = Color.Black,
    onSecondary = Color.Black,
    onBackground = Color.White,
    onSurface = Color.White,
)

@Composable
fun AbsorbWearTheme(content: @Composable () -> Unit) {
    val phone by PhoneTheme.color.collectAsState()
    val colors = remember(phone) { phone?.let { PhoneTheme.colors(it, AbsorbWearColors) } ?: AbsorbWearColors }
    MaterialTheme(colors = colors, content = content)
}
