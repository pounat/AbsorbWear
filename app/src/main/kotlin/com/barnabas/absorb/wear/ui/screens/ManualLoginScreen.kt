package com.barnabas.absorb.wear.ui.screens

import android.app.Activity
import android.app.RemoteInput
import android.content.ActivityNotFoundException
import android.content.Intent
import android.util.Log
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.ActivityResult
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.auth.AbsorbApi
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.barnabas.absorb.wear.ui.components.GlowBackdrop
import com.barnabas.absorb.wear.ui.components.WideButton
import kotlinx.coroutines.launch

private enum class Field(val label: String, val placeholder: String, val secret: Boolean) {
    Url("Server URL", "abs.example.com", false),
    User("Username", "you", false),
    Password("Password", "tap to enter", true),
}

@Composable
fun ManualLoginScreen(onCancel: () -> Unit) {
    var backend by remember { mutableStateOf(Credentials.BACKEND_ABS) }
    var serverUrl by remember { mutableStateOf("") }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var inFlight by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var editing by remember { mutableStateOf<Field?>(null) }
    val scope = rememberCoroutineScope()

    fun valueOf(field: Field) = when (field) {
        Field.Url -> serverUrl
        Field.User -> username
        Field.Password -> password
    }

    fun assign(field: Field, text: String) {
        when (field) {
            Field.Url -> serverUrl = text
            Field.User -> username = text
            Field.Password -> password = text
        }
    }

    // The system remote input (keyboard, voice, or type-on-phone) where the
    // watch has it; this watch's own keyboard inside the app where it does not.
    val remoteInput = rememberRemoteInput(onResult = ::assign)
    fun edit(field: Field) {
        if (!remoteInput.launch(field, valueOf(field))) editing = field
    }

    editing?.let { field ->
        InlineEditor(
            field = field,
            initial = valueOf(field),
            onDone = { text ->
                assign(field, text)
                editing = null
            },
            onCancel = { editing = null },
        )
        return
    }

    val canSubmit = !inFlight &&
        serverUrl.isNotBlank() && username.isNotBlank() && password.isNotBlank()

    Box(Modifier.fillMaxSize()) {
    GlowBackdrop()
    ScalingLazyColumn(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        item {
            Text(
                text = "Sign in",
                style = MaterialTheme.typography.title3,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
            )
        }

        item {
            // Which kind of server: two pills, the chosen one solid.
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                ServerTypePill("Audiobookshelf", backend != Credentials.BACKEND_BOOKORBIT) { backend = Credentials.BACKEND_ABS }
                ServerTypePill("BookOrbit", backend == Credentials.BACKEND_BOOKORBIT) { backend = Credentials.BACKEND_BOOKORBIT }
            }
        }
        item { LoginField(label = Field.Url.label, value = serverUrl, placeholder = Field.Url.placeholder) { edit(Field.Url) } }
        item { LoginField(label = Field.User.label, value = username, placeholder = Field.User.placeholder) { edit(Field.User) } }
        item { LoginField(label = Field.Password.label, value = mask(password), placeholder = Field.Password.placeholder) { edit(Field.Password) } }

        item {
            WideButton(
                label = if (inFlight) "Signing in…" else "Sign in",
                primary = true,
                enabled = canSubmit,
                modifier = Modifier.padding(horizontal = 10.dp),
                onClick = {
                    inFlight = true
                    error = null
                    scope.launch {
                        val result = AbsorbApi.login(serverUrl, username, password, backend)
                        result.onSuccess { creds ->
                            // Update flows into CredentialStore, AbsorbApp
                            // recomposes, SignedInScreen replaces us. No
                            // need to call onCancel.
                            CredentialStore.update(creds)
                        }
                        result.onFailure { e ->
                            error = e.message ?: "Login failed"
                            inFlight = false
                        }
                    }
                },
            )
        }

        if (error != null) {
            item {
                Text(
                    text = error!!,
                    style = MaterialTheme.typography.caption2,
                    color = MaterialTheme.colors.error,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }

        item {
            WideButton(
                label = "Cancel",
                enabled = !inFlight,
                onClick = onCancel,
                modifier = Modifier.padding(horizontal = 10.dp),
            )
        }
    }
    }
}

@Composable
private fun ServerTypePill(label: String, selected: Boolean, onClick: () -> Unit) {
    val tint = MaterialTheme.colors.primary
    Text(
        text = label,
        style = MaterialTheme.typography.caption2,
        fontWeight = FontWeight.SemiBold,
        color = if (selected) MaterialTheme.colors.onPrimary else Color.White.copy(alpha = 0.8f),
        modifier = Modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (selected) tint else tint.copy(alpha = 0.18f))
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** One field as a tinted card: its name small above what it holds. */
@Composable
private fun LoginField(
    label: String,
    value: String,
    placeholder: String,
    onClick: () -> Unit,
) {
    val tint = MaterialTheme.colors.primary
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(tint.copy(alpha = 0.16f))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.caption3,
            color = Color.White.copy(alpha = 0.6f),
        )
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.body2,
            color = if (value.isBlank()) Color.White.copy(alpha = 0.4f) else Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A one-field editor using the watch's own keyboard, for watches where the
 * system remote-input activity does not exist (Pixel Watch 4 on Wear OS 6
 * has no handler for it, and launching it crashed the app).
 */
@Composable
private fun InlineEditor(
    field: Field,
    initial: String,
    onDone: (String) -> Unit,
    onCancel: () -> Unit,
) {
    var text by remember(field) { mutableStateOf(initial) }
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(field) {
        focusRequester.requestFocus()
        keyboard?.show()
    }
    val textColor = MaterialTheme.colors.onSurface

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(
            text = field.label,
            style = MaterialTheme.typography.caption1,
            color = textColor.copy(alpha = 0.7f),
        )
        Spacer(Modifier.height(6.dp))
        BasicTextField(
            value = text,
            onValueChange = { text = it.replace("\n", "") },
            singleLine = true,
            textStyle = TextStyle(color = textColor, fontSize = MaterialTheme.typography.body1.fontSize),
            cursorBrush = SolidColor(MaterialTheme.colors.primary),
            visualTransformation = if (field.secret) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = when (field) {
                    Field.Url -> KeyboardType.Uri
                    Field.User -> KeyboardType.Email
                    Field.Password -> KeyboardType.Password
                },
                imeAction = ImeAction.Done,
                autoCorrectEnabled = false,
            ),
            keyboardActions = KeyboardActions(onDone = { onDone(text) }),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
            decorationBox = { inner ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colors.surface)
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                ) {
                    if (text.isEmpty()) {
                        Text(
                            text = field.placeholder,
                            style = MaterialTheme.typography.body1,
                            color = textColor.copy(alpha = 0.4f),
                        )
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(8.dp))
        Chip(
            label = { Text("Done") },
            onClick = { onDone(text) },
            colors = ChipDefaults.primaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(4.dp))
        Chip(
            label = { Text("Cancel") },
            onClick = onCancel,
            colors = ChipDefaults.secondaryChipColors(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

private fun mask(value: String): String = "•".repeat(value.length)

/**
 * Wear OS's RemoteInput launches the system's full-screen IME (voice,
 * keyboard, type-on-phone, depending on the user's pick) where a watch has
 * it. Hand-rolled rather than depending on androidx.wear:wear-input: the
 * helper just sets two extras with the `android.remoteinput.*` keys.
 */
@Composable
private fun rememberRemoteInput(onResult: (Field, String) -> Unit): RemoteInputHandle {
    var pending by remember { mutableStateOf<Field?>(null) }
    val launcher: ActivityResultLauncher<Intent> = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result: ActivityResult ->
        val field = pending ?: return@rememberLauncherForActivityResult
        pending = null
        if (result.resultCode != Activity.RESULT_OK) return@rememberLauncherForActivityResult
        val data = result.data ?: return@rememberLauncherForActivityResult
        val bundle = RemoteInput.getResultsFromIntent(data) ?: return@rememberLauncherForActivityResult
        val text = bundle.getCharSequence(RESULT_KEY)?.toString() ?: return@rememberLauncherForActivityResult
        onResult(field, text)
    }

    return remember(launcher) {
        RemoteInputHandle { field, initial ->
            val remoteInput = RemoteInput.Builder(RESULT_KEY)
                .setLabel(field.label)
                .build()
            val intent = Intent(ACTION_REMOTE_INPUT).apply {
                putExtra(EXTRA_REMOTE_INPUTS, arrayOf(remoteInput))
                putExtra(EXTRA_TITLE, field.label)
            }
            if (initial.isNotEmpty()) {
                val seed = android.os.Bundle().apply { putCharSequence(RESULT_KEY, initial) }
                RemoteInput.addResultsToIntent(arrayOf(remoteInput), intent, seed)
            }
            pending = field
            try {
                launcher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                pending = null
                Log.i(TAG, "No system remote input on this watch; using the in-app editor")
                false
            }
        }
    }
}

private class RemoteInputHandle(private val onLaunch: (Field, String) -> Boolean) {
    /** True when the system input opened; false when the caller must edit in-app. */
    fun launch(field: Field, initial: String): Boolean = onLaunch(field, initial)
}

private const val TAG = "AbsorbWearAuth"

// String constants pulled from androidx.wear.input.RemoteInputIntentHelper
// so we don't need that artifact.
private const val ACTION_REMOTE_INPUT = "androidx.wear.input.action.REMOTE_INPUT"
private const val EXTRA_REMOTE_INPUTS = "android.remoteinput.remoteInputs"
private const val EXTRA_TITLE = "android.remoteinput.title"
private const val RESULT_KEY = "text"
