package com.barnabas.absorb.wear.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.wear.compose.material.MaterialTheme
import coil.compose.AsyncImage
import coil.request.CachePolicy
import coil.request.ImageRequest
import com.barnabas.absorb.wear.AbsorbWearApp
import com.barnabas.absorb.wear.api.CoverArt
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.barnabas.absorb.wear.download.CoverStore
import kotlinx.coroutines.delay

/**
 * A book cover. A downloaded book's cover comes from the copy kept on the
 * watch; anything else is loaded from the server with the session's
 * current token and reverse-proxy headers (a plain URL 403s on proxied
 * servers, and BookOrbit tokens only last fifteen minutes). A failed load is
 * tried again with growing waits and whenever a network comes back, so a
 * blip does not leave the player blank. Falls back to a flat surface-
 * coloured placeholder while there is nothing to show.
 */
@Composable
fun BookCover(
    itemId: String?,
    creds: Credentials?,
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 6.dp,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val placeholder = modifier
        .clip(RoundedCornerShape(cornerRadius))
        .background(MaterialTheme.colors.surface)

    if (itemId == null || creds == null) {
        Box(placeholder)
        return
    }

    val context = LocalContext.current
    // The live session, so a token refreshed since this screen opened is used.
    val live by CredentialStore.credentials.collectAsState()
    val session = live?.takeIf { it.serverUrl == creds.serverUrl } ?: creds
    val network by AbsorbWearApp.networkGeneration.collectAsState()
    var attempt by remember(itemId) { mutableIntStateOf(0) }
    var failed by remember(itemId) { mutableIntStateOf(0) }

    // Retry after a failure: 4s, 8s, 16s, 32s, then only when a network
    // comes back (which bumps the network generation).
    LaunchedEffect(failed) {
        if (failed == 0 || failed > 4) return@LaunchedEffect
        delay(4_000L shl (failed - 1))
        attempt++
    }

    val kept = remember(itemId, attempt) { CoverStore.existing(itemId) }
    val request = remember(itemId, session.accessToken, session.customHeaders, attempt, network, kept) {
        val builder = ImageRequest.Builder(context).crossfade(true)
        if (kept != null) {
            builder.data(kept)
        } else {
            builder
                .data(CoverArt.url(session, itemId))
                .diskCachePolicy(CachePolicy.ENABLED)
                .addHeader("Authorization", "Bearer ${session.accessToken}")
                .apply { session.customHeaders.forEach { (key, value) -> addHeader(key, value) } }
        }
        builder.build()
    }

    Box(placeholder) {
        AsyncImage(
            model = request,
            contentDescription = null,
            contentScale = contentScale,
            onError = { failed++ },
            onSuccess = { failed = 0 },
            modifier = Modifier.fillMaxSize(),
        )
    }
}
