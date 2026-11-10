package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.ExperimentalFoundationApi
import kotlinx.coroutines.launch
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.HorizontalPageIndicator
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PageIndicatorState
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.AbsorbWearApp
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.InProgressRepository
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.download.keepKey
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.PlayerState
import com.barnabas.absorb.wear.playback.PlaybackUiState
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.ui.components.BookCover
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import com.barnabas.absorb.wear.ui.components.PlayOnPicker
import com.barnabas.absorb.wear.ui.components.PlayTargetChip
import com.barnabas.absorb.wear.ui.components.rememberOutputName
import com.barnabas.absorb.wear.ui.components.PlayerRings
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * Home as a deck of cards: the library first (shelves, search, settings),
 * then one card per book in progress, newest first, each a still of the
 * player face with the book's saved spot from the server; a tap sends it
 * to the chosen device. The card of the book loaded on the watch is the
 * watch player itself, and the card of the phone's book the phone remote,
 * so starting a book brings its card forward and the home page stays one
 * swipe to the left. The app opens on the playing book, else the first card.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalWearFoundationApi::class)
@Composable
fun CardsHomeScreen(
    creds: Credentials,
    onOpenShelf: (String) -> Unit,
    onOpenLists: () -> Unit,
    onOpenSearch: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenChapters: (String) -> Unit,
    onOpenBookmarks: (String) -> Unit,
    onOpenWatchDetails: () -> Unit,
    onOpenPhoneDetails: () -> Unit,
    onOpenSleep: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val network by AbsorbWearApp.networkGeneration.collectAsState()
    val loaded by remember {
        PlaybackManager.uiState.map { it.toActiveBook() }.distinctUntilChanged()
    }.collectAsState(initial = PlaybackManager.uiState.value.toActiveBook())
    val phoneBook by remember {
        PlayerStateStore.state.map { it?.toActiveBook() }.distinctUntilChanged()
    }.collectAsState(initial = PlayerStateStore.state.value?.toActiveBook())
    val playing = loaded?.itemId

    var items by remember(creds.serverUrl) { mutableStateOf<List<InProgressItem>?>(null) }
    var error by remember(creds.serverUrl) { mutableStateOf<String?>(null) }
    var reload by remember { mutableStateOf(0) }
    val progress by LibraryRepository.progress.collectAsState()
    val selectedLibrary by LibraryRepository.selectedLibrary.collectAsState()
    LaunchedEffect(Unit) { LibraryRepository.restoreSelection(context) }

    // Fresh from the server when the screen opens, when a network comes
    // back, after a book played here (its spot moved), and when the
    // library changes.
    LaunchedEffect(creds.serverUrl, creds.accessToken, network, playing == null, reload, selectedLibrary?.id) {
        val repo = InProgressRepository(creds)
        val lib = LibraryRepository(creds)
        withContext(Dispatchers.IO) {
            when (val r = repo.fetch(20)) {
                is AbsorbHttpClient.Result.Ok -> { items = r.value; error = null }
                is AbsorbHttpClient.Result.Unauthorized -> error = "Sign in again (HTTP ${r.code})"
                is AbsorbHttpClient.Result.Failed -> if (items == null) error = r.message
            }
            (lib.progress() as? AbsorbHttpClient.Result.Ok)?.let { LibraryRepository.progress.value = it.value }
            (lib.shelves() as? AbsorbHttpClient.Result.Ok)?.let { LibraryRepository.shelves.value = it.value }
            if (LibraryRepository.libraries.value == null) {
                (lib.libraries() as? AbsorbHttpClient.Result.Ok)?.let { LibraryRepository.libraries.value = it.value }
            }
        }
    }

    // Only the chosen library's books, when the server says which library
    // a book is in; BookOrbit's continue list is not split by library.
    val libraryId = selectedLibrary?.takeIf { it.serverUrl == creds.serverUrl }?.id
    val listed = items.orEmpty().let { all ->
        if (libraryId == null || creds.isBookOrbit) all
        else all.filter { it.libraryId == null || it.libraryId == libraryId }
    }.take(10)
    // A playing book the server's list does not have yet (just started
    // from a shelf, or the phone's) gets a card after the others, so the
    // cards already in hand keep their places.
    val cards = buildList {
        addAll(listed)
        loaded?.takeIf { l -> listed.none { it.id == l.itemId } }?.let { add(it.asItem()) }
        phoneBook?.takeIf { b -> b.itemId != loaded?.itemId && listed.none { it.id == b.itemId } }?.let { add(it.asItem()) }
    }
    val pageCount = cards.size + 1
    fun pageOf(itemId: String?): Int? =
        itemId?.let { id -> cards.indexOfFirst { it.id == id }.takeIf { it >= 0 }?.plus(1) }
    // The library is the first page, the cards run off to the right, and
    // the app opens on the playing book's card, else the first, once the
    // books are known.
    val pagerState = rememberPagerState(pageCount = { pageCount })
    var landed by rememberSaveable(creds.serverUrl) { mutableStateOf(false) }
    LaunchedEffect(cards.isNotEmpty()) {
        if (!landed && cards.isNotEmpty()) {
            landed = true
            if (pagerState.currentPage == 0) pagerState.scrollToPage(pageOf(loaded?.itemId) ?: pageOf(phoneBook?.itemId) ?: 1)
        }
    }
    // A book starting on the watch brings its card, the player, forward.
    // Only on a change: coming back from another screen stays put.
    var lastLoaded by rememberSaveable { mutableStateOf(loaded?.itemId) }
    LaunchedEffect(loaded?.itemId) {
        val id = loaded?.itemId
        if (id == lastLoaded) return@LaunchedEffect
        lastLoaded = id
        val page = pageOf(id) ?: return@LaunchedEffect
        if (pagerState.currentPage != page) pagerState.animateScrollToPage(page)
    }
    // Wear OS opened the app for the phone's session: its card, the remote.
    val phoneRequest by AbsorbWearApp.phonePlayerRequests.collectAsState()
    var lastPhoneRequest by rememberSaveable { mutableStateOf(phoneRequest) }
    LaunchedEffect(phoneRequest, phoneBook?.itemId) {
        if (phoneRequest == lastPhoneRequest) return@LaunchedEffect
        val page = pageOf(phoneBook?.itemId) ?: return@LaunchedEffect
        lastPhoneRequest = phoneRequest
        if (pagerState.currentPage != page) pagerState.animateScrollToPage(page)
    }
    val showPhone: () -> Unit = {
        pageOf(phoneBook?.itemId)?.let { page -> scope.launch { pagerState.animateScrollToPage(page) } }
    }
    val indicatorState = remember(pagerState) {
        object : PageIndicatorState {
            override val pageOffset: Float get() = pagerState.currentPageOffsetFraction
            override val selectedPage: Int get() = pagerState.currentPage
            override val pageCount: Int get() = pagerState.pageCount
        }
    }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colors.background)) {
        HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize(), beyondViewportPageCount = 1) { page ->
            // Each page owns the crown only while it is the one on screen.
            HierarchicalFocusCoordinator(requiresFocus = { pagerState.currentPage == page }) {
                if (page > 0) {
                    val item = cards[page - 1]
                    val here = loaded?.let { it.itemId == item.id && it.episodeId == item.episodeId } == true
                    // The phone names no episode, only its title.
                    val onPhone = !here && phoneBook?.let {
                        it.itemId == item.id && (item.episodeId == null || it.title == item.displayTitle)
                    } == true
                    when {
                        here -> NowPlayingScreen(
                            creds = creds,
                            onOpenPhonePlayer = {},
                            onOpenChapters = { onOpenChapters(SOURCE_WATCH) },
                            onOpenBookmarks = { onOpenBookmarks(SOURCE_WATCH) },
                            onOpenDetails = onOpenWatchDetails,
                            onOpenSleep = { onOpenSleep(SOURCE_WATCH) },
                        )
                        onPhone -> PhonePlayerScreen(
                            creds = creds,
                            onOpenWatchPlayer = {},
                            onOpenChapters = { onOpenChapters(SOURCE_PHONE) },
                            onOpenBookmarks = { onOpenBookmarks(SOURCE_PHONE) },
                            onOpenDetails = onOpenPhoneDetails,
                            onOpenSleep = { onOpenSleep(SOURCE_PHONE) },
                        )
                        else -> BookCard(
                            item = item,
                            creds = creds,
                            row = progress[item.id + (item.episodeId?.let { "-$it" } ?: "")],
                        )
                    }
                } else {
                    BrowsePage(
                        creds = creds,
                        loading = items == null && error == null,
                        error = if (items == null) error else null,
                        onRetry = { reload++ },
                        onOpenShelf = onOpenShelf,
                        onOpenLists = onOpenLists,
                        onOpenSearch = onOpenSearch,
                        onOpenSettings = onOpenSettings,
                        onOpenPhonePlayer = showPhone,
                    )
                }
            }
        }
        if (pageCount > 1) {
            HorizontalPageIndicator(pageIndicatorState = indicatorState, modifier = Modifier.padding(bottom = 2.dp))
        }
    }
}

/** A book playing on the watch or the phone, as the cards know it. */
private data class ActiveBook(
    val itemId: String,
    val episodeId: String?,
    val title: String?,
    val author: String?,
    val durationMs: Long,
) {
    fun asItem() = InProgressItem(
        id = itemId,
        media = InProgressItem.Media(
            metadata = InProgressItem.Metadata(title = title, authorName = author),
            duration = durationMs / 1000.0,
        ),
        recentEpisode = episodeId?.let { InProgressItem.RecentEpisode(id = it) },
    )
}

private fun PlaybackUiState.toActiveBook(): ActiveBook? =
    itemId?.let { ActiveBook(it, episodeId, title, author, durationMs) }

private fun PlayerState.toActiveBook(): ActiveBook? =
    itemId?.takeIf { hasBook }?.let { ActiveBook(it, null, title, author, durationMs) }

/** One book as a still of the player face: a tap sends it to the chosen
 *  device, and its card becomes that player. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookCard(
    item: InProgressItem,
    creds: Credentials,
    row: LibraryRepository.ProgressRow?,
) {
    val context = LocalContext.current
    val colors = rememberCoverColors(creds, item.id)
    val state by PlaybackManager.uiState.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()
    val starting by PlayerStateStore.starting.collectAsState()
    val downloads by BookDownloads.progress.collectAsState()
    val keptBooks by DownloadStore.books.collectAsState()
    val target by PlaybackManager.target.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()
    val outputName = rememberOutputName()
    var pickerOpen by remember { mutableStateOf(false) }
    // Handed to the phone, which hasn't reported the book yet.
    val startingOnPhone = starting?.isFor(item.id, item.displayTitle) == true

    fun play() {
        val toPhone = PlaybackManager.target.value == PlaybackManager.PlayTarget.PHONE
        if (toPhone && startingOnPhone) return
        if (!toPhone) PlayerStateStore.cancelStart()
        PlaybackManager.select(context, creds, item.id, item.episodeId, item.displayTitle, item.displaySubtitle)
        if (toPhone) PlaybackManager.handOffToPhone(context)
    }

    val loadedHere = state.itemId == item.id && state.episodeId == item.episodeId
    val onPhone = !loadedHere && phone?.hasBook == true && phone?.itemId == item.id &&
        (item.episodeId == null || phone?.title == item.displayTitle)
    val download = downloads[keepKey(item.id, item.episodeId)]
    val kept = keptBooks.containsKey(keepKey(item.id, item.episodeId))
    val fraction = if (loadedHere && state.durationMs > 0) state.positionMs.toFloat() / state.durationMs
    else if (onPhone && (phone?.durationMs ?: 0L) > 0L) phone!!.positionMs.toFloat() / phone!!.durationMs
    else row?.fraction ?: 0f
    val positionMs = if (loadedHere) state.positionMs else if (onPhone) phone!!.positionMs else ((row?.currentTimeSec ?: 0.0) * 1000).toLong()
    val durationMs = if (loadedHere) state.durationMs else if (onPhone) phone!!.durationMs else ((row?.durationSec ?: item.media?.duration ?: 0.0) * 1000).toLong()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { play() },
    ) {
        BookCover(itemId = item.id, creds = creds, modifier = Modifier.fillMaxSize().blur(10.dp), cornerRadius = 0.dp)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.66f)))

        PlayerRings(bookProgress = fraction, chapterProgress = null, colors = colors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = item.displaySubtitle.orEmpty(),
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center,
                )
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().basicMarquee(),
                )
                Spacer(Modifier.height(4.dp))
                val status = when {
                    startingOnPhone -> "Starting on phone"
                    onPhone -> if (phone?.isPlaying == true) "Playing on phone" else "Paused on phone"
                    loadedHere && state.isPlaying -> "Playing here"
                    loadedHere -> "Paused here"
                    row?.finished == true -> "Finished"
                    durationMs <= 0L -> "Not started"
                    else -> {
                        val speed = if (onPhone) phone?.speed ?: 1f else state.speed
                        val timeScale = if (settings.speedAdjustedTime && speed > 0f) speed else 1f
                        "${(fraction * 100).roundToInt()}% · ${formatDuration((positionMs / timeScale).toLong())} / ${formatDuration((durationMs / timeScale).toLong())}"
                    }
                }
                Text(
                    text = status,
                    style = MaterialTheme.typography.caption3,
                    color = if (startingOnPhone || onPhone || loadedHere) colors.accentSoft else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                )
                Spacer(Modifier.height(14.dp))
                if (startingOnPhone) {
                    Box(Modifier.height(32.dp), contentAlignment = Alignment.Center) {
                        ExpressiveSpinner(modifier = Modifier.size(30.dp), color = colors.accentSoft)
                    }
                } else {
                    PlayTargetChip(target = target, onClick = { pickerOpen = true })
                }
                Spacer(Modifier.height(4.dp))
                val keptLine = when {
                    download?.failed == true -> "Download failed"
                    download?.complete == true || kept && download == null -> "Downloaded"
                    download != null -> "Keeping ${(download.fraction * 100).roundToInt()}%"
                    else -> null
                }
                if (keptLine != null) {
                    Text(
                        text = keptLine,
                        style = MaterialTheme.typography.caption3,
                        color = if (download?.failed == true) MaterialTheme.colors.error
                        else MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                    )
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

        // A download under way shows its progress at the top of the card.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            when {
                download != null && !download.complete && !download.failed -> {
                    Box(contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            progress = download.fraction.coerceIn(0f, 1f),
                            modifier = Modifier.size(30.dp),
                            indicatorColor = colors.accentSoft,
                            trackColor = colors.accentSoft.copy(alpha = 0.2f),
                            strokeWidth = 3.dp,
                        )
                        if (download.fraction <= 0f) ExpressiveSpinner(modifier = Modifier.size(14.dp), color = colors.accentSoft)
                    }
                }
            }
        }
    }
}
