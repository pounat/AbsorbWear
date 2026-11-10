package com.barnabas.absorb.wear.ui.screens

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.Network
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.foundation.rotary.RotaryScrollableDefaults
import androidx.wear.compose.foundation.rotary.rotaryScrollable
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.R
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.InProgressRepository
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.BookDownloads
import com.barnabas.absorb.wear.download.DownloadStore
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.playback.PlaybackUiState
import com.barnabas.absorb.wear.player.AudioOutput
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.player.WearMediaAutoLaunch
import com.barnabas.absorb.wear.sync.PhoneAuthClient
import com.barnabas.absorb.wear.ui.components.BookCover
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

@Composable
fun SignedInScreen(
    creds: Credentials,
    onOpenPlayer: () -> Unit,
    onOpenPhonePlayer: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneClient = remember { PhoneAuthClient(context) }
    // Only what this screen shows: the position ticks every second and
    // would otherwise redraw the whole list each time.
    val playback: PlaybackUiState by remember {
        PlaybackManager.uiState
            .map { it.copy(positionMs = 0L, chapterTitle = null, isBuffering = false) }
            .distinctUntilChanged()
    }.collectAsState(initial = PlaybackManager.uiState.value)
    val phonePlayer by remember {
        PlayerStateStore.state.map { it?.copy(positionMs = 0L, issuedAt = 0L) }.distinctUntilChanged()
    }.collectAsState(initial = PlayerStateStore.state.value)
    val downloads by DownloadStore.books.collectAsState()
    val progress by BookDownloads.progress.collectAsState()
    var downloadError by remember { mutableStateOf<String?>(null) }

    // Re-read whenever the screen comes back, since the user may have
    // changed it in the system settings we send them to.
    var autoLaunch by remember { mutableStateOf(WearMediaAutoLaunch.STATE_UNKNOWN) }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                    val s = WearMediaAutoLaunch.state(context)
                    android.util.Log.i("AbsorbWearPlayer", "media auto-launch state=$s")
                    autoLaunch = s
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Audio outputs are asked once and then watched, not polled on every pass.
    var hasOutput by remember { mutableStateOf(AudioOutput.hasSuitableOutput(context)) }
    DisposableEffect(Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                hasOutput = AudioOutput.hasSuitableOutput(context)
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                hasOutput = AudioOutput.hasSuitableOutput(context)
            }
        }
        audio?.registerAudioDeviceCallback(callback, null)
        onDispose { audio?.unregisterAudioDeviceCallback(callback) }
    }
    val repo = remember(creds.serverUrl, creds.accessToken) { InProgressRepository(creds) }

    var items by remember { mutableStateOf<List<InProgressItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }

    // The watch hops between Wi-Fi and the phone's Bluetooth link, and a
    // fetch that landed in the gap stays failed otherwise. Fetch again when
    // a network comes back (the first callback is the current one).
    DisposableEffect(Unit) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        var first = true
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                if (first) first = false else refresh++
            }
        }
        cm?.registerDefaultNetworkCallback(callback)
        onDispose { cm?.unregisterNetworkCallback(callback) }
    }

    // Fetched on entry and again whenever playback ends while we're visible,
    // so positions reflect what was just listened to.
    LaunchedEffect(creds.serverUrl, creds.accessToken, playback.hasItem, refresh) {
        error = null
        PlaybackManager.retryPendingSync()
        when (val result = repo.fetch(limit = 10)) {
            is AbsorbHttpClient.Result.Ok -> items = result.value
            is AbsorbHttpClient.Result.Unauthorized -> {
                error = "Session expired — open Absorb on your phone"
                items = emptyList()
                // Nudge the phone to republish fresh credentials.
                phoneClient.requestSync()
            }
            is AbsorbHttpClient.Result.Failed -> {
                error = result.message
                items = emptyList()
            }
        }
    }

    val listState = rememberScalingLazyListState()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    ScalingLazyColumn(
        state = listState,
        modifier = Modifier
            .fillMaxSize()
            .rotaryScrollable(
                behavior = RotaryScrollableDefaults.behavior(listState),
                focusRequester = focusRequester,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.signed_in_as, creds.username),
                style = MaterialTheme.typography.caption1,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
            )
        }

        if (playback.hasItem) {
            item {
                NowPlayingChip(playback = playback, creds = creds, onClick = onOpenPlayer)
            }
        }

        val phone = phonePlayer
        if (phone != null && phone.hasBook && !playback.hasItem) {
            item {
                Chip(
                    onClick = onOpenPhonePlayer,
                    colors = ChipDefaults.gradientBackgroundChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    icon = {
                        phone.itemId?.let {
                            BookCover(
                                itemId = it,
                                creds = creds,
                                modifier = Modifier.size(36.dp),
                                cornerRadius = 4.dp,
                            )
                        }
                    },
                    label = {
                        Column {
                            Text(
                                text = if (phone.isPlaying) "Playing on phone" else "Paused on phone",
                                style = MaterialTheme.typography.caption3,
                                color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                            )
                            Text(
                                text = phone.title ?: "Open controls",
                                style = MaterialTheme.typography.button,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    },
                )
            }
        }

        // Wear OS opens its own Media Controls for the phone's playback unless
        // the user turns auto-launch on for this app; offer the switch.
        // "Unknown" means the watch's Media Controls app predates the
        // per-app setting (its settings screen does not exist yet), so the
        // chip only appears once the setting is real and off.
        if (autoLaunch == WearMediaAutoLaunch.STATE_DISABLED) {
            item {
                Chip(
                    label = { Text("Open here for phone playback") },
                    secondaryLabel = { Text("Off. Tap to turn on") },
                    onClick = {
                        val opened = WearMediaAutoLaunch.openSettings(context)
                        android.util.Log.i("AbsorbWearPlayer", "showMediaAutoLaunchSettings -> $opened")
                    },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }

        // Wear OS only plays media through Bluetooth audio, so say so up front
        // rather than letting the first tap on a book look broken.
        if (!hasOutput) {
            item {
                Chip(
                    label = { Text("No headphones connected") },
                    secondaryLabel = { Text("Tap to choose output") },
                    onClick = { AudioOutput.launchOutputSwitcher(context) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                )
            }
        }

        item {
            Text(
                text = "Continue listening",
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 4.dp),
            )
        }

        val currentItems = items
        when {
            currentItems == null -> item {
                Text(
                    text = "Loading...",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            error != null -> item {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = error!!,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.error,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    )
                    CompactChip(
                        onClick = { items = null; refresh++ },
                        label = { Text("Retry") },
                    )
                }
            }
            currentItems.isEmpty() -> item {
                Text(
                    text = "Nothing in progress",
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f),
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            else -> items(currentItems, key = { it.id + (it.episodeId ?: "") }) { item ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    ItemChip(
                        item = item,
                        creds = creds,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            val alreadyLoaded = playback.itemId == item.id &&
                                playback.episodeId == item.episodeId
                            if (!alreadyLoaded) {
                                // Loads here, paused, or waits for the phone;
                                // the player screen has the play button.
                                PlaybackManager.select(
                                    context = context,
                                    creds = creds,
                                    itemId = item.id,
                                    episodeId = item.episodeId,
                                    title = item.displayTitle,
                                    author = item.displaySubtitle,
                                )
                            }
                            onOpenPlayer()
                        },
                    )
                    if (item.episodeId == null) {
                        Spacer(Modifier.width(4.dp))
                        DownloadButton(
                            progress = progress[com.barnabas.absorb.wear.download.keepKey(item.id, item.episodeId)],
                            kept = downloads.containsKey(com.barnabas.absorb.wear.download.keepKey(item.id, item.episodeId)),
                            onDownload = {
                                scope.launch {
                                    BookDownloads.start(creds, item)?.let { downloadError = it }
                                }
                            },
                        )
                    }
                }
            }
        }

        if (downloadError != null) {
            item {
                Text(
                    text = downloadError!!,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        // Books kept on the watch: playable with no server in reach.
        val kept = downloads.values.filter { it.serverUrl == creds.serverUrl }.sortedByDescending { it.addedAtMs }
        if (kept.isNotEmpty()) {
            item {
                Text(
                    text = "On this watch",
                    style = MaterialTheme.typography.title3,
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            items(kept, key = { "kept:" + it.itemId }) { book ->
                val p = progress[book.key]
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                ) {
                    Chip(
                        onClick = {
                            if (playback.itemId == book.itemId) {
                                onOpenPlayer()
                            } else if (p?.complete == true) {
                                PlaybackManager.select(context, creds, book.itemId, null, book.title, book.author)
                                onOpenPlayer()
                            }
                        },
                        colors = ChipDefaults.secondaryChipColors(),
                        modifier = Modifier.weight(1f),
                        icon = {
                            BookCover(
                                itemId = book.itemId,
                                creds = creds,
                                modifier = Modifier.size(36.dp),
                                cornerRadius = 4.dp,
                            )
                        },
                        label = {
                            Text(
                                text = book.title ?: book.itemId,
                                style = MaterialTheme.typography.button,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        secondaryLabel = {
                            Text(
                                text = when {
                                    p == null -> "Checking"
                                    p.failed -> "Download failed, tap to retry"
                                    p.complete -> "Downloaded"
                                    else -> "Downloading ${p.percent}%"
                                },
                                style = MaterialTheme.typography.caption3,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                    )
                    Spacer(Modifier.width(4.dp))
                    Button(
                        onClick = { BookDownloads.remove(book.key) },
                        colors = ButtonDefaults.secondaryButtonColors(),
                        modifier = Modifier.size(32.dp),
                    ) {
                        Text(text = "✕", fontSize = 12.sp)
                    }
                }
            }
        }

        item {
            Chip(
                label = { Text(stringResource(R.string.sign_out)) },
                onClick = {
                    PlaybackManager.stop()
                    // Tell the phone first: clearing creds tears this screen
                    // (and this scope) down, which would cancel the send.
                    scope.launch {
                        withTimeoutOrNull(5_000) { phoneClient.signOut() }
                        CredentialStore.clear()
                    }
                },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@Composable
private fun NowPlayingChip(
    playback: PlaybackUiState,
    creds: Credentials,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        colors = ChipDefaults.gradientBackgroundChipColors(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        icon = {
            playback.itemId?.let {
                BookCover(
                    itemId = it,
                    creds = creds,
                    modifier = Modifier.size(36.dp),
                    cornerRadius = 4.dp,
                )
            }
        },
        label = {
            Column {
                Text(
                    text = if (playback.isPlaying) "Now playing" else "Paused",
                    style = MaterialTheme.typography.caption3,
                    color = MaterialTheme.colors.onSurface.copy(alpha = 0.75f),
                )
                Text(
                    text = playback.title ?: "Open player",
                    style = MaterialTheme.typography.button,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
    )
}

/** A small round button that keeps a book on the watch and shows how far
 *  along that is. Removal lives in the "On this watch" list. */
@Composable
internal fun DownloadButton(
    progress: BookDownloads.Progress?,
    kept: Boolean,
    onDownload: () -> Unit,
) {
    val label = when {
        progress?.complete == true -> "✓"
        progress?.failed == true -> "!"
        kept -> "${progress?.percent ?: 0}"
        else -> "↓"
    }
    Button(
        onClick = { if (!kept) onDownload() },
        enabled = !kept,
        colors = ButtonDefaults.secondaryButtonColors(),
        modifier = Modifier.size(32.dp),
    ) {
        Text(text = label, fontSize = 11.sp)
    }
}

@Composable
internal fun ItemChip(
    item: InProgressItem,
    creds: Credentials,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Chip(
        onClick = onClick,
        colors = ChipDefaults.primaryChipColors(),
        modifier = modifier,
        icon = {
            BookCover(
                itemId = item.id,
                creds = creds,
                modifier = Modifier.size(36.dp),
                cornerRadius = 4.dp,
            )
        },
        label = {
            Column {
                Text(
                    text = item.displayTitle,
                    style = MaterialTheme.typography.button,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val subtitle = item.displaySubtitle
                if (subtitle != null) {
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.caption3,
                        color = MaterialTheme.colors.onPrimary.copy(alpha = 0.75f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        },
    )
}
