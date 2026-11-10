package com.barnabas.absorb.wear.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.Text

/**
 * The player's frame: the book's progress as a ring around the screen's
 * edge and the chapter's as a thinner ring just inside it, both open at
 * the bottom, in the book's colours. [content] sits inside the rings.
 */
@Composable
fun PlayerRings(
    bookProgress: Float,
    chapterProgress: Float?,
    colors: PlayerColors,
    modifier: Modifier = Modifier,
    moreHint: Boolean = false,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.fillMaxSize()) {
        content()
        if (moreHint) {
            Icon(
                imageVector = Icons.Rounded.KeyboardArrowUp,
                contentDescription = "More",
                tint = colors.accentSoft.copy(alpha = 0.6f),
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 9.dp).size(18.dp),
            )
        }
        CircularProgressIndicator(
            progress = bookProgress.coerceIn(0f, 1f),
            modifier = Modifier.fillMaxSize().padding(1.dp),
            startAngle = 135f,
            endAngle = 45f,
            indicatorColor = colors.accent,
            trackColor = colors.accent.copy(alpha = 0.22f),
            strokeWidth = 5.dp,
        )
        if (chapterProgress != null) {
            CircularProgressIndicator(
                progress = chapterProgress.coerceIn(0f, 1f),
                modifier = Modifier.fillMaxSize().padding(9.dp),
                startAngle = 135f,
                endAngle = 45f,
                indicatorColor = colors.accentSoft,
                trackColor = colors.accentSoft.copy(alpha = 0.18f),
                strokeWidth = 3.dp,
            )
        }
    }
}

/** The big round play/pause button in the book's colour, with a soft halo;
 *  while [spinning] it shows the loading shape instead and cannot be pressed. */
@Composable
fun PlayPauseButton(
    isPlaying: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit,
    spinning: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(62.dp)
            .border(2.dp, colors.accentSoft.copy(alpha = 0.55f), CircleShape)
            .padding(4.dp),
    ) {
        Button(
            onClick = onClick,
            enabled = !spinning,
            modifier = Modifier.fillMaxSize().clip(CircleShape),
            colors = ButtonDefaults.buttonColors(
                backgroundColor = colors.accent,
                contentColor = colors.onAccent,
                disabledBackgroundColor = colors.accent.copy(alpha = 0.7f),
                disabledContentColor = colors.onAccent,
            ),
        ) {
            if (spinning) {
                ExpressiveSpinner(modifier = Modifier.size(26.dp), color = colors.onAccent)
            } else {
                Icon(
                    imageVector = if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(34.dp),
                    tint = colors.onAccent,
                )
            }
        }
    }
}

/**
 * A skip button: a translucent disc in the book's colour with the circling
 * arrow, mirrored for forward, and the number of seconds in its middle, so
 * any amount the phone app is set to reads right.
 */
@Composable
fun SkipButton(
    seconds: Int,
    forward: Boolean,
    colors: PlayerColors,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(44.dp).clip(CircleShape),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = colors.accentSoft.copy(alpha = 0.24f),
            contentColor = Color.White,
            disabledBackgroundColor = colors.accentSoft.copy(alpha = 0.1f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = Icons.Rounded.Replay,
                contentDescription = if (forward) "Forward $seconds seconds" else "Back $seconds seconds",
                modifier = Modifier
                    .size(30.dp)
                    .scale(scaleX = if (forward) -1f else 1f, scaleY = 1f),
            )
            Text(
                text = seconds.toString(),
                fontSize = if (seconds >= 100) 7.sp else 9.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.offset(y = 1.5.dp),
            )
        }
    }
}
