package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.Smartphone
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.R
import com.barnabas.absorb.wear.sync.PhoneAuthClient
import com.barnabas.absorb.wear.sync.PhoneAuthClient.SendResult
import com.barnabas.absorb.wear.ui.components.AbsorbLogo
import com.barnabas.absorb.wear.ui.components.GlowBackdrop
import com.barnabas.absorb.wear.ui.components.WideButton
import kotlinx.coroutines.launch

/**
 * The welcome: the app's mark under its glow, a line on how to sign in,
 * and the two ways, from the phone or here on the watch.
 */
@Composable
fun AwaitingCredentialsScreen(onManualLogin: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val phoneClient = remember { PhoneAuthClient(context) }
    var status by remember { mutableStateOf<RequestStatus>(RequestStatus.Idle) }
    val listState = rememberScalingLazyListState(initialCenterItemIndex = 1)

    // No auto-request on composition: this screen reappears every time
    // the user signs out, and an automatic resync would silently undo
    // the sign-out before they could blink. The button below is the only
    // path that pings the phone.

    Box(Modifier.fillMaxSize()) {
        GlowBackdrop(height = 170.dp)
        ScalingLazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            item {
                AbsorbLogo(size = 72.dp)
            }
            item {
                Text(
                    text = "Absorb",
                    style = MaterialTheme.typography.title2,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
            item {
                Text(
                    text = stringResource(R.string.auth_waiting_body),
                    style = MaterialTheme.typography.caption2,
                    color = Color.White.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 26.dp, vertical = 4.dp),
                )
            }
            item {
                WideButton(
                    label = stringResource(R.string.auth_retry),
                    icon = Icons.Rounded.Smartphone,
                    primary = true,
                    enabled = status !is RequestStatus.Sending,
                    onClick = {
                        scope.launch {
                            status = RequestStatus.Sending
                            status = phoneClient.requestSync().toStatus()
                        }
                    },
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            item {
                WideButton(
                    label = stringResource(R.string.auth_manual),
                    icon = Icons.Rounded.Keyboard,
                    onClick = onManualLogin,
                    modifier = Modifier.padding(horizontal = 10.dp),
                )
            }
            item {
                val text = when (val s = status) {
                    RequestStatus.Idle -> ""
                    RequestStatus.Sending -> "Asking the phone…"
                    is RequestStatus.NoPhone -> "No phone connected. Is Bluetooth on?"
                    is RequestStatus.Sent -> "Asked ${s.nodes.joinToString()}, waiting…"
                    is RequestStatus.Failed -> "Failed: ${s.error}"
                }
                if (text.isNotEmpty()) {
                    Text(
                        text = text,
                        style = MaterialTheme.typography.caption2,
                        color = MaterialTheme.colors.onSurface.copy(alpha = 0.7f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    )
                } else {
                    Spacer(Modifier.height(4.dp))
                }
            }
        }
    }
}

private sealed class RequestStatus {
    object Idle : RequestStatus()
    object Sending : RequestStatus()
    object NoPhone : RequestStatus()
    data class Sent(val nodes: List<String>) : RequestStatus()
    data class Failed(val error: String) : RequestStatus()
}

private fun SendResult.toStatus(): RequestStatus = when (this) {
    SendResult.NoConnectedNodes -> RequestStatus.NoPhone
    is SendResult.Delivered -> RequestStatus.Sent(nodes)
    is SendResult.AllFailed -> RequestStatus.Failed(error)
}
