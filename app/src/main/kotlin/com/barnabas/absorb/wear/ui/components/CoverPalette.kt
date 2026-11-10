package com.barnabas.absorb.wear.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.core.graphics.drawable.toBitmap
import androidx.palette.graphics.Palette
import androidx.wear.compose.material.MaterialTheme
import coil.imageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import com.barnabas.absorb.wear.api.CoverArt
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.CoverStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The colours the player dresses in: taken from the book's cover, with
 *  the app's teal until a cover has been read. */
data class PlayerColors(val accent: Color, val accentSoft: Color, val onAccent: Color)

/**
 * The player's colours for [itemId], from its cover's strongest colour and
 * a lighter companion, lifted enough to read against black. Falls back to
 * the theme's own colours while the cover loads or when it has none.
 */
@Composable
fun rememberCoverColors(creds: Credentials?, itemId: String?): PlayerColors {
    val theme = MaterialTheme.colors
    val fallback = remember(theme) { PlayerColors(theme.primary, theme.secondary, theme.onPrimary) }
    val context = LocalContext.current
    val live by CredentialStore.credentials.collectAsState()
    var colors by remember(itemId) { mutableStateOf(fallback) }

    LaunchedEffect(itemId, live?.accessToken) {
        if (itemId == null || creds == null) return@LaunchedEffect
        val session = live?.takeIf { it.serverUrl == creds.serverUrl } ?: creds
        val kept = CoverStore.existing(itemId)
        val request = ImageRequest.Builder(context)
            .data(kept ?: CoverArt.url(session, itemId))
            .allowHardware(false)
            .size(96)
            .apply {
                if (kept == null) {
                    addHeader("Authorization", "Bearer ${session.accessToken}")
                    session.customHeaders.forEach { (key, value) -> addHeader(key, value) }
                }
            }
            .build()
        val bitmap = (context.imageLoader.execute(request) as? SuccessResult)?.drawable?.toBitmap() ?: return@LaunchedEffect
        val palette = withContext(Dispatchers.Default) { Palette.from(bitmap).clearFilters().generate() }
        val main = palette.vibrantSwatch ?: palette.lightVibrantSwatch ?: palette.dominantSwatch ?: return@LaunchedEffect
        val soft = palette.lightVibrantSwatch?.takeIf { it !== main }
            ?: palette.lightMutedSwatch
            ?: palette.mutedSwatch
        val accent = readable(Color(main.rgb))
        val accentSoft = soft?.let { readable(Color(it.rgb)) }?.takeIf { it != accent } ?: lighten(accent, 0.35f)
        colors = PlayerColors(
            accent = accent,
            accentSoft = accentSoft,
            onAccent = if (accent.luminance() > 0.45f) Color.Black else Color.White,
        )
    }
    return colors
}

/** A colour that still shows on a black screen: dark ones are lifted. */
private fun readable(color: Color): Color {
    val lum = color.luminance()
    return when {
        lum < 0.08f -> lighten(color, 0.55f)
        lum < 0.18f -> lighten(color, 0.3f)
        else -> color
    }
}

private fun lighten(color: Color, amount: Float): Color = Color(
    red = color.red + (1f - color.red) * amount,
    green = color.green + (1f - color.green) * amount,
    blue = color.blue + (1f - color.blue) * amount,
    alpha = 1f,
)
