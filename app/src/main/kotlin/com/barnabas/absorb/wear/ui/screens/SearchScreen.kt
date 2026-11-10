package com.barnabas.absorb.wear.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import com.barnabas.absorb.wear.api.AbsorbHttpClient
import com.barnabas.absorb.wear.api.InProgressItem
import com.barnabas.absorb.wear.api.LibraryRepository
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.ui.components.BookRow
import com.barnabas.absorb.wear.ui.components.ExpressiveSpinner
import androidx.compose.runtime.collectAsState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Search the whole library by title, author or series, a row per hit. */
@Composable
fun SearchScreen(creds: Credentials, onOpenBook: (InProgressItem) -> Unit) {
    val progressRows by LibraryRepository.progress.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val listState = rememberScalingLazyListState()
    val keyboard = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<InProgressItem>?>(null) }
    var searching by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    fun run() {
        keyboard?.hide()
        focusManager.clearFocus()
        val q = query.trim()
        if (q.isEmpty()) return
        searching = true
        error = null
        scope.launch {
            val r = withContext(Dispatchers.IO) { LibraryRepository(creds).search(q) }
            searching = false
            when (r) {
                is AbsorbHttpClient.Result.Ok -> results = r.value
                is AbsorbHttpClient.Result.Unauthorized -> error = "Sign in again (HTTP ${r.code})"
                is AbsorbHttpClient.Result.Failed -> error = r.message
            }
        }
    }

    ScalingLazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp)) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(MaterialTheme.colors.surface, RoundedCornerShape(18.dp))
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                ) {
                    if (query.isEmpty()) {
                        Text("Search library", style = MaterialTheme.typography.body2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.5f))
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = MaterialTheme.colors.onSurface, fontSize = MaterialTheme.typography.body2.fontSize),
                        cursorBrush = SolidColor(MaterialTheme.colors.primary),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { run() }),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                Button(onClick = { run() }, colors = ButtonDefaults.primaryButtonColors(), modifier = Modifier.padding(start = 6.dp).size(36.dp)) {
                    Icon(Icons.Rounded.Search, contentDescription = "Search", modifier = Modifier.size(18.dp))
                }
            }
        }
        when {
            searching -> item { ExpressiveSpinner(modifier = Modifier.size(28.dp).padding(top = 8.dp), color = MaterialTheme.colors.primary) }
            error != null -> item {
                Text(error.orEmpty(), style = MaterialTheme.typography.caption3, color = MaterialTheme.colors.error, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            results != null && results!!.isEmpty() -> item {
                Text("No matches", style = MaterialTheme.typography.caption2, color = MaterialTheme.colors.onSurface.copy(alpha = 0.6f), textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            }
            results != null -> items(results!!, key = { it.id + (it.episodeId ?: "") }) { item ->
                BookRow(
                    item = item,
                    creds = creds,
                    fraction = progressRows[item.id + (item.episodeId?.let { "-$it" } ?: "")]?.let { if (it.finished) 1f else it.fraction },
                    modifier = Modifier.padding(horizontal = 6.dp),
                    onClick = { onOpenBook(item) },
                )
            }
        }
    }
}
