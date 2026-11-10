package com.barnabas.absorb.wear.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.R

/** The phone app's glow: the theme colour fading in from the top of the
 *  screen. Put it first in a Box, under the content. */
@Composable
fun GlowBackdrop(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 150.dp) {
    val tint = MaterialTheme.colors.primary
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(
                Brush.verticalGradient(
                    0f to tint.copy(alpha = 0.38f),
                    1f to Color.Transparent,
                ),
            ),
    )
}

/** The app's mark, from the launcher icon. */
@Composable
fun AbsorbLogo(size: androidx.compose.ui.unit.Dp = 64.dp, modifier: Modifier = Modifier) {
    Image(
        painter = painterResource(R.drawable.ic_launcher_foreground),
        contentDescription = "Absorb",
        modifier = modifier.size(size),
    )
}

/**
 * A full-width rounded button in the cards' manner: solid in the app's
 * colour when [primary], a tinted card otherwise, with an optional icon
 * and a second line.
 */
@Composable
fun WideButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    secondary: String? = null,
    icon: ImageVector? = null,
    primary: Boolean = false,
    enabled: Boolean = true,
) {
    val tint = MaterialTheme.colors.primary
    val background = when {
        !enabled -> tint.copy(alpha = 0.1f)
        primary -> tint
        else -> tint.copy(alpha = 0.18f)
    }
    val content = when {
        !enabled -> Color.White.copy(alpha = 0.4f)
        primary -> MaterialTheme.colors.onPrimary
        else -> Color.White
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(if (secondary != null) 56.dp else 48.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(background)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(22.dp).padding(end = 0.dp))
            Box(Modifier.size(10.dp))
        }
        androidx.compose.foundation.layout.Column {
            Text(
                text = label,
                style = MaterialTheme.typography.button,
                fontWeight = FontWeight.SemiBold,
                color = content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (secondary != null) {
                Text(
                    text = secondary,
                    style = MaterialTheme.typography.caption3,
                    color = content.copy(alpha = 0.7f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
