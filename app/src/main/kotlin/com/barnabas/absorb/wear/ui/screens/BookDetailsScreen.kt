package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Download
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyColumnDefaults
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.BookDetails
import com.barnabas.absorb.wear.api.BookDetailsLoader
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.download.keepKey
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.ui.components.BookCover
import com.barnabas.absorb.wear.ui.components.PlayOnPicker
import com.barnabas.absorb.wear.ui.components.PlayPauseButton
import com.barnabas.absorb.wear.ui.components.PlayTargetChip
import com.barnabas.absorb.wear.ui.components.PlayerRings
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import com.barnabas.absorb.wear.ui.components.rememberOutputName
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The book a list row was tapped for, handed to the details screen. */
object BookDetailsStore {
    val item = MutableStateFlow<InProgressItem?>(null)
}

/**
 * A book's page: its cover fills the screen, blurred and darkened under the
 * words, and its progress rings the edge. Title and author sit over the
 * cover with how long, how far and who reads it; then the round buttons
 * for playing here, playing on the phone and keeping it on the watch; then
 * the series, genres and year as pills, the description, and what it is
 * made of.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun BookDetailsScreen(
    creds: Credentials,
    onOpenPlayer: () -> Unit,
    onOpenPhonePlayer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val item by BookDetailsStore.item.collectAsState()
    // The title opens in the middle of the screen, clear of the round top.
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 0)
    val progress by LibraryRepository.progress.collectAsState()
    val target by PlaybackManager.target.collectAsState()
    val outputName = rememberOutputName()
    var pickerOpen by remember { mutableStateOf(false) }
    val downloads by BookDownloads.progress.collectAsState()
    val kept by DownloadStore.books.collectAsState()
    val downloadsOnly by PlaybackManager.downloadsOnly.collectAsState()
    val watch by PlaybackManager.uiState.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()
    var details by remember(item?.id) { mutableStateOf<BookDetails?>(null) }
    var downloadError by remember(item?.id, item?.episodeId) { mutableStateOf<String?>(null) }
    var descriptionOpen by remember(item?.id) { mutableStateOf(false) }

    val book = item ?: return
    val colors = rememberCoverColors(creds, book.id)
    val row = progress[book.id + (book.episodeId?.let { "-$it" } ?: "")]
    val keptKey = keepKey(book.id, book.episodeId)
    val download = downloads[keptKey]
    val isKept = kept.containsKey(keptKey)
    val keeping = download != null && !download.complete && !download.failed
    val durationSec = row?.durationSec?.takeIf { it > 0 } ?: book.media?.duration?.takeIf { it > 0 } ?: details?.durationSec ?: 0.0
    val loadedHere = watch.itemId == book.id && watch.episodeId == book.episodeId
    // The phone names no episode, only its title.
    val onPhone = phone?.hasBook == true && phone?.itemId == book.id &&
        (book.episodeId == null || phone?.title == book.displayTitle)
    val fraction = when {
        loadedHere && watch.durationMs > 0 -> watch.positionMs.toFloat() / watch.durationMs
        onPhone && (phone?.durationMs ?: 0L) > 0L -> phone!!.positionMs.toFloat() / phone!!.durationMs
        else -> row?.fraction ?: 0f
    }

    LaunchedEffect(book.id, creds.serverUrl) {
        val r = withContext(Dispatchers.IO) { BookDetailsLoader.load(creds, book.id) }
        if (r is AbsorbHttpClient.Result.Ok) details = r.value
    }

    fun playHere() {
        PlaybackManager.setTarget(PlaybackManager.PlayTarget.WATCH)
        if (!loadedHere) {
            PlaybackManager.select(context, creds, book.id, book.episodeId, book.displayTitle, book.displaySubtitle)
        }
        onOpenPlayer()
    }

    fun playOnPhone() {
        PlaybackManager.setTarget(PlaybackManager.PlayTarget.PHONE)
        if (onPhone) {
            onOpenPhonePlayer()
            return
        }
        PlaybackManager.select(context, creds, book.id, book.episodeId, book.displayTitle, book.displaySubtitle)
        onOpenPhonePlayer()
        PlaybackManager.handOffToPhone(context)
    }

    fun keep() {
        when {
            isKept -> BookDownloads.remove(keptKey)
            keeping -> Unit
            else -> scope.launch { BookDownloads.start(creds, book)?.let { downloadError = it } }
        }
    }

    Box(Modifier.fillMaxSize()) {
        BookCover(
            itemId = book.id,
            creds = creds,
            modifier = Modifier.fillMaxSize().blur(3.dp),
            cornerRadius = 0.dp,
        )
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to Color.Black.copy(alpha = 0.4f),
                        0.45f to Color.Black.copy(alpha = 0.72f),
                        1f to Color.Black.copy(alpha = 0.94f),
                    ),
                ),
        )

        PlayerRings(bookProgress = fraction, chapterProgress = null, colors = colors) {
            // Title, author and facts are separate rows so each shrinks and
            // fades at the round edge instead of running into it.
            ScalingLazyColumn(
                state = listState,
                scalingParams = ScalingLazyColumnDefaults.scalingParams(edgeScale = 0.4f, edgeAlpha = 0.2f),
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    Text(
                        text = book.displayTitle,
                        style = MaterialTheme.typography.title3,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp),
                    )
                }
                book.displaySubtitle?.let { subtitle ->
                    item {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.caption1,
                            color = Color.White.copy(alpha = 0.78f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                        )
                    }
                }
                val facts = buildList {
                    if (durationSec > 0) add(formatLength(durationSec))
                    when {
                        row?.finished == true -> add("finished")
                        fraction > 0f -> add("${(fraction * 100).roundToInt()}%")
                    }
                    details?.narrator?.let { add("read by $it") }
                }
                if (facts.isNotEmpty()) {
                    item {
                        Text(
                            text = facts.joinToString(" · "),
                            style = MaterialTheme.typography.caption3,
                            color = colors.accentSoft,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                        )
                    }
                }
                item {
                    // The same play button and device chip as the cards and
                    // the player: play goes where the chip says.
                    PlayPauseButton(
                        isPlaying = loadedHere && watch.isPlaying || onPhone && phone?.isPlaying == true,
                        colors = colors,
                        onClick = {
                            if (target == PlaybackManager.PlayTarget.PHONE) playOnPhone() else playHere()
                        },
                    )
                }
                item {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        PlayTargetChip(target = target, onClick = { pickerOpen = true })
                        Box(contentAlignment = Alignment.Center) {
                            Button(
                                onClick = { keep() },
                                modifier = Modifier.size(40.dp).clip(CircleShape),
                                colors = ButtonDefaults.buttonColors(
                                    backgroundColor = colors.accentSoft.copy(alpha = if (isKept) 0.5f else 0.3f),
                                    contentColor = Color.White,
                                ),
                            ) {
                                Icon(
                                    if (isKept || download?.complete == true) Icons.Rounded.Check else Icons.Rounded.Download,
                                    contentDescription = if (isKept) "Remove from watch" else "Keep on watch",
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                            if (keeping) {
                                CircularProgressIndicator(
                                    progress = download!!.fraction.coerceIn(0f, 1f),
                                    modifier = Modifier.size(46.dp),
                                    indicatorColor = colors.accentSoft,
                                    trackColor = colors.accentSoft.copy(alpha = 0.2f),
                                    strokeWidth = 3.dp,
                                )
                            }
                        }
                    }
                }
                val note = when {
                    downloadError != null -> downloadError
                    download?.failed == true -> "Download failed, tap the arrow to retry"
                    keeping -> "Keeping ${(download!!.fraction * 100).roundToInt()}%"
                    downloadsOnly && !isKept && download?.complete != true -> "Downloads only: keep it to play here"
                    isKept -> "Kept on the watch, tap the check to remove"
                    else -> null
                }
                if (note != null) {
                    item {
                        Text(
                            text = note,
                            style = MaterialTheme.typography.caption3,
                            color = if (downloadError != null || download?.failed == true) MaterialTheme.colors.error else Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp),
                        )
                    }
                }
                val pills = buildList {
                    details?.series?.let { add(it) }
                    details?.publishedYear?.let { add(it) }
                    details?.genres?.take(3)?.forEach { add(it) }
                }
                if (pills.isNotEmpty()) {
                    item {
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 2.dp),
                        ) {
                            for (pill in pills) {
                                CompactChip(
                                    onClick = { },
                                    label = { Text(pill, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    colors = ChipDefaults.chipColors(
                                        backgroundColor = colors.accentSoft.copy(alpha = 0.2f),
                                        contentColor = Color.White.copy(alpha = 0.9f),
                                    ),
                                )
                            }
                        }
                    }
                }
                val description = details?.description
                if (description != null) {
                    item {
                        var overflowed by remember { mutableStateOf(false) }
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .clickable { descriptionOpen = !descriptionOpen },
                        ) {
                            Text(
                                text = description,
                                style = MaterialTheme.typography.caption2,
                                color = Color.White.copy(alpha = 0.85f),
                                textAlign = TextAlign.Start,
                                maxLines = if (descriptionOpen) Int.MAX_VALUE else 4,
                                overflow = TextOverflow.Ellipsis,
                                onTextLayout = { overflowed = it.hasVisualOverflow },
                            )
                            if (overflowed || descriptionOpen) {
                                Text(
                                    text = if (descriptionOpen) "less" else "more",
                                    style = MaterialTheme.typography.caption3,
                                    color = colors.accentSoft,
                                    modifier = Modifier.padding(top = 2.dp),
                                )
                            }
                        }
                    }
                }
                val made = buildList {
                    details?.chapterCount?.takeIf { it > 0 }?.let { add("$it chapters") }
                    details?.sizeBytes?.takeIf { it > 0 }?.let { add(formatSize(it)) }
                    details?.publisher?.let { add(it) }
                }
                if (made.isNotEmpty()) {
                    item {
                        Text(
                            text = made.joinToString(" · "),
                            style = MaterialTheme.typography.caption3,
                            color = Color.White.copy(alpha = 0.5f),
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                        )
                    }
                }
                item { Spacer(Modifier.height(12.dp)) }
            }
        }
    }

    PlayOnPicker(
        open = pickerOpen,
        onDismiss = { pickerOpen = false },
        target = target,
        outputName = outputName,
        onWatch = { },
        onPhone = { },
    )
}

/** "9h 42m" style length for a book. */
internal fun formatLength(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = (total % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${total}s"
    }
}

internal fun formatSize(bytes: Long): String {
    val mb = bytes / 1_048_576.0
    return if (mb >= 1000) "%.1f GB".format(mb / 1024) else "${mb.roundToInt()} MB"
}
