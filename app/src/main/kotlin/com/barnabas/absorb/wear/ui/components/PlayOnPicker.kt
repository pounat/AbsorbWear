package com.barnabas.absorb.wear.ui.components

import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CompactChip
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.playback.PlaybackManager.PlayTarget
import com.barnabas.absorb.wear.player.AudioOutput
import com.barnabas.absorb.wear.sync.PhoneAuthClient

/** The connected Bluetooth audio device's name, kept current, or null. */
@Composable
fun rememberOutputName(): String? {
    val context = LocalContext.current
    var outputName by remember { mutableStateOf(AudioOutput.outputName(context)) }
    DisposableEffect(Unit) {
        val audio = context.getSystemService(AudioManager::class.java)
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) {
                outputName = AudioOutput.outputName(context)
            }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) {
                outputName = AudioOutput.outputName(context)
            }
        }
        audio?.registerAudioDeviceCallback(callback, null)
        onDispose { audio?.unregisterAudioDeviceCallback(callback) }
    }
    return outputName
}

/** The small chip that names where play goes and opens the picker; just
 *  the icon when [compact], to leave the row room for another chip. */
@Composable
fun PlayTargetChip(target: PlayTarget, onClick: () -> Unit, compact: Boolean = false) {
    val name = if (target == PlayTarget.PHONE) "Phone" else "Watch"
    val icon = @Composable {
        Icon(
            imageVector = if (target == PlayTarget.PHONE) Icons.Rounded.Smartphone else Icons.Rounded.Watch,
            contentDescription = name,
            modifier = Modifier.size(ChipDefaults.SmallIconSize),
        )
    }
    if (compact) {
        CompactChip(onClick = onClick, label = { icon() }, colors = ChipDefaults.secondaryChipColors())
    } else {
        CompactChip(onClick = onClick, icon = { icon() }, label = { Text(name) }, colors = ChipDefaults.secondaryChipColors())
    }
}

/**
 * "Play on": this watch, named after its Bluetooth audio and grayed when
 * there is none (then it opens the output picker instead), or the phone,
 * grayed in words when the phone is out of reach. [onWatch] and [onPhone]
 * run after the choice is remembered.
 */
@Composable
fun PlayOnPicker(
    open: Boolean,
    onDismiss: () -> Unit,
    target: PlayTarget,
    outputName: String?,
    onWatch: () -> Unit,
    onPhone: () -> Unit,
) {
    val context = LocalContext.current
    var phoneReachable by remember { mutableStateOf(true) }
    LaunchedEffect(open) {
        if (open) phoneReachable = PhoneAuthClient(context).canReachPhone()
    }

    Dialog(showDialog = open, onDismissRequest = onDismiss) {
        Alert(
            title = {
                Text("Play on", textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            },
        ) {
            item {
                val hasOutput = outputName != null
                Chip(
                    onClick = {
                        onDismiss()
                        if (!hasOutput) {
                            AudioOutput.launchOutputSwitcher(context)
                        } else {
                            PlaybackManager.setTarget(PlayTarget.WATCH)
                            onWatch()
                        }
                    },
                    label = { Text("This watch") },
                    secondaryLabel = { Text(outputName ?: "Connect Bluetooth device") },
                    icon = { Icon(Icons.Rounded.Watch, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
                    colors = when {
                        !hasOutput -> ChipDefaults.chipColors(
                            backgroundColor = MaterialTheme.colors.surface.copy(alpha = 0.5f),
                            contentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                            secondaryContentColor = MaterialTheme.colors.onSurface.copy(alpha = 0.45f),
                            iconColor = MaterialTheme.colors.onSurface.copy(alpha = 0.5f),
                        )
                        target == PlayTarget.WATCH -> ChipDefaults.primaryChipColors()
                        else -> ChipDefaults.secondaryChipColors()
                    },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            item {
                Chip(
                    onClick = {
                        onDismiss()
                        PlaybackManager.setTarget(PlayTarget.PHONE)
                        onPhone()
                    },
                    label = { Text("Phone") },
                    secondaryLabel = { Text(if (phoneReachable) "Absorb on your phone" else "Phone not connected") },
                    icon = { Icon(Icons.Rounded.Smartphone, contentDescription = null, modifier = Modifier.size(ChipDefaults.IconSize)) },
                    colors = if (target == PlayTarget.PHONE) ChipDefaults.primaryChipColors() else ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}
