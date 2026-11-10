package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.ui.components.CoverCard
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import com.barnabas.absorb.wear.ui.components.GlowBackdrop
import com.barnabas.absorb.wear.ui.components.ShelfCard

/**
 * The first page: the library, in the phone app's manner. A glow in the
 * app's colour at the top, the library's name as the title (a switch when
 * there is more than one), round buttons for search, the books kept on
 * the watch and settings, the phone's book as a cover card, a card per
 * shelf with a fan of its covers, and the server's lists last.
 */
@Composable
fun BrowsePage(
    creds: Credentials,
    loading: Boolean,
    error: String?,
    onRetry: () -> Unit,
    onOpenShelf: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenPhonePlayer: () -> Unit,
) {
    val context = LocalContext.current
    val shelves by LibraryRepository.shelves.collectAsState()
    val libraries by LibraryRepository.libraries.collectAsState()
    val selectedLibrary by LibraryRepository.selectedLibrary.collectAsState()
    var libraryPickerOpen by remember { mutableStateOf(false) }
    val phone by PlayerStateStore.state.collectAsState()
    val listState = rememberScalingLazyListState()
    val tint = MaterialTheme.colors.primary

    Box(Modifier.fillMaxSize()) {
        GlowBackdrop()

        ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            item {
                val libs = libraries.orEmpty()
                val current = selectedLibrary?.takeIf { it.serverUrl == creds.serverUrl }?.name
                    ?: libs.firstOrNull()?.name
                    ?: "Library"
                val switchable = libs.size > 1
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 28.dp)
                        .clip(CircleShape)
                        .clickable(enabled = switchable) { libraryPickerOpen = true }
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = current,
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (switchable) {
                        Icon(
                            Icons.Rounded.ExpandMore,
                            contentDescription = "Switch library",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    RoundIconButton(Icons.Rounded.Search, "Search", onOpenSearch)
                    KeptButton { onOpenShelf(SHELF_KEPT) }
                    RoundIconButton(Icons.Rounded.Settings, "Settings", onOpenSettings)
                }
            }
            val p = phone
            if (p != null && p.hasBook && p.itemId != null) {
                item {
                    CoverCard(
                        itemId = p.itemId,
                        creds = creds,
                        title = p.title ?: "On the phone",
                        subtitle = if (p.isPlaying) "Playing on phone" else "Paused on phone",
                        fraction = if (p.durationMs > 0) p.positionMs.toFloat() / p.durationMs else null,
                        modifier = Modifier.padding(horizontal = 6.dp),
                        onClick = onOpenPhonePlayer,
                    )
                }
            }
            val list = shelves
            when {
                list == null && loading -> item {
                    ExpressiveSpinner(modifier = Modifier.size(28.dp).padding(top = 6.dp), color = tint)
                }
                list == null && error != null -> item {
                    Chip(
                        onClick = onRetry,
                        label = { Text("Couldn't reach the server") },
                        secondaryLabel = { Text("Tap to try again") },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                list != null && list.isEmpty() -> item {
                    Text(
                        text = "No shelves on this server",
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                list != null -> items(list, key = { it.id }) { shelf ->
                    ShelfCard(
                        label = shelf.label,
                        caption = countLabel(shelf.items.size),
                        coverIds = shelf.items.map { it.id },
                        creds = creds,
                        modifier = Modifier.padding(horizontal = 6.dp),
                        onClick = { onOpenShelf(shelf.id) },
                    )
                }
            }
            item {
                ShelfCard(
                    label = "Lists",
                    caption = if (creds.isBookOrbit) "Collections" else "Playlists and collections",
                    coverIds = emptyList(),
                    creds = creds,
                    icon = Icons.AutoMirrored.Rounded.List,
                    modifier = Modifier.padding(horizontal = 6.dp),
                    onClick = onOpenLists,
                )
            }
        }
    }

    LibraryPicker(
        open = libraryPickerOpen,
        onDismiss = { libraryPickerOpen = false },
        libraries = libraries.orEmpty(),
        selectedId = selectedLibrary?.takeIf { it.serverUrl == creds.serverUrl }?.id ?: libraries?.firstOrNull()?.id,
        onPick = { lib -> LibraryRepository.select(context, creds.serverUrl, lib) },
    )
}

const val SHELF_KEPT = "kept"

private fun countLabel(n: Int) = "$n ${if (n == 1) "book" else "books"}"

@Composable
private fun RoundIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    val tint = MaterialTheme.colors.primary
    Button(
        onClick = onClick,
        modifier = Modifier.size(46.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = tint.copy(alpha = 0.22f),
            contentColor = Color.White,
        ),
    ) {
        Icon(icon, contentDescription = description, modifier = Modifier.size(22.dp))
    }
}

/** The watch with a download arrow in its corner: the books kept on it. */
@Composable
private fun KeptButton(onClick: () -> Unit) {
    val tint = MaterialTheme.colors.primary
    Button(
        onClick = onClick,
        modifier = Modifier.size(46.dp),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = tint.copy(alpha = 0.22f),
            contentColor = Color.White,
        ),
    ) {
        Box(modifier = Modifier.size(27.dp)) {
            Icon(
                Icons.Rounded.Watch,
                contentDescription = "On this watch",
                modifier = Modifier.size(22.dp).align(Alignment.TopStart),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .size(15.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colors.background),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(12.dp))
            }
        }
    }
}

/** Which library the watch looks at, one chip per library. */
@Composable
private fun LibraryPicker(
    open: Boolean,
    onDismiss: () -> Unit,
    libraries: List<LibraryRepository.Library>,
    selectedId: String?,
    onPick: (LibraryRepository.Library) -> Unit,
) {
    Dialog(showDialog = open, onDismissRequest = onDismiss) {
        Alert(title = { Text("Library", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth()) }) {
            items(libraries, key = { it.id }) { lib ->
                Chip(
                    onClick = { onDismiss(); onPick(lib) },
                    label = { Text(lib.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    colors = if (lib.id == selectedId) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
