package com.barnabas.absorb.wear.api

import android.util.Log
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials

/**
 * One-shot fetch of "Continue Listening" candidates. The watch uses these
 * to let the user resume a session without touching the phone.
 *
 * Audiobookshelf answers `/api/me/items-in-progress`; BookOrbit's
 * equivalent is its dashboard's continue-listening scroller, whose cards are
 * reshaped into the same [InProgressItem] the list renders.
 */
class InProgressRepository(private val creds: Credentials) {

    private val client = AbsorbHttpClient(creds)

    private companion object {
        const val TAG = "AbsorbWearAuth"
    }

    suspend fun fetch(limit: Int = 10): AbsorbHttpClient.Result<List<InProgressItem>> {
        val result = fetchForBackend(limit)
        when (result) {
            is AbsorbHttpClient.Result.Ok -> Log.i(TAG, "continue listening (${creds.backend}): ${result.value.size} item(s)")
            is AbsorbHttpClient.Result.Unauthorized -> Log.w(TAG, "continue listening (${creds.backend}): HTTP ${result.code}")
            is AbsorbHttpClient.Result.Failed -> Log.w(TAG, "continue listening (${creds.backend}): ${result.message} (HTTP ${result.code})")
        }
        return result
    }

    private suspend fun fetchForBackend(limit: Int): AbsorbHttpClient.Result<List<InProgressItem>> {
        if (creds.isBookOrbit) return fetchBookOrbit(limit)
        val abs = fetchAudiobookshelf(limit)
        // A phone build from before the server type travelled with the
        // session pushes BookOrbit credentials labelled as Audiobookshelf.
        // The ABS route then 404s; if the BookOrbit one answers with the same
        // token, that is what this server is, so remember it.
        if (abs is AbsorbHttpClient.Result.Failed && abs.code == 404) {
            val asBookOrbit = InProgressRepository(creds.copy(backend = Credentials.BACKEND_BOOKORBIT))
            val bo = asBookOrbit.fetchBookOrbit(limit)
            if (bo is AbsorbHttpClient.Result.Ok) {
                Log.i(TAG, "Server answers BookOrbit routes; relabelling the session")
                val live = CredentialStore.credentials.value
                if (live != null && live.serverUrl == creds.serverUrl && !live.isBookOrbit) {
                    CredentialStore.update(
                        live.copy(backend = Credentials.BACKEND_BOOKORBIT, issuedAt = System.currentTimeMillis()),
                    )
                }
                return bo
            }
        }
        return abs
    }

    private suspend fun fetchAudiobookshelf(limit: Int): AbsorbHttpClient.Result<List<InProgressItem>> {
        val result = client.get(
            path = "/api/me/items-in-progress?limit=$limit",
            deserializer = InProgressResponse.serializer(),
        )
        return when (result) {
            is AbsorbHttpClient.Result.Ok -> AbsorbHttpClient.Result.Ok(result.value.libraryItems)
            is AbsorbHttpClient.Result.Unauthorized -> AbsorbHttpClient.Result.Unauthorized(result.code)
            is AbsorbHttpClient.Result.Failed -> AbsorbHttpClient.Result.Failed(result.message, result.code)
        }
    }

    private suspend fun fetchBookOrbit(limit: Int): AbsorbHttpClient.Result<List<InProgressItem>> {
        val result = client.get(
            path = "/api/v1/dashboard/scrollers/continue-listening?limit=${limit.coerceIn(1, 50)}",
            deserializer = BoScroller.serializer(),
        )
        return when (result) {
            is AbsorbHttpClient.Result.Ok -> AbsorbHttpClient.Result.Ok(
                result.value.books.map { card ->
                    InProgressItem(
                        id = card.itemId,
                        media = InProgressItem.Media(
                            metadata = InProgressItem.Metadata(
                                title = card.title,
                                authorName = card.authorName,
                            ),
                        ),
                    )
                },
            )
            is AbsorbHttpClient.Result.Unauthorized -> AbsorbHttpClient.Result.Unauthorized(result.code)
            is AbsorbHttpClient.Result.Failed -> AbsorbHttpClient.Result.Failed(result.message, result.code)
        }
    }
}
