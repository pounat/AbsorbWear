package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Picker
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.rememberPickerState
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.playback.SleepTimer
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.sync.PlayerCommandClient
import com.barnabas.absorb.wear.ui.components.PlayerColors
import com.barnabas.absorb.wear.ui.components.rememberCoverColors
import com.barnabas.absorb.wear.ui.components.sleepLabel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * The sleep timer as a grid of round buttons, like the watch's app grid:
 * the minute presets the phone offers, the end of the chapter, and a
 * custom number of minutes on a picker. With a timer running, the top row
 * adds the phone's "more time" amount or turns it off. Serves the watch's
 * own timer and the phone's; only the actions behind it differ.
 */
@Composable
fun SleepTimerScreen(creds: Credentials?, source: String, onBack: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val commands = remember { PlayerCommandClient(context) }
    val fromWatch = source == SOURCE_WATCH
    val watchTimer by SleepTimer.state.collectAsState()
    val watch by PlaybackManager.uiState.collectAsState()
    val phone by PlayerStateStore.state.collectAsState()
    val settings by PlaybackManager.settingsFlow.collectAsState()

    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1_000)
            now = System.currentTimeMillis()
        }
    }

    val itemId = if (fromWatch) watch.itemId else phone?.itemId
    val colors = rememberCoverColors(creds, itemId)
    val activeLabel = if (fromWatch) sleepLabel(watchTimer) else sleepLabel(phone, now)
    val canAdd = if (fromWatch) watchTimer?.isChapter == false else phone?.sleepMode == "time"
    val hasChapters = if (fromWatch) watch.chapters.isNotEmpty() else (phone?.chapterCount ?: 0) > 0
    val addMinutes = if (fromWatch) settings.sleepAddMinutes else phone?.sleepAddMinutes ?: settings.sleepAddMinutes
    var custom by remember { mutableStateOf(false) }

    fun start(minutes: Int) {
        if (fromWatch) SleepTimer.start(minutes) else scope.launch { commands.sleepMinutes(minutes) }
        onBack()
    }

    fun endOfChapter() {
        if (fromWatch) SleepTimer.endOfChapter() else scope.launch { commands.sleepChapters(1) }
        onBack()
    }

    fun add() {
        if (fromWatch) SleepTimer.addMinutes(addMinutes) else scope.launch { commands.sleepAdd(addMinutes) }
        onBack()
    }

    fun cancel() {
        if (fromWatch) SleepTimer.cancel() else scope.launch { commands.sleepCancel() }
        onBack()
    }

    if (custom) {
        MinutesPicker(colors = colors, onPick = { start(it) }, onCancel = { custom = false })
        return
    }

    val listState = rememberScalingLazyListState()
    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                text = activeLabel?.let { "Sleep · $it" } ?: "Sleep timer",
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp),
            )
        }
        if (activeLabel != null) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    RoundChoice(label = "+$addMinutes", caption = "min", colors = colors, enabled = canAdd, onClick = ::add)
                    RoundChoice(icon = Icons.Rounded.Close, caption = "Off", colors = colors, onClick = ::cancel)
                }
            }
        }
        for (row in PRESET_MINUTES.chunked(3)) {
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (minutes in row) {
                        RoundChoice(label = "$minutes", caption = "min", colors = colors, onClick = { start(minutes) })
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                if (hasChapters) {
                    RoundChoice(label = "End", caption = "chapter", colors = colors, onClick = ::endOfChapter)
                }
                RoundChoice(icon = Icons.Rounded.Tune, caption = "Custom", colors = colors, onClick = { custom = true })
            }
        }
    }
}

private val PRESET_MINUTES = listOf(5, 10, 15, 30, 45, 60)

/** A round button with a big label or an icon, and a small word under it. */
@Composable
private fun RoundChoice(
    colors: PlayerColors,
    caption: String,
    onClick: () -> Unit,
    label: String? = null,
    icon: ImageVector? = null,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(60.dp).clip(CircleShape),
        colors = ButtonDefaults.buttonColors(
            backgroundColor = colors.accentSoft.copy(alpha = 0.24f),
            contentColor = Color.White,
            disabledBackgroundColor = colors.accentSoft.copy(alpha = 0.1f),
            disabledContentColor = Color.White.copy(alpha = 0.35f),
        ),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (icon != null) {
                Icon(icon, contentDescription = caption, modifier = Modifier.size(22.dp))
            } else {
                Text(text = label.orEmpty(), style = MaterialTheme.typography.title2, fontWeight = FontWeight.Bold, maxLines = 1)
            }
            Text(text = caption, style = MaterialTheme.typography.caption3, maxLines = 1)
        }
    }
}

/** A picker of minutes, one to three hours, with a tick to set it. */
@Composable
private fun MinutesPicker(colors: PlayerColors, onPick: (Int) -> Unit, onCancel: () -> Unit) {
    val state = rememberPickerState(initialNumberOfOptions = 180, initiallySelectedOption = 29)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(text = "Minutes", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f))
            Picker(
                state = state,
                contentDescription = "Minutes",
                modifier = Modifier.size(width = 120.dp, height = 96.dp),
            ) { index ->
                Text(
                    text = "${index + 1}",
                    style = MaterialTheme.typography.display3,
                    color = if (index == state.selectedOption) colors.accentSoft else MaterialTheme.colors.onSurface,
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 6.dp)) {
                Button(
                    onClick = onCancel,
                    modifier = Modifier.size(40.dp),
                    colors = ButtonDefaults.secondaryButtonColors(),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", modifier = Modifier.size(20.dp))
                }
                Button(
                    onClick = { onPick(state.selectedOption + 1) },
                    modifier = Modifier.size(48.dp),
                    colors = ButtonDefaults.buttonColors(backgroundColor = colors.accent, contentColor = colors.onAccent),
                ) {
                    Icon(Icons.Rounded.Check, contentDescription = "Set", modifier = Modifier.size(24.dp))
                }
            }
            Box(Modifier.height(4.dp))
        }
    }
}
