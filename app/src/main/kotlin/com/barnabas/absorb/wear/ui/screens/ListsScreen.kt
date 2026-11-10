package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Playlists and collections, one chip each, fetched when the page opens. */
@Composable
fun ListsScreen(creds: Credentials, onOpenShelf: (String) -> Unit) {
    val listState = rememberScalingLazyListState()
    val lists by LibraryRepository.lists.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(creds.serverUrl, reload) {
        error = null
        when (val r = withContext(Dispatchers.IO) { LibraryRepository(creds).lists() }) {
            is AbsorbHttpClient.Result.Ok -> LibraryRepository.lists.value = r.value
            is AbsorbHttpClient.Result.Unauthorized -> error = "Sign in again (HTTP ${r.code})"
            is AbsorbHttpClient.Result.Failed -> if (lists == null) error = r.message
        }
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = "Lists",
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
        val list = lists
        when {
            list == null && error == null -> item {
                ExpressiveSpinner(modifier = Modifier.size(28.dp).padding(top = 6.dp), color = MaterialTheme.colors.primary)
            }
            list == null -> item {
                Chip(
                    onClick = { reload++ },
                    label = { Text(error ?: "Couldn't reach the server") },
                    secondaryLabel = { Text("Tap to try again") },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            list.isEmpty() -> item {
                Text(
                    text = "No playlists or collections yet",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> items(list, key = { it.id }) { shelf ->
                Chip(
                    onClick = { onOpenShelf(shelf.id) },
                    label = { Text(shelf.label, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = {
                        Text(
                            (if (shelf.id.startsWith("playlist:")) "Playlist · " else "Collection · ") +
                                "${shelf.items.size} ${if (shelf.items.size == 1) "book" else "books"}",
                        )
                    },
                    colors = ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
