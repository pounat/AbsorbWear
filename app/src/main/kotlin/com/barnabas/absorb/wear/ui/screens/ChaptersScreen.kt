package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.itemsIndexed
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.ChapterList
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.playback.Chapter
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.barnabas.absorb.wear.sync.PlayerStateClient
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import kotlinx.coroutines.launch

/** Which player a chapter or bookmark list belongs to. */
const val SOURCE_WATCH = "watch"
const val SOURCE_PHONE = "phone"

/**
 * The playing book's chapters, the current one marked and in view, each a
 * jump to its start. For the watch's own player the list comes with the
 * session; for the phone's it comes over the Data Layer once per book.
 */
@Composable
fun ChaptersScreen(creds: Credentials?, source: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember { PlayerCommandClient(context) }
    val watch by PlaybackManager.uiState.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()
    val phoneChapters by PlayerStateStore.chapters.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()

    val fromWatch = source == SOURCE_WATCH
    val itemId = if (fromWatch) watch.itemId else phone?.itemId
    val chapters: List<Chapter> = if (fromWatch) watch.chapters else phoneChapters?.takeIf { it.itemId == itemId }?.chapters.orEmpty()
    val positionMs = if (fromWatch) watch.positionMs else phone?.positionMs ?: 0L
    val speed = if (fromWatch) watch.speed else phone?.speed ?: 1f
    val timeScale = if (settings.speedAdjustedTime && speed > 0f) speed else 1f
    val colors = rememberCoverColors(creds, itemId)
    val current = chapters.indexOfLast { positionMs / 1000.0 >= it.start }

    // The phone's list arrives once per book; after this process was away
    // it has to be read back from the Data Layer's copy, or failing that
    // taken from a kept copy of the book or the server.
    var looked by remember { mutableStateOf(fromWatch) }
    LaunchedEffect(itemId) {
        if (fromWatch || itemId == null) return@LaunchedEffect
        if (phoneChapters?.itemId == itemId) {
            looked = true
            return@LaunchedEffect
        }
        if (!PlayerStateClient.seedChapters(context) || PlayerStateStore.chapters.value?.itemId != itemId) {
            val found = DownloadStore.books.value[itemId]?.session?.chapters?.takeIf { it.isNotEmpty() }
                ?: creds?.let { ChapterList.load(it, itemId) }
            if (found != null) PlayerStateStore.updateChapters(itemId, found)
        }
        looked = true
    }

    val listState = rememberScalingLazyListState(initialCenterItemIndex = (current + 1).coerceAtLeast(0))

    fun jump(chapter: Chapter) {
        if (fromWatch) {
            PlaybackManager.seekToSec(chapter.start)
        } else {
            scope.launch { commands.seekTo((chapter.start * 1000).toLong()) }
        }
        onBack()
    }

    LaunchedEffect(chapters.size) {
        if (current >= 0) listState.scrollToItem(current + 1)
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Chapters",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
        if (chapters.isEmpty()) {
            item {
                Text(
                    text = if (looked) "This book has no chapters" else "Finding the chapters",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                )
            }
        }
        itemsIndexed(chapters) { index, chapter ->
            val isCurrent = index == current
            Chip(
                onClick = { jump(chapter) },
                label = {
                    Text(
                        chapter.title?.takeIf { it.isNotBlank() } ?: "Chapter ${index + 1}",
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                secondaryLabel = {
                    val length = ((chapter.end - chapter.start) * 1000 / timeScale).toLong()
                    Text(formatDuration((chapter.start * 1000 / timeScale).toLong()) + (if (length > 0) " · ${formatDuration(length)}" else ""))
                },
                colors = if (isCurrent) ChipDefaults.chipColors(
                    backgroundColor = colors.accent,
                    contentColor = colors.onAccent,
                    secondaryContentColor = colors.onAccent.copy(alpha = 0.75f),
                ) else ChipDefaults.chipColors(
                    backgroundColor = colors.accentSoft.copy(alpha = 0.18f),
                    contentColor = Color.White,
                    secondaryContentColor = Color.White.copy(alpha = 0.65f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
