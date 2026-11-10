package com.barnabas.absorb.wear.ui.screens

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.focus.FocusRequester
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
import com.barnabas.absorb.wear.playback.nextSpeed
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.barnabas.absorb.wear.ui.components.BookCover
import com.barnabas.absorb.wear.ui.components.FaceAction
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import com.barnabas.absorb.wear.ui.components.FaceActionChip
import com.barnabas.absorb.wear.ui.components.FaceActionStore
import com.barnabas.absorb.wear.ui.components.PlayerActions
import com.barnabas.absorb.wear.ui.components.PlayOnPicker
import com.barnabas.absorb.wear.ui.components.PlayPauseButton
import com.barnabas.absorb.wear.ui.components.PlayerRings
import com.barnabas.absorb.wear.ui.components.SkipButton
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import com.barnabas.absorb.wear.ui.components.rememberOutputName
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.wear.compose.foundation.HierarchicalFocusCoordinator
import com.barnabas.absorb.wear.ui.components.PlayerMorePage
import com.barnabas.absorb.wear.ui.components.sleepLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Controls for what the phone is playing, Spotify-style: the phone pushes
 * its player state every couple of seconds over the Data Layer and the
 * watch sends play, pause and skips. The position ticks between pushes
 * from the last push's time and the playback speed, and a tapped button
 * shows its effect at once rather than waiting for the next push. The
 * crown turns the phone's volume, which the phone reports back to draw.
 * "Play on" here can pull the book to the watch: the phone is paused and
 * the watch takes over from its spot.
 */
@OptIn(ExperimentalFoundationApi::class, ExperimentalWearFoundationApi::class)
@Composable
fun PhonePlayerScreen(
    creds: Credentials?,
    onOpenWatchPlayer: () -> Unit,
    onOpenChapters: () -> Unit = {},
    onOpenBookmarks: () -> Unit = {},
    onOpenDetails: () -> Unit = {},
    onOpenSleep: () -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember { PlayerCommandClient(context) }
    val state by PlayerStateStore.state.collectAsState()
    val starting by PlayerStateStore.starting.collectAsState()
    val target by PlaybackManager.target.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(state?.isPlaying) {
        while (state?.isPlaying == true) {
            now = System.currentTimeMillis()
            delay(1_000)
        }
    }

    // What the user just asked for, shown until the phone confirms it.
    var expectPlaying by remember { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(state?.issuedAt) { expectPlaying = null }

    // The speed just picked, shown until the phone reports it or gives up.
    var expectSpeed by remember { mutableStateOf<Float?>(null) }
    LaunchedEffect(state?.speed) { expectSpeed = null }
    LaunchedEffect(expectSpeed) {
        if (expectSpeed == null) return@LaunchedEffect
        delay(3_000)
        expectSpeed = null
    }
    val settings by PlaybackManager.settingsFlow.collectAsState()

    // The phone answers each volume command with its level, drawn along
    // the edge for a moment like the watch's own volume; it is asked for
    // once when the screen opens.
    val phoneVolume by PlayerStateStore.phoneVolume.collectAsState()
    var volumeVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { commands.volumeGet() }
    LaunchedEffect(phoneVolume?.at) {
        if (phoneVolume == null) return@LaunchedEffect
        volumeVisible = true
        delay(1_500)
        volumeVisible = false
    }

    // The crown turns the phone's volume, a step per notch's worth of
    // scroll, each step one small message to the phone.
    var rotaryAccum by remember { mutableFloatStateOf(0f) }
    fun Modifier.rotaryVolume(focusRequester: FocusRequester) = this
        .onRotaryScrollEvent { event ->
            rotaryAccum += event.verticalScrollPixels
            val step = 48f
            while (rotaryAccum >= step) {
                scope.launch { commands.volumeUp() }
                rotaryAccum -= step
            }
            while (rotaryAccum <= -step) {
                scope.launch { commands.volumeDown() }
                rotaryAccum += step
            }
            true
        }
        .focusRequester(focusRequester)
        .focusable()

    val outputName = rememberOutputName()
    var pickerOpen by remember { mutableStateOf(false) }
    val pagerState = rememberPagerState { 2 }

    val s = state
    val colors = rememberCoverColors(creds, s?.itemId)
    val timeScale = if (s?.speedAdjustedTime != false && (s?.speed ?: 1f) > 0f) s?.speed ?: 1f else 1f

    // The watch takes the book over: the phone pauses, which saves its
    // spot, and the watch loads from where the phone was.
    fun takeOver() {
        val p = s ?: return
        val c = creds ?: return
        val itemId = p.itemId ?: return
        scope.launch {
            var resumeAt: Double? = null
            if (p.isPlaying) {
                commands.playPause()
                val elapsedMs = (System.currentTimeMillis() - p.issuedAt).coerceAtLeast(0) * p.speed
                resumeAt = (p.positionMs + elapsedMs.toLong()) / 1000.0
                delay(400)
            } else {
                resumeAt = p.positionMs / 1000.0
            }
            PlaybackManager.load(context, c, itemId, null, p.title, p.author, resumeAt)
            onOpenWatchPlayer()
        }
    }

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
            val v = phoneVolume
            if (volumeVisible && v != null && pagerState.currentPage == 0) {
                PositionIndicator(
                    value = { v.index.toFloat() / v.max },
                    range = 0f..1f,
                    color = colors.accentSoft,
                )
            }
        },
    ) {
        if (s == null || !s.hasBook) {
            Box(Modifier.fillMaxSize().rotaryVolume(rememberActiveFocusRequester()), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    if (starting != null) {
                        ExpressiveSpinner(modifier = Modifier.size(30.dp), color = colors.accentSoft)
                        Spacer(Modifier.height(10.dp))
                    }
                    Text(
                        text = if (starting != null) "Starting on the phone" else "Nothing playing on the phone",
                        style = MaterialTheme.typography.body2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 24.dp),
                    )
                }
            }
            return@Scaffold
        }

        // The phone has the book but isn't playing it yet: it is still
        // opening it, so the button spins like the watch's own does.
        val waiting = starting?.itemId == s.itemId && !s.isPlaying
        val isPlaying = !waiting && (expectPlaying ?: s.isPlaying)
        val elapsed = if (s.isPlaying) ((now - s.issuedAt).coerceAtLeast(0) * s.speed).toLong() else 0L
        val positionMs = (s.positionMs + elapsed).coerceIn(0L, if (s.durationMs > 0) s.durationMs else Long.MAX_VALUE)
        val progress = if (s.durationMs > 0) (positionMs.toFloat() / s.durationMs).coerceIn(0f, 1f) else 0f
        val chapterProgress = if (s.chapterEndMs > s.chapterStartMs) {
            ((positionMs - s.chapterStartMs).toFloat() / (s.chapterEndMs - s.chapterStartMs)).coerceIn(0f, 1f)
        } else {
            null
        }
        val sleepChip = when (s.sleepMode) {
            "time" -> formatDuration((if (s.isPlaying) s.sleepEndsAtMs - now else s.sleepEndsAtMs - s.issuedAt).coerceAtLeast(0L))
            "chapters" -> if (s.sleepChaptersLeft <= 1) "Chapter" else "${s.sleepChaptersLeft} ch"
            "episodes" -> "Episode"
            else -> null
        }
        val faceAction by FaceActionStore.action.collectAsState()
        val actions = PlayerActions(
            speedLabel = formatSpeed(expectSpeed ?: s.speed),
            onSpeed = {
                val next = nextSpeed(expectSpeed ?: s.speed, s.speedPresets.ifEmpty { settings.speedPresets })
                expectSpeed = next
                scope.launch { commands.setSpeed(next) }
            },
            sleepLabel = sleepLabel(s, now),
            sleepChip = sleepChip,
            onSleep = onOpenSleep,
            onSleepTap = {
                if (s.sleepMode == "time") scope.launch { commands.sleepAdd(s.sleepAddMinutes) } else onOpenSleep()
            },
            // Playing on the phone is what this screen is, whatever the
            // remembered choice says.
            playTarget = PlaybackManager.PlayTarget.PHONE,
            onPlayOn = { pickerOpen = true },
            onPrevChapter = { scope.launch { commands.previousChapter() } },
            onNextChapter = { scope.launch { commands.nextChapter() } },
            onBookmark = { commands.bookmark(); true },
            onChapters = onOpenChapters,
            onBookmarks = onOpenBookmarks,
            onDetails = onOpenDetails,
            onStop = { scope.launch { commands.stop() } },
        )

        VerticalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
            if (page == 0) {
                HierarchicalFocusCoordinator(requiresFocus = { pagerState.currentPage == 0 }) {
                // Made here, inside the page that carries it, so the request
                // only fires once this page is on screen and attached.
                val focusRequester = rememberActiveFocusRequester()
                Box(modifier = Modifier.fillMaxSize().rotaryVolume(focusRequester)) {
                    if (s.itemId != null && creds != null) {
                        BookCover(
                            itemId = s.itemId,
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
                                text = s.author.orEmpty(),
                                style = MaterialTheme.typography.caption2,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.65f),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.fillMaxWidth(),
                                textAlign = TextAlign.Center,
                            )
                            Text(
                                text = s.title.orEmpty(),
                                style = MaterialTheme.typography.title3,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .basicMarquee(),
                            )
                            if (!s.chapter.isNullOrBlank()) {
                                Text(
                                    text = s.chapter,
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
                                positionMs = positionMs,
                                durationMs = s.durationMs,
                                chapterStartMs = s.chapterStartMs,
                                chapterEndMs = s.chapterEndMs,
                                timeScale = timeScale,
                            )
                            Text(
                                text = lines.first,
                                style = MaterialTheme.typography.caption3,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                maxLines = 1,
                                textAlign = TextAlign.Center,
                            )
                            val second = lines.second
                            if (second != null) {
                                Text(
                                    text = second,
                                    style = MaterialTheme.typography.caption3,
                                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                                    maxLines = 1,
                                    textAlign = TextAlign.Center,
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(10.dp),
                            ) {
                                SkipButton(
                                    seconds = s.skipBackSec,
                                    forward = false,
                                    colors = colors,
                                    onClick = { scope.launch { commands.skipBack() } },
                                )
                                PlayPauseButton(
                                    isPlaying = isPlaying,
                                    colors = colors,
                                    spinning = waiting,
                                    onClick = {
                                        val itemId = s.itemId
                                        if (isPlaying || itemId == null) {
                                            expectPlaying = !isPlaying
                                            PlayerStateStore.cancelStart()
                                        } else {
                                            PlayerStateStore.expectStart(itemId)
                                        }
                                        scope.launch { if (!commands.playPause()) PlayerStateStore.cancelStart() }
                                    },
                                )
                                SkipButton(
                                    seconds = s.skipForwardSec,
                                    forward = true,
                                    colors = colors,
                                    onClick = { scope.launch { commands.skipForward() } },
                                )
                            }

                            Spacer(Modifier.height(4.dp))

                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                FaceActionChip(action = faceAction, actions = actions, enabled = true, hasChapters = s.chapterCount > 0)
                                if (sleepChip != null && faceAction != FaceAction.SLEEP) {
                                    SleepChip(label = sleepChip, onClick = actions.onSleepTap)
                                }
                            }
                        }
                    }
                }
                }
            } else {
                HierarchicalFocusCoordinator(requiresFocus = { pagerState.currentPage == 1 }) {
                    PlayerMorePage(
                        colors = colors,
                        enabled = true,
                        chapterTitle = s.chapter,
                        chapterIndex = s.chapterIndex,
                        chapterCount = s.chapterCount,
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
        target = if (target == PlaybackManager.PlayTarget.WATCH && s?.hasBook == true) PlaybackManager.PlayTarget.PHONE else target,
        outputName = outputName,
        onWatch = { takeOver() },
        onPhone = { },
    )
}
