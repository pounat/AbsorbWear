package com.barnabas.absorb.wear.sync

import android.util.Log
import com.barnabas.absorb.wear.auth.CredentialStore
import com.barnabas.absorb.wear.auth.Credentials
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.coroutines.launch
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Picks up credential payloads pushed by the phone over the Wearable Data
 * Layer and hands them to [CredentialStore]. Registered in the manifest
 * against the [WearPaths.AUTH] path so Google Play Services launches us
 * even if the watch app isn't currently running.
 */
class CredentialSyncService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onCreate() {
        super.onCreate()
        // Service can be started in isolation by Play Services, before any
        // Activity has touched the store. Init here so the first apply()
        // doesn't NPE.
        CredentialStore.init(applicationContext)
    }

    override fun onDataChanged(buffer: DataEventBuffer) {
        buffer.use { events ->
            for (event in events) {
                if (!event.dataItem.uri.path.orEmpty().startsWith(WearPaths.AUTH)) continue
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> handleChanged(event)
                    DataEvent.TYPE_DELETED -> {
                        Log.i(TAG, "Auth DataItem deleted on phone — signing out on watch")
                        CredentialStore.clear()
                    }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        // Phone-side may also send a one-shot message variant if the
        // DataItem hasn't propagated yet (rare; mostly a belt-and-braces
        // fallback). Decode the same way.
        if (!event.path.startsWith(WearPaths.AUTH)) return
        decodeAndStore(event.data)
    }

    private fun handleChanged(event: DataEvent) {
        val item = DataMapItem.fromDataItem(event.dataItem)
        val payload = item.dataMap.getByteArray(KEY_PAYLOAD)
        if (payload == null) {
            Log.w(TAG, "Auth DataItem changed but payload was empty")
            return
        }
        decodeAndStore(payload)
    }

    private fun decodeAndStore(bytes: ByteArray) {
        val text = bytes.decodeToString()
        var creds = try {
            json.decodeFromString(Credentials.serializer(), text)
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to decode credentials payload", e)
            return
        }
        // A server does not change type. A push labelling a server the watch
        // already knows as BookOrbit as Audiobookshelf comes from a phone
        // path that never learned the type (its republish-from-prefs path
        // defaulted to "abs"); keeping the known type stops every call from
        // 404ing until the list fetch re-detects it.
        var kept = false
        val known = CredentialStore.credentials.value
        if (known != null && known.serverUrl == creds.serverUrl && known.isBookOrbit && !creds.isBookOrbit) {
            creds = creds.copy(backend = known.backend)
            kept = true
        }
        CredentialStore.update(creds)
        Log.i(TAG, "Updated credentials from phone for user=${creds.username} backend=${creds.backend}${if (kept) " (kept)" else ""}")
        // A sign-in from the phone brings its playback settings along too,
        // from the state it last pushed, so the watch sounds like the phone
        // from the first book.
        kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
            PlayerStateClient.seedFromPhone(applicationContext)
        }
    }

    companion object {
        private const val TAG = "AbsorbWearAuth"
        const val KEY_PAYLOAD = "payload"
    }
}
