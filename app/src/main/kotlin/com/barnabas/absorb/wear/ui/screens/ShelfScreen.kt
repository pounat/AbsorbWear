package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.download.keepKey
import com.barnabas.absorb.wear.ui.components.BookRow

/**
 * One shelf's books, or the books kept on the watch: a card per book with
 * its cover and how far along it is; a tap opens the book's page. A kept
 * book's card also has a button to take it off the watch, behind a
 * confirmation.
 */
@Composable
fun ShelfScreen(creds: Credentials, shelfId: String, onOpenBook: (InProgressItem) -> Unit) {
    val frontShelves by LibraryRepository.shelves.collectAsState()
    val lists by LibraryRepository.lists.collectAsState()
    val shelves = frontShelves.orEmpty() + lists.orEmpty()
    val kept by DownloadStore.books.collectAsState()
    val progressRows by LibraryRepository.progress.collectAsState()
    val listState = rememberScalingLazyListState()
    var removing by remember { mutableStateOf<InProgressItem?>(null) }

    val isKept = shelfId == SHELF_KEPT
    val title = if (isKept) "On this watch" else shelves.firstOrNull { it.id == shelfId }?.label ?: "Shelf"
    val items: List<InProgressItem> = if (isKept) {
        kept.values.filter { it.serverUrl == creds.serverUrl }.sortedByDescending { it.addedAtMs }.map { b ->
            if (b.episodeId != null) {
                // An episode keeps its own title; its podcast's is what it shows under.
                InProgressItem(
                    id = b.itemId,
                    mediaType = "podcast",
                    media = InProgressItem.Media(metadata = InProgressItem.Metadata(title = b.author), duration = b.session.duration),
                    recentEpisode = InProgressItem.RecentEpisode(id = b.episodeId, title = b.title),
                )
            } else {
                InProgressItem(id = b.itemId, media = InProgressItem.Media(metadata = InProgressItem.Metadata(title = b.title, authorName = b.author)))
            }
        }
    } else {
        shelves.firstOrNull { it.id == shelfId }?.items.orEmpty()
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = title,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
        if (items.isEmpty()) {
            item {
                Text(
                    text = if (isKept) "Nothing kept yet" else "Nothing here",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        items(items, key = { it.id + (it.episodeId ?: "") }) { item ->
            BookRow(
                item = item,
                creds = creds,
                fraction = progressRows[item.id + (item.episodeId?.let { "-$it" } ?: "")]?.let { if (it.finished) 1f else it.fraction },
                modifier = Modifier.padding(horizontal = 6.dp),
                onRemove = if (isKept) ({ removing = item }) else null,
                onClick = { onOpenBook(item) },
            )
        }
    }

    val target = removing
    Dialog(showDialog = target != null, onDismissRequest = { removing = null }) {
        if (target != null) {
            Alert(
                title = {
                    Text("Remove from this watch?", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                },
            ) {
                item {
                    Text(
                        text = target.displayTitle,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
                    )
                }
                item {
                    Chip(
                        onClick = {
                            BookDownloads.remove(keepKey(target.id, target.episodeId))
                            removing = null
                        },
                        label = { Text("Remove") },
                        colors = ChipDefaults.primaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    Chip(
                        onClick = { removing = null },
                        label = { Text("Keep it") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}
