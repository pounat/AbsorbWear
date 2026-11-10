package com.barnabas.absorb.wear.ui.screens

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.focusable
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
import androidx.compose.material.icons.Icons
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.ExperimentalWearFoundationApi
import androidx.wear.compose.foundation.rememberActiveFocusRequester
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.compose.material.icons.rounded.Bedtime
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.material.TimeTextDefaults
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.playback.PlaybackManager.PlayTarget
import com.barnabas.absorb.wear.player.AudioOutput
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PhoneAuthClient
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.barnabas.absorb.wear.ui.components.BookCover
import com.barnabas.absorb.wear.ui.components.FaceAction
import com.barnabas.absorb.wear.ui.components.FaceActionChip
import com.barnabas.absorb.wear.ui.components.FaceActionStore
import com.barnabas.absorb.wear.ui.components.PlayerActions
import com.barnabas.absorb.wear.ui.components.PlayOnPicker
import com.barnabas.absorb.wear.ui.components.PlayPauseButton
import com.barnabas.absorb.wear.ui.components.PlayerRings
import com.barnabas.absorb.wear.ui.components.rememberOutputName
import com.barnabas.absorb.wear.ui.components.SkipButton
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import com.barnabas.absorb.wear.ui.components.PlayerMorePage
import com.barnabas.absorb.wear.ui.components.sleepLabel
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.BookmarkRepository
import com.barnabas.absorb.wear.playback.SleepTimer
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/**
 * The player, which is also where a tapped book waits to be played: it
 * loads here, paused, with the play button turning until it is ready, and
 * a chip says where play will send it, this watch (with its Bluetooth
 * audio) or the phone. The choice is remembered. Choosing the other device
 * while a book plays hands it over at its current spot. The book's
 * progress rings the screen, the chapter's rings it just inside, both in
 * the cover's colours.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalWearFoundationApi::class)
@Composable
fun NowPlayingScreen(
    creds: Credentials?,
    onOpenPhonePlayer: () -> Unit,
    onOpenChapters: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenDetails: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember { PlayerCommandClient(context) }
    val state by PlaybackManager.uiState.collectAsState()
    val selection by PlaybackManager.selection.collectAsState()
    val target by PlaybackManager.target.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()

    val sel = selection
    // Loaded means the watch's player holds the selected book.
    val loaded = state.hasItem && (sel == null || (state.itemId == sel.itemId && state.episodeId == sel.episodeId))
    val itemId = sel?.itemId ?: state.itemId
    // What this book (or podcast episode) is kept under, if it is.
    val keptKey = itemId?.let { com.barnabas.absorb.wear.download.keepKey(it, sel?.episodeId ?: state.episodeId) }
    val title = if (loaded) state.title ?: sel?.title else sel?.title
    val author = if (loaded) state.author ?: sel?.author else sel?.author
    val progress = if (loaded && state.durationMs > 0)
        (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f)
    else 0f
    val chapterProgress = if (loaded && state.chapterEndMs > state.chapterStartMs) {
        ((state.positionMs - state.chapterStartMs).toFloat() / (state.chapterEndMs - state.chapterStartMs)).coerceIn(0f, 1f)
    } else {
        null
    }
    val colors = rememberCoverColors(creds, itemId)
    val downloadsOnly by PlaybackManager.downloadsOnly.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()
    // Times at the speed they play at, when the phone shows them that way.
    val timeScale = if (settings.speedAdjustedTime && state.speed > 0f) state.speed else 1f
    val downloads by com.barnabas.absorb.wear.download.BookDownloads.progress.collectAsState()
    // Downloads only, and this book is not kept: it cannot stream here.
    val blocked = target == PlayTarget.WATCH && !loaded && downloadsOnly && keptKey != null &&
        downloads[keptKey]?.complete != true
    val keeping = keptKey?.let { downloads[it] }?.takeIf { !it.complete && !it.failed }

    val outputName = rememberOutputName()
    var pickerOpen by remember { mutableStateOf(false) }

    fun playOnPhone() {
        if (sel == null) return
        onOpenPhonePlayer()
        PlaybackManager.handOffToPhone(context)
    }

    fun loadHere() {
        val s = sel ?: return
        val c = creds ?: return
        val p = phone
        // The phone playing this very book hands it over at its spot.
        val fromPhone = p != null && p.hasBook && p.itemId == s.itemId && p.isPlaying
        scope.launch {
            var resumeAt: Double? = null
            if (fromPhone && p != null) {
                commands.playPause()
                val elapsedMs = (System.currentTimeMillis() - p.issuedAt).coerceAtLeast(0) * p.speed
                resumeAt = (p.positionMs + elapsedMs.toLong()) / 1000.0
                delay(400)
            }
            PlaybackManager.load(context, c, s.itemId, s.episodeId, s.title, s.author, resumeAt)
        }
    }

    // A book kept while waiting on this screen loads by itself.
    LaunchedEffect(downloads[keptKey]?.complete, blocked) {
        if (!loaded && downloadsOnly && target == PlayTarget.WATCH && keptKey != null && downloads[keptKey]?.complete == true) loadHere()
    }

    // Rotary crown/bezel = volume, like every other Wear media app. Events
    // arrive in small pixel deltas, so accumulate until a step's worth.
    val audioManager = remember { context.getSystemService(AudioManager::class.java) }
    var rotaryAccum by remember { mutableFloatStateOf(0f) }

    // The system shows no volume panel for an app's own changes on Wear OS,
    // so the level is drawn along the edge while the crown turns.
    val maxVolume = remember { audioManager?.getStreamMaxVolume(AudioManager.STREAM_MUSIC)?.coerceAtLeast(1) ?: 1 }
    var volume by remember { mutableIntStateOf(audioManager?.getStreamVolume(AudioManager.STREAM_MUSIC) ?: 0) }
    var volumeShownAt by remember { mutableLongStateOf(0L) }
    var volumeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(volumeShownAt) {
        if (volumeShownAt == 0L) return@LaunchedEffect
        volumeVisible = true
        delay(1_500)
        volumeVisible = false
    }
    fun adjustVolume(direction: Int) {
        val audio = audioManager ?: return
        audio.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction, 0)
        volume = audio.getStreamVolume(AudioManager.STREAM_MUSIC)
        volumeShownAt = System.currentTimeMillis()
    }

    // The face, and under it the page of further actions.
    val pagerState = rememberPagerState { 2 }
    val sleep by SleepTimer.state.collectAsState()
    val faceAction by FaceActionStore.action.collectAsState()
    val actions = PlayerActions(
        speedLabel = formatSpeed(state.speed),
        onSpeed = { PlaybackManager.cycleSpeed() },
        sleepLabel = sleepLabel(sleep),
        sleepChip = sleep?.let { if (it.isChapter) "Chapter" else formatDuration(it.remainingMs) },
        onSleep = onOpenSleep,
        // More time on a running timer, or the choices when it is set to
        // the chapter's end.
        onSleepTap = {
            val t = sleep
            if (t == null || t.isChapter) onOpenSleep() else SleepTimer.addMinutes(settings.sleepAddMinutes)
        },
        playTarget = target,
        onPlayOn = { pickerOpen = true },
        onPrevChapter = { PlaybackManager.previousChapter() },
        onNextChapter = { PlaybackManager.nextChapter() },
        onBookmark = {
            val id = state.itemId
            val c = creds
            if (id == null || c == null) {
                false
            } else {
                withContext(Dispatchers.IO) {
                    BookmarkRepository(c).add(id, state.positionMs / 1000.0, state.chapterTitle?.takeIf { it.isNotBlank() } ?: "Bookmark")
                } is AbsorbHttpClient.Result.Ok
            }
        },
        onChapters = onOpenChapters,
        onBookmarks = onOpenBookmarks,
        onDetails = onOpenDetails,
        onStop = { PlaybackManager.stop() },
    )
    Scaffold(
        timeText = {
            if (pagerState.currentPage == 0) {
                TimeText(
                    contentPadding = PaddingValues(top = 13.dp),
                    timeTextStyle = TimeTextDefaults.timeTextStyle(
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        fontSize = 11.sp,
                    ),
                )
            }
        },
        positionIndicator = {
            if (volumeVisible && pagerState.currentPage == 0) {
                PositionIndicator(
                    value = { volume.toFloat() / maxVolume },
                    range = 0f..1f,
                    color = colors.accentSoft,
                )
            }
        },
    ) {
        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                HierarchicalFocusCoordinator(requiresFocus = { pagerState.currentPage == 0 }) {
                // Made here, inside the page that carries it, so the request
                // only fires once this page is on screen and attached.
                val focusRequester = rememberActiveFocusRequester()
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onRotaryScrollEvent { event ->
                            rotaryAccum += event.verticalScrollPixels
                            val step = 48f
                            while (rotaryAccum >= step) {
                                adjustVolume(AudioManager.ADJUST_RAISE)
                                rotaryAccum -= step
                            }
                            while (rotaryAccum <= -step) {
                                adjustVolume(AudioManager.ADJUST_LOWER)
                                rotaryAccum += step
                            }
                            true
                        }
                        .focusRequester(focusRequester)
                        .focusable(),
                ) {
                    // Cover art as a dimmed full-bleed backdrop: the book's identity
                    // without taking space from the controls.
                    if (itemId != null && creds != null) {
                        BookCover(
                            itemId = itemId,
                            creds = creds,
                            modifier = Modifier.fillMaxSize().blur(10.dp),
                            cornerRadius = 0.dp,
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(Color.Black.copy(alpha = 0.66f)),
                        )
                    }

                    PlayerRings(bookProgress = progress, chapterProgress = chapterProgress, colors = colors, moreHint = true) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = 24.dp, end = 24.dp, top = 30.dp, bottom = 10.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center,
                        ) {
                            Text(
                                text = author.orEmpty(),
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )

                            Text(
                                text = title.orEmpty(),
                                style = MaterialTheme.typography.title3,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(),
                            )

                            if (loaded && state.chapterTitle != null) {
                                Text(
                                    text = state.chapterTitle.orEmpty(),
                                    style = MaterialTheme.typography.caption3,
                                    color = colors.accentSoft,
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .basicMarquee(),
                                )
                            }

                            Spacer(Modifier.height(2.dp))

                            val lines = timeLines(
                                positionMs = state.positionMs,
                                durationMs = state.durationMs,
                                chapterStartMs = state.chapterStartMs,
                                chapterEndMs = state.chapterEndMs,
                                timeScale = timeScale,
                            )
                            val status = when {
                                loaded && state.error != null -> state.error
                                blocked && keeping != null -> "Keeping ${(keeping.fraction * 100).roundToInt()}%"
                                blocked -> "Downloads only: keep this book to play it here"
                                target == PlayTarget.PHONE && !loaded -> "Plays on your phone"
                                loaded && state.needsAudioOutput -> "Connect Bluetooth headphones to listen"
                                loaded && !state.isReady -> "Loading…"
                                loaded && state.isBuffering -> "Buffering…"
                                loaded -> lines.first
                                else -> "Loading…"
                            }
                            Text(
                                text = status.orEmpty(),
                                style = MaterialTheme.typography.caption3,
                                color = if (loaded && (state.error != null || state.needsAudioOutput)) MaterialTheme.colors.error
                                else MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                maxLines = 2,
                                textAlign = TextAlign.Center,
                                overflow = TextOverflow.Ellipsis,
                            )
                            val second = lines.second
                            if (status == lines.first && second != null) {
                                Text(
                                    text = second,
                                    style = MaterialTheme.typography.caption3,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            val spinning = !blocked && target == PlayTarget.WATCH && (!loaded || (!state.isReady && !state.isPlaying)) &&
                                !(loaded && state.error != null)
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SkipButton(
                                    seconds = state.skipBackSec,
                                    forward = false,
                                    colors = colors,
                                    enabled = loaded,
                                    onClick = { PlaybackManager.seekBack() },
                                )
                                PlayPauseButton(
                                    isPlaying = loaded && state.isPlaying,
                                    colors = colors,
                                    spinning = spinning,
                                    onClick = {
                                        when {
                                            target == PlayTarget.PHONE -> playOnPhone()
                                            loaded -> PlaybackManager.playPause()
                                            // Play on a book that must be kept first starts keeping it.
                                            blocked -> sel?.let { s ->
                                                val c = creds ?: return@let
                                                if (keeping == null) scope.launch {
                                                    com.barnabas.absorb.wear.download.BookDownloads.start(
                                                        c,
                                                        com.barnabas.absorb.wear.api.InProgressItem(
                                                            id = s.itemId,
                                                            media = com.barnabas.absorb.wear.api.InProgressItem.Media(
                                                                // An episode's "author" is its podcast.
                                                                metadata = if (s.episodeId != null) {
                                                                    com.barnabas.absorb.wear.api.InProgressItem.Metadata(title = s.author)
                                                                } else {
                                                                    com.barnabas.absorb.wear.api.InProgressItem.Metadata(title = s.title, authorName = s.author)
                                                                },
                                                            ),
                                                            recentEpisode = s.episodeId?.let {
                                                                com.barnabas.absorb.wear.api.InProgressItem.RecentEpisode(id = it, title = s.title)
                                                            },
                                                        ),
                                                    )
                                                }
                                            }
                                            else -> loadHere()
                                        }
                                    },
                                )
                                SkipButton(
                                    seconds = state.skipForwardSec,
                                    forward = true,
                                    colors = colors,
                                    enabled = loaded,
                                    onClick = { PlaybackManager.seekForward() },
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val timer = sleep
                                if (loaded && state.error != null) {
                                    CompactChip(
                                        onClick = { PlaybackManager.retry(context) },
                                        label = { Text("Retry") },
                                    )
                                } else if (loaded && state.needsAudioOutput) {
                                    CompactChip(
                                        onClick = { AudioOutput.launchOutputSwitcher(context) },
                                        label = { Text("Output") },
                                    )
                                } else {
                                    FaceActionChip(action = faceAction, actions = actions, enabled = loaded, hasChapters = state.chapters.isNotEmpty())
                                }
                                // The running timer, unless it is the face's own chip.
                                if (timer != null && faceAction != FaceAction.SLEEP) {
                                    SleepChip(
                                        label = if (timer.isChapter) "Chapter" else formatDuration(timer.remainingMs),
                                        onClick = actions.onSleepTap,
                                    )
                                }
                            }
                        }
                    }
                }
                }
            } else {
                HierarchicalFocusCoordinator(requiresFocus = { pagerState.currentPage == 1 }) {
                    val chapterIndex = state.chapters.indexOfLast { state.positionMs / 1000.0 >= it.start }
                    PlayerMorePage(
                        colors = colors,
                        enabled = loaded,
                        chapterTitle = state.chapterTitle,
                        chapterIndex = chapterIndex,
                        chapterCount = state.chapters.size,
                        actions = actions,
                        faceAction = faceAction,
                        onSwapFace = { FaceActionStore.set(it) },
                    )
                }
            }
        }
    }

    PlayOnPicker(
        open = pickerOpen,
        onDismiss = { pickerOpen = false },
        target = target,
        outputName = outputName,
        onWatch = { if (!loaded) loadHere() },
        // Already listening here: the phone picks it up now.
        onPhone = { if (loaded && state.isPlaying) playOnPhone() },
    )
}

internal fun formatSpeed(speed: Float): String =
    if (speed % 1f == 0f) "${speed.toInt()}×" else "$speed×"

/**
 * The player's time lines, as the phone's cards show them: the book's
 * percent and how far in over its length, then the same for the chapter
 * when the chapter is known.
 */
internal fun timeLines(
    positionMs: Long,
    durationMs: Long,
    chapterStartMs: Long,
    chapterEndMs: Long,
    timeScale: Float,
): Pair<String, String?> {
    fun at(ms: Long) = formatDuration((ms.coerceAtLeast(0L) / timeScale).toLong())
    val percent = if (durationMs > 0) (positionMs * 100.0 / durationMs).roundToInt() else 0
    val book = "$percent% · ${at(positionMs)} / ${at(durationMs)}"
    val chapter = if (chapterEndMs > chapterStartMs) {
        "Chapter ${at(positionMs - chapterStartMs)} / ${at(chapterEndMs - chapterStartMs)}"
    } else {
        null
    }
    return book to chapter
}

/** The sleep timer's chip on the face: the moon and what is left. */
@Composable
internal fun SleepChip(label: String, onClick: () -> Unit) {
    CompactChip(
        onClick = onClick,
        icon = {
            Icon(
                imageVector = Icons.Rounded.Bedtime,
                contentDescription = "Sleep timer",
                modifier = Modifier.size(ChipDefaults.SmallIconSize),
            )
        },
        label = { Text(label, maxLines = 1, softWrap = false) },
        colors = ChipDefaults.secondaryChipColors(),
    )
}

internal fun formatDuration(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSeconds = ms / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
