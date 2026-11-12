package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.BookmarkAdd
import androidx.compose.material.icons.rounded.Check
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
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.Bookmark
import com.barnabas.absorb.wear.api.BookmarkRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The playing book's bookmarks from the server, newest first, each a jump
 * to its spot, with a way to add one here. The phone keeps its bookmarks on
 * the same server, so the list is the same on both.
 */
@Composable
fun BookmarksScreen(creds: Credentials, source: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember { PlayerCommandClient(context) }
    val repo = remember(creds.serverUrl) { BookmarkRepository(creds) }
    val watch by PlaybackManager.uiState.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()
    val cache by BookmarkRepository.cache.collectAsState()
    val phoneBookmarks by PlayerStateStore.bookmarks.collectAsState()
    val listState = rememberScalingLazyListState()

    val fromWatch = source == SOURCE_WATCH
    val itemId = if (fromWatch) watch.itemId else phone?.itemId
    val positionMs = if (fromWatch) watch.positionMs else phone?.positionMs ?: 0L
    val chapterTitle = if (fromWatch) watch.chapterTitle else phone?.chapter
    val speed = if (fromWatch) watch.speed else phone?.speed ?: 1f
    val timeScale = if (settings.speedAdjustedTime && speed > 0f) speed else 1f
    val colors = rememberCoverColors(creds, itemId)

    var error by remember { mutableStateOf<String?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var reload by remember { mutableStateOf(0) }
    var saved by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(saved) {
        if (saved == null) return@LaunchedEffect
        delay(2_000)
        saved = null
    }

    LaunchedEffect(itemId, reload) {
        val id = itemId ?: return@LaunchedEffect
        error = null
        when (val r = withContext(Dispatchers.IO) { repo.list(id) }) {
            is AbsorbHttpClient.Result.Ok -> Unit
            is AbsorbHttpClient.Result.Unauthorized -> error = "Sign in again (HTTP ${r.code})"
            is AbsorbHttpClient.Result.Failed -> if (cache[id] == null) error = r.message
        }
        loaded = true
    }

    fun jump(b: Bookmark) {
        if (fromWatch) {
            PlaybackManager.seekToSec(b.timeSec)
        } else {
            scope.launch { commands.seekTo((b.timeSec * 1000).toLong()) }
        }
        onBack()
    }

    fun addHere() {
        val id = itemId ?: return
        scope.launch {
            if (fromWatch) {
                val r = withContext(Dispatchers.IO) { repo.add(id, positionMs / 1000.0, chapterTitle?.takeIf { it.isNotBlank() } ?: "Bookmark") }
                saved = if (r is AbsorbHttpClient.Result.Ok) "Saved" else "Couldn't save"
            } else {
                commands.bookmark()
                saved = "Saved"
                delay(1_500)
                reload++
            }
        }
    }

    // For the phone's book the phone's own list wins: it holds the server's
    // bookmarks and the ones kept on the phone when the server has no place
    // for them (a narrated EPUB on BookOrbit), and it arrives at once.
    val bookmarks = itemId?.let { id ->
        val fromPhone = phoneBookmarks?.takeIf { !fromWatch && it.itemId == id }
        fromPhone?.bookmarks ?: cache[id]
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Bookmarks",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
        item {
            Chip(
                onClick = { addHere() },
                enabled = itemId != null && saved == null,
                label = { Text(saved ?: "Bookmark here") },
                secondaryLabel = { Text(formatDuration((positionMs / timeScale).toLong())) },
                icon = {
                    Icon(
                        if (saved == "Saved") Icons.Rounded.Check else Icons.Rounded.BookmarkAdd,
                        contentDescription = null,
                        modifier = Modifier.size(ChipDefaults.IconSize),
                    )
                },
                colors = ChipDefaults.chipColors(
                    backgroundColor = colors.accent,
                    contentColor = colors.onAccent,
                    secondaryContentColor = colors.onAccent.copy(alpha = 0.75f),
                    iconColor = colors.onAccent,
                ),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        when {
            bookmarks == null && error == null && !loaded -> item {
                ExpressiveSpinner(modifier = Modifier.size(28.dp).padding(top = 6.dp), color = colors.accentSoft)
            }
            bookmarks == null -> item {
                Chip(
                    onClick = { reload++ },
                    label = { Text(error ?: "Couldn't reach the server") },
                    secondaryLabel = { Text("Tap to try again") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            bookmarks.isEmpty() -> item {
                Text(
                    text = "No bookmarks in this book yet",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                )
            }
            else -> items(bookmarks, key = { "${it.id}-${it.timeSec}-${it.createdAtMs}" }) { b ->
                Chip(
                    onClick = { jump(b) },
                    label = { Text(b.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(formatDuration((b.timeSec * 1000 / timeScale).toLong())) },
                    colors = ChipDefaults.chipColors(
                        backgroundColor = colors.accentSoft.copy(alpha = 0.18f),
                        contentColor = Color.White,
                        secondaryContentColor = Color.White.copy(alpha = 0.65f),
                    ),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
