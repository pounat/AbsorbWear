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
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.api.PodcastEpisodes
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.download.keepKey
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext

/** The podcast whose episodes [EpisodesScreen] lists. */
object PodcastShowStore {
    val item = MutableStateFlow<InProgressItem?>(null)
}

/**
 * A podcast's episodes, newest first, each with how far along it is or
 * when it came out. A tap opens the episode's page, which plays it or
 * keeps it like a book.
 */
@Composable
fun EpisodesScreen(creds: Credentials, onOpenEpisode: (InProgressItem) -> Unit) {
    val show by PodcastShowStore.item.collectAsState()
    val podcast = show ?: return
    val listState = rememberScalingLazyListState()
    val progress by LibraryRepository.progress.collectAsState()
    val kept by DownloadStore.books.collectAsState()
    var episodes by remember(podcast.id) { mutableStateOf<List<PodcastEpisodes.Episode>?>(null) }
    var error by remember(podcast.id) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(podcast.id, creds.serverUrl, reload) {
        error = null
        when (val r = withContext(Dispatchers.IO) { PodcastEpisodes.load(creds, podcast.id) }) {
            is AbsorbHttpClient.Result.Ok -> episodes = r.value
            is AbsorbHttpClient.Result.Unauthorized -> error = "Sign in again (HTTP ${r.code})"
            is AbsorbHttpClient.Result.Failed -> if (episodes == null) error = r.message
        }
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = podcast.displayTitle,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().padding(bottom = 2.dp),
            )
        }
        val list = episodes
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
                    text = "No episodes yet",
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            else -> items(list, key = { it.id }) { ep ->
                val row = progress["${podcast.id}-${ep.id}"]
                val length = ep.lengthSec.takeIf { it > 0 }?.let { formatLength(it) }
                val status = when {
                    row?.finished == true -> "Finished"
                    row != null && row.fraction > 0f -> listOfNotNull("${(row.fraction * 100).roundToInt()}%", length).joinToString(" · ")
                    else -> listOfNotNull(ep.publishedAt?.let(::formatPublished), length).joinToString(" · ")
                }
                val onWatch = kept.containsKey(keepKey(podcast.id, ep.id))
                Chip(
                    onClick = { onOpenEpisode(podcast.asEpisode(ep)) },
                    label = { Text(ep.title ?: "Untitled", maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    secondaryLabel = { Text(if (onWatch) "$status · On watch" else status, maxLines = 1) },
                    colors = if (row?.finished == true) ChipDefaults.secondaryChipColors() else ChipDefaults.primaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** One of this podcast's episodes, as the rest of the app knows a book. */
private fun InProgressItem.asEpisode(ep: PodcastEpisodes.Episode) = copy(
    mediaType = "podcast",
    media = InProgressItem.Media(metadata = media?.metadata, duration = ep.lengthSec),
    recentEpisode = InProgressItem.RecentEpisode(id = ep.id, title = ep.title),
)

/** "Oct 28", with the year when it isn't this one. */
private fun formatPublished(ms: Long): String {
    val date = Instant.ofEpochMilli(ms).atZone(ZoneId.systemDefault()).toLocalDate()
    val pattern = if (date.year == java.time.LocalDate.now().year) "MMM d" else "MMM d, yyyy"
    return DateTimeFormatter.ofPattern(pattern, Locale.getDefault()).format(date)
}
