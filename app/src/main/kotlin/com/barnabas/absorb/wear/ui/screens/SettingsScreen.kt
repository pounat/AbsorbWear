package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Stepper
import androidx.wear.compose.material.StepperDefaults
import androidx.wear.compose.material.Switch
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.ToggleChip
import androidx.wear.compose.material.ToggleChipDefaults
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.barnabas.absorb.wear.sleep.SleepWatch
import androidx.wear.compose.material.dialog.Dialog
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.sync.PhoneAuthClient
import com.barnabas.absorb.wear.sync.PhoneAutoOpen
import com.barnabas.absorb.wear.sync.PlayerStateClient
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Where the watch is signed in, the playback settings it follows from the
 * phone (shown, not editable, until "follow the phone" is turned off and
 * the watch keeps its own), and sign out. A number opens a stepper over
 * the screen; the crown or the buttons change it, swiping back keeps it.
 */
private class ValueEdit(
    val title: String,
    val value: Int,
    val range: IntProgression,
    val label: (Int) -> String,
    val onChange: (Int) -> Unit,
)

@Composable
fun SettingsScreen(creds: Credentials, onOpenDownloads: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneClient = remember { PhoneAuthClient(context) }
    val listState = rememberScalingLazyListState()
    val follow by PlaybackManager.followPhone.collectAsState()
    val downloadsOnly by PlaybackManager.downloadsOnly.collectAsState()
    val kept by com.barnabas.absorb.wear.download.DownloadStore.books.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()
    val skips by PlaybackManager.skipsFlow.collectAsState()
    val playback by PlaybackManager.uiState.collectAsState()
    val openOnPhonePlay by PhoneAutoOpen.enabled.collectAsState()
    val sleepPause by SleepWatch.enabled.collectAsState()
    val sleepNote by SleepWatch.lastEvent.collectAsState()
    val askActivity = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        SleepWatch.setEnabled(granted)
    }
    var editing by remember { mutableStateOf<ValueEdit?>(null) }

    val mutedChip = ChipDefaults.chipColors(
        backgroundColor = MaterialTheme.colors.surface,
        contentColor = MaterialTheme.colors.onSurface.copy(alpha = if (follow) 0.55f else 1f),
        secondaryContentColor = MaterialTheme.colors.onSurface.copy(alpha = if (follow) 0.45f else 0.7f),
    )

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item { SectionLabel("Server") }
        item {
            Chip(
                onClick = { },
                enabled = false,
                label = { Text(creds.serverUrl.removePrefix("https://").removePrefix("http://"), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                secondaryLabel = { Text("${creds.username} · ${if (creds.isBookOrbit) "BookOrbit" else "Audiobookshelf"}") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SectionLabel("Playback") }
        item {
            ToggleChip(
                checked = follow,
                onCheckedChange = {
                    PlaybackManager.setFollowPhone(it)
                    // Back on, the phone's last published settings replace
                    // the watch's own at once rather than on its next push.
                    if (it) scope.launch { PlayerStateClient.seedFromPhone(context) }
                },
                label = { Text("Follow the phone") },
                secondaryLabel = { Text(if (follow) "Settings come from Absorb" else "The watch keeps its own") },
                toggleControl = { Switch(checked = follow) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = { if (!follow) PlaybackManager.cycleSpeed() },
                enabled = !follow,
                label = { Text("Speed") },
                secondaryLabel = { Text(formatSpeed(playback.speed)) },
                colors = mutedChip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = {
                    editing = ValueEdit("Skip back", skips.first, 5..120 step 5, ::seconds) {
                        PlaybackManager.setSkips(it, PlaybackManager.skipsFlow.value.second)
                    }
                },
                enabled = !follow,
                label = { Text("Skip back") },
                secondaryLabel = { Text(seconds(skips.first)) },
                colors = mutedChip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = {
                    editing = ValueEdit("Skip forward", skips.second, 5..180 step 5, ::seconds) {
                        PlaybackManager.setSkips(PlaybackManager.skipsFlow.value.first, it)
                    }
                },
                enabled = !follow,
                label = { Text("Skip forward") },
                secondaryLabel = { Text(seconds(skips.second)) },
                colors = mutedChip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = settings.autoRewindEnabled,
                onCheckedChange = { if (!follow) PlaybackManager.setSettings(settings.copy(autoRewindEnabled = it)) },
                enabled = !follow,
                label = { Text("Auto rewind") },
                secondaryLabel = {
                    Text(
                        if (settings.autoRewindEnabled) "${settings.autoRewindMinSec.toInt()}–${settings.autoRewindMaxSec.toInt()}s after a pause"
                        else "Off",
                    )
                },
                toggleControl = { Switch(checked = settings.autoRewindEnabled, enabled = !follow) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (settings.autoRewindEnabled) {
            // The rewind grows from the shortest to the longest over an
            // hour's pause; nothing happens for a pause shorter than the delay.
            item {
                Chip(
                    onClick = {
                        editing = ValueEdit("Shortest rewind", settings.autoRewindMinSec.toInt(), 0..60, ::seconds) { v ->
                            val s = PlaybackManager.settingsFlow.value
                            PlaybackManager.setSettings(
                                s.copy(autoRewindMinSec = v.toDouble(), autoRewindMaxSec = maxOf(s.autoRewindMaxSec, v.toDouble())),
                            )
                        }
                    },
                    enabled = !follow,
                    label = { Text("Shortest rewind") },
                    secondaryLabel = { Text("${seconds(settings.autoRewindMinSec.toInt())} after a short pause") },
                    colors = mutedChip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = {
                        editing = ValueEdit("Longest rewind", settings.autoRewindMaxSec.toInt(), 0..60, ::seconds) { v ->
                            val s = PlaybackManager.settingsFlow.value
                            PlaybackManager.setSettings(
                                s.copy(autoRewindMaxSec = v.toDouble(), autoRewindMinSec = minOf(s.autoRewindMinSec, v.toDouble())),
                            )
                        }
                    },
                    enabled = !follow,
                    label = { Text("Longest rewind") },
                    secondaryLabel = { Text("${seconds(settings.autoRewindMaxSec.toInt())} after an hour") },
                    colors = mutedChip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = {
                        editing = ValueEdit("Rewind after a pause of", settings.autoRewindDelaySec.toInt(), 0..10, ::pauseLength) { v ->
                            PlaybackManager.setSettings(PlaybackManager.settingsFlow.value.copy(autoRewindDelaySec = v.toDouble()))
                        }
                    },
                    enabled = !follow,
                    label = { Text("Rewind after a pause of") },
                    secondaryLabel = { Text(pauseLength(settings.autoRewindDelaySec.toInt())) },
                    colors = mutedChip,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item {
            ToggleChip(
                checked = settings.autoRewindSessionStart,
                onCheckedChange = { if (!follow) PlaybackManager.setSettings(settings.copy(autoRewindSessionStart = it)) },
                enabled = !follow,
                label = { Text("Rewind on start") },
                secondaryLabel = { Text(if (settings.autoRewindSessionStart) "${settings.autoRewindMaxSec.toInt()}s when a book opens" else "Off") },
                toggleControl = { Switch(checked = settings.autoRewindSessionStart, enabled = !follow) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = settings.skipChapterBarrier,
                onCheckedChange = { if (!follow) PlaybackManager.setSettings(settings.copy(skipChapterBarrier = it)) },
                enabled = !follow,
                label = { Text("Stop at chapter start") },
                secondaryLabel = { Text("When skipping back") },
                toggleControl = { Switch(checked = settings.skipChapterBarrier, enabled = !follow) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = {
                    editing = ValueEdit("Sleep timer tap adds", settings.sleepAddMinutes, 1..30, ::minutes) { v ->
                        PlaybackManager.setSettings(PlaybackManager.settingsFlow.value.copy(sleepAddMinutes = v))
                    }
                },
                enabled = !follow,
                label = { Text("Sleep timer tap adds") },
                secondaryLabel = { Text(minutes(settings.sleepAddMinutes)) },
                colors = mutedChip,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            ToggleChip(
                checked = settings.speedAdjustedTime,
                onCheckedChange = { if (!follow) PlaybackManager.setSettings(settings.copy(speedAdjustedTime = it)) },
                enabled = !follow,
                label = { Text("Time at speed") },
                secondaryLabel = { Text(if (settings.speedAdjustedTime) "Times shrink as speed rises" else "Real book time") },
                toggleControl = { Switch(checked = settings.speedAdjustedTime, enabled = !follow) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SectionLabel("Sleep") }
        item {
            ToggleChip(
                checked = sleepPause,
                onCheckedChange = { on ->
                    when {
                        !on -> SleepWatch.setEnabled(false)
                        ContextCompat.checkSelfPermission(context, Manifest.permission.ACTIVITY_RECOGNITION) ==
                            PackageManager.PERMISSION_GRANTED -> SleepWatch.setEnabled(true)
                        else -> askActivity.launch(Manifest.permission.ACTIVITY_RECOGNITION)
                    }
                },
                label = { Text("Pause when asleep") },
                secondaryLabel = { Text(if (sleepPause) (sleepNote ?: "Watching for sleep") else "Off") },
                toggleControl = { Switch(checked = sleepPause) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SectionLabel("Phone") }
        item {
            ToggleChip(
                checked = openOnPhonePlay,
                onCheckedChange = { PhoneAutoOpen.set(it) },
                label = { Text("Open when phone plays") },
                secondaryLabel = { Text(if (openOnPhonePlay) "Shows the phone player here" else "Off") },
                toggleControl = { Switch(checked = openOnPhonePlay) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SectionLabel("Downloads") }
        item {
            ToggleChip(
                checked = downloadsOnly,
                onCheckedChange = { PlaybackManager.setDownloadsOnly(it) },
                label = { Text("Downloads only") },
                secondaryLabel = { Text(if (downloadsOnly) "No streaming on the watch" else "Books can stream") },
                toggleControl = { Switch(checked = downloadsOnly) },
                colors = ToggleChipDefaults.toggleChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Chip(
                onClick = onOpenDownloads,
                label = { Text("Manage downloads") },
                secondaryLabel = { Text("${kept.size} ${if (kept.size == 1) "book" else "books"} on the watch") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item { SectionLabel("Account") }
        item {
            Chip(
                onClick = {
                    PlaybackManager.stop()
                    // Tell the phone first: clearing the session tears this
                    // screen (and this scope) down, which would cancel the send.
                    scope.launch {
                        withTimeoutOrNull(5_000) { phoneClient.signOut() }
                        CredentialStore.clear()
                    }
                },
                label = { Text("Sign out") },
                colors = ChipDefaults.secondaryChipColors(),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }

    val edit = editing
    Dialog(showDialog = edit != null, onDismissRequest = { editing = null }) {
        if (edit != null) ValueStepper(edit)
    }
}

@Composable
private fun ValueStepper(edit: ValueEdit) {
    var value by remember(edit.title) { mutableIntStateOf(edit.value) }
    Stepper(
        value = value,
        onValueChange = {
            value = it
            edit.onChange(it)
        },
        valueProgression = edit.range,
        decreaseIcon = { Icon(StepperDefaults.Decrease, "Less") },
        increaseIcon = { Icon(StepperDefaults.Increase, "More") },
        modifier = Modifier.fillMaxSize(),
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = edit.title,
                style = MaterialTheme.typography.caption1,
                color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
            Text(
                text = edit.label(value),
                style = MaterialTheme.typography.title2,
                textAlign = TextAlign.Center,
            )
        }
    }
}

private fun seconds(n: Int) = if (n == 1) "1 second" else "$n seconds"
private fun minutes(n: Int) = if (n == 1) "1 minute" else "$n minutes"
private fun pauseLength(n: Int) = if (n == 0) "Any pause" else seconds(n)

@Composable
private fun SectionLabel(text: String) {
    Column(Modifier.fillMaxWidth().padding(top = 4.dp)) {
        Text(
            text = text,
            style = MaterialTheme.typography.caption1,
            color = MaterialTheme.colors.primary,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
