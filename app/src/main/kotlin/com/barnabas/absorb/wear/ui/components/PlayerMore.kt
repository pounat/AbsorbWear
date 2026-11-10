package com.barnabas.absorb.wear.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Bookmarks
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FormatListNumbered
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipColors
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.barnabas.absorb.wear.playback.PlaybackManager.PlayTarget
import com.barnabas.absorb.wear.playback.SleepTimer
import com.barnabas.absorb.wear.player.PlayerState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.ceil

/**
 * The page under the player face, reached by swiping up: chapter skips, a
 * bookmark at the current spot, the sleep timer, the way to the chapter
 * list, the bookmark list and the book's details, where play goes, and
 * last a stop that ends the session. The action that sits on the face is
 * not here; its slot holds the playback speed instead, and a long press
 * on any chip offers to swap it onto the face. The same page serves the
 * watch's own player and the phone remote; only the actions behind it
 * differ.
 */
@Composable
fun PlayerMorePage(
    colors: PlayerColors,
    enabled: Boolean,
    chapterTitle: String?,
    chapterIndex: Int,
    chapterCount: Int,
    actions: PlayerActions,
    faceAction: FaceAction,
    onSwapFace: (FaceAction) -> Unit,
) {
    val listState = rememberScalingLazyListState()
    val scope = rememberCoroutineScope()
    var bookmarkNote by remember { mutableStateOf<String?>(null) }
    var swapTo by remember { mutableStateOf<FaceAction?>(null) }
    LaunchedEffect(bookmarkNote) {
        if (bookmarkNote == null) return@LaunchedEffect
        delay(2_000)
        bookmarkNote = null
    }
    val chipColors = ChipDefaults.chipColors(
        backgroundColor = colors.accentSoft.copy(alpha = 0.2f),
        contentColor = Color.White,
        secondaryContentColor = Color.White.copy(alpha = 0.65f),
        iconColor = colors.accentSoft,
        disabledBackgroundColor = colors.accentSoft.copy(alpha = 0.08f),
        disabledContentColor = Color.White.copy(alpha = 0.4f),
        disabledSecondaryContentColor = Color.White.copy(alpha = 0.3f),
        disabledIconColor = colors.accentSoft.copy(alpha = 0.4f),
    )
    val hasChapters = chapterCount > 0
    val phone = actions.playTarget == PlayTarget.PHONE

    @Composable
    fun listChip(shown: FaceAction) {
        val modifier = Modifier.fillMaxWidth().onLongPress { swapTo = shown }
        when (shown) {
            FaceAction.SPEED -> ActionChip("Playback speed", actions.speedLabel, Icons.Rounded.Speed, chipColors, enabled, actions.onSpeed, modifier)
            FaceAction.BOOKMARK -> {
                val saved = bookmarkNote
                Chip(
                    onClick = {
                        scope.launch { bookmarkNote = if (actions.onBookmark()) "Saved" else "Couldn't save" }
                    },
                    enabled = enabled && saved == null,
                    label = { Text("Bookmark") },
                    secondaryLabel = { Text(saved ?: "Save this spot") },
                    icon = {
                        Icon(
                            if (saved == "Saved") Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                            contentDescription = null,
                            modifier = Modifier.size(ChipDefaults.IconSize),
                        )
                    },
                    colors = chipColors,
                    modifier = modifier,
                )
            }
            FaceAction.SLEEP -> ActionChip("Sleep timer", actions.sleepLabel ?: "Off", Icons.Rounded.Bedtime, chipColors, enabled, actions.onSleep, modifier)
            FaceAction.CHAPTERS -> ActionChip(
                "Chapters",
                if (hasChapters) "$chapterCount chapters" else "None in this book",
                Icons.Rounded.FormatListNumbered,
                chipColors,
                enabled && hasChapters,
                actions.onChapters,
                modifier,
            )
            FaceAction.BOOKMARKS -> ActionChip("Bookmarks", "Your saved spots", Icons.Rounded.Bookmarks, chipColors, enabled, actions.onBookmarks, modifier)
            FaceAction.DETAILS -> ActionChip("Details", "About this book", Icons.Rounded.Info, chipColors, enabled, actions.onDetails, modifier)
            FaceAction.PLAY_ON -> ActionChip("Play on", if (phone) "Phone" else "Watch", if (phone) Icons.Rounded.Smartphone else Icons.Rounded.Watch, chipColors, true, actions.onPlayOn, modifier)
            FaceAction.STOP -> ActionChip("Stop", "End the session", Icons.Rounded.Stop, ChipDefaults.secondaryChipColors(), enabled, actions.onStop, modifier)
        }
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
            ) {
                RoundButton(Icons.Rounded.SkipPrevious, "Previous chapter", colors, enabled && hasChapters, actions.onPrevChapter)
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = when {
                            !hasChapters -> "No chapters"
                            chapterIndex >= 0 -> "Chapter ${chapterIndex + 1} of $chapterCount"
                            else -> "$chapterCount chapters"
                        },
                        style = MaterialTheme.typography.caption2,
                        color = colors.accentSoft,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                    )
                    if (!chapterTitle.isNullOrBlank()) {
                        Text(
                            text = chapterTitle,
                            style = MaterialTheme.typography.caption3,
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                RoundButton(Icons.Rounded.SkipNext, "Next chapter", colors, enabled && hasChapters, actions.onNextChapter)
            }
        }
        for (slot in PAGE_SLOTS) {
            item(key = slot) { listChip(if (slot == faceAction) FaceAction.SPEED else slot) }
        }
    }

    val target = swapTo
    Dialog(showDialog = target != null, onDismissRequest = { swapTo = null }) {
        if (target != null) {
            Alert(
                title = {
                    Text(
                        text = "Swap ${faceAction.title.lowercase()} for ${target.title.lowercase()}?",
                        textAlign = TextAlign.Center,
                    )
                },
                negativeButton = {
                    Button(onClick = { swapTo = null }, colors = ButtonDefaults.secondaryButtonColors()) {
                        Icon(Icons.Rounded.Close, contentDescription = "Keep it")
                    }
                },
                positiveButton = {
                    Button(onClick = { onSwapFace(target); swapTo = null }) {
                        Icon(Icons.Rounded.Check, contentDescription = "Swap")
                    }
                },
            ) {
                Text(
                    text = "${target.title} goes on the player and ${faceAction.title.lowercase()} comes here.",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

/** The page's chips in order; the face's own slot shows the speed. */
private val PAGE_SLOTS = listOf(
    FaceAction.BOOKMARK,
    FaceAction.SLEEP,
    FaceAction.CHAPTERS,
    FaceAction.BOOKMARKS,
    FaceAction.DETAILS,
    FaceAction.PLAY_ON,
    FaceAction.STOP,
)

@Composable
private fun ActionChip(
    label: String,
    secondary: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    colors: ChipColors,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Chip(
        onClick = onClick,
        enabled = enabled,
        label = { Text(label) },
        secondaryLabel = { Text(secondary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        icon = { Icon(icon, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
        colors = colors,
        modifier = modifier,
    )
}

@Composable
private fun RoundButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    colors: PlayerColors,
    enabled: Boolean,
    onClick: () -> Unit,
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
            Icon(icon, contentDescription = description, modifier = Modifier.size(28.dp))
        }
    }
}

/** What the watch's own timer reads as, or null when off. */
fun sleepLabel(state: SleepTimer.State?): String? = when {
    state == null -> null
    state.isChapter -> "End of chapter"
    else -> "${ceil(state.remainingMs / 60_000.0).toInt().coerceAtLeast(1)} min left"
}

/** What the phone's timer reads as, or null when off. */
fun sleepLabel(phone: PlayerState?, nowMs: Long): String? {
    if (phone == null) return null
    return when (phone.sleepMode) {
        "time" -> {
            val left = if (phone.isPlaying) phone.sleepEndsAtMs - nowMs else phone.sleepEndsAtMs - phone.issuedAt
            "${ceil(left.coerceAtLeast(0L) / 60_000.0).toInt().coerceAtLeast(1)} min left"
        }
        "chapters" -> if (phone.sleepChaptersLeft <= 1) "End of chapter" else "${phone.sleepChaptersLeft} chapters left"
        "episodes" -> "End of episode"
        else -> null
    }
}
