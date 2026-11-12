package com.barnabas.absorb.wear.sync

import android.util.Log
import com.barnabas.absorb.wear.AbsorbWearApp
import com.barnabas.absorb.wear.api.Bookmark
import com.barnabas.absorb.wear.playback.Chapter
import com.barnabas.absorb.wear.playback.PlaybackManager
import com.barnabas.absorb.wear.player.NowPlayingNotifier
import com.barnabas.absorb.wear.player.PhoneMirrorSession
import com.barnabas.absorb.wear.player.PlayerState
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.barnabas.absorb.wear.ui.PhoneTheme
import com.google.android.gms.wearable.DataEvent
import com.google.android.gms.wearable.DataEventBuffer
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.MessageEvent
import com.google.android.gms.wearable.WearableListenerService
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.json.JSONObject

/**
 * Receives `/absorb/player/state/v1` DataItems from the phone and feeds
 * [PlayerStateStore]. Registered statically in the manifest so the
 * service starts even when no activity is in the foreground (e.g. when
 * the user taps the watch and we draw straight into NowPlayingScreen).
 */
class PlayerSyncService : WearableListenerService() {

    private val json = Json { ignoreUnknownKeys = true }

    override fun onDataChanged(buffer: DataEventBuffer) {
        buffer.use { events ->
            for (event in events) {
                val path = event.dataItem.uri.path.orEmpty()
                if (path.startsWith(WearPaths.PLAYER_BOOKMARKS)) {
                    if (event.type == DataEvent.TYPE_CHANGED) handleBookmarks(event)
                    continue
                }
                if (path.startsWith(WearPaths.PLAYER_CHAPTERS)) {
                    if (event.type == DataEvent.TYPE_CHANGED) handleChapters(event)
                    continue
                }
                if (!path.startsWith(WearPaths.PLAYER_STATE)) continue
                when (event.type) {
                    DataEvent.TYPE_CHANGED -> handleChanged(event)
                    DataEvent.TYPE_DELETED -> {
                        PlayerStateStore.clear()
                        PhoneMirrorSession.release()
                        NowPlayingNotifier.cancel(applicationContext)
                    }
                }
            }
        }
    }

    override fun onMessageReceived(event: MessageEvent) {
        if (event.path != WearPaths.PLAYER_VOLUME) {
            super.onMessageReceived(event)
            return
        }
        try {
            val obj = JSONObject(event.data.decodeToString())
            PlayerStateStore.updateVolume(obj.getInt("index"), obj.getInt("max"))
        } catch (e: Exception) {
            Log.w(TAG, "phone volume message unreadable", e)
        }
    }

    private fun handleBookmarks(event: DataEvent) {
        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
        val payload = map.getByteArray(KEY_PAYLOAD) ?: return
        val (itemId, bookmarks) = parseBookmarks(payload) ?: return
        Log.i(TAG, "phone bookmarks for $itemId: ${bookmarks.size}")
        PlayerStateStore.updateBookmarks(itemId, bookmarks)
    }

    private fun handleChapters(event: DataEvent) {
        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
        val payload = map.getByteArray(KEY_PAYLOAD) ?: return
        val (itemId, chapters) = parseChapters(payload) ?: return
        Log.i(TAG, "phone chapters for $itemId: ${chapters.size}")
        PlayerStateStore.updateChapters(itemId, chapters)
    }

    private fun handleChanged(event: DataEvent) {
        val map = DataMapItem.fromDataItem(event.dataItem).dataMap
        val payload = map.getByteArray(KEY_PAYLOAD) ?: return
        val state = try {
            json.decodeFromString(PlayerState.serializer(), payload.decodeToString())
        } catch (e: SerializationException) {
            Log.e(TAG, "Failed to decode player state payload", e)
            return
        }
        PlayerStateStore.update(state)
        adoptSettings(state)
        PhoneAutoOpen.onPhoneState(applicationContext, state)
        // While the phone plays and the watch itself does not, the book shows
        // up on the wrist as our own notification with transport buttons,
        // tapping it opens the phone-remote screen. Not while our app is on
        // screen (it is the control surface then) or the watch is playing.
        if (!PlaybackManager.uiState.value.hasItem) {
            PhoneMirrorSession.update(applicationContext, state)
        }
        if (!AbsorbWearApp.isInForeground && !PlaybackManager.uiState.value.hasItem) {
            NowPlayingNotifier.update(applicationContext, state)
        } else {
            NowPlayingNotifier.cancel(applicationContext)
        }
    }

    companion object {
        private const val TAG = "AbsorbWearPlayer"
        const val KEY_PAYLOAD = "payload"

        /** The chapters DataItem's payload as the book it is for and its list. */
        fun parseChapters(payload: ByteArray): Pair<String, List<Chapter>>? {
            return try {
                val obj = JSONObject(payload.decodeToString())
                val itemId = obj.optString("itemId").ifBlank { return null }
                val arr = obj.optJSONArray("chapters") ?: return null
                val chapters = ArrayList<Chapter>(arr.length())
                for (i in 0 until arr.length()) {
                    val c = arr.getJSONObject(i)
                    chapters += Chapter(
                        id = i,
                        start = c.optDouble("start", 0.0),
                        end = c.optDouble("end", 0.0),
                        title = c.optString("title").ifBlank { null },
                    )
                }
                itemId to chapters
            } catch (e: Exception) {
                Log.w(TAG, "phone chapters unreadable", e)
                null
            }
        }

        /** The bookmarks DataItem's payload as the book it is for and its list. */
        fun parseBookmarks(payload: ByteArray): Pair<String, List<Bookmark>>? {
            return try {
                val obj = JSONObject(payload.decodeToString())
                val itemId = obj.optString("itemId").ifBlank { return null }
                val arr = obj.optJSONArray("bookmarks") ?: return null
                val list = ArrayList<Bookmark>(arr.length())
                for (i in 0 until arr.length()) {
                    val b = arr.getJSONObject(i)
                    val note = b.optString("note").ifBlank { null }
                    val title = b.optString("title").ifBlank { "Bookmark" }
                    list += Bookmark(
                        timeSec = b.optDouble("time", 0.0),
                        title = if (note == null) title else "$title - $note",
                        createdAtMs = b.optLong("createdAt", 0L),
                        id = b.optString("id").ifBlank { null },
                    )
                }
                itemId to list
            } catch (e: Exception) {
                Log.w(TAG, "phone bookmarks unreadable", e)
                null
            }
        }

        /** The playback settings carried by a phone state become the
         *  watch's, whether the state was pushed or read back at start. */
        fun adoptSettings(state: PlayerState) {
            PlaybackManager.adoptSkips(state.skipBackSec, state.skipForwardSec)
            PlaybackManager.adoptSettings(
                PlaybackManager.Settings(
                    autoRewindEnabled = state.autoRewindEnabled,
                    autoRewindMinSec = state.autoRewindMinSec,
                    autoRewindMaxSec = state.autoRewindMaxSec,
                    autoRewindDelaySec = state.autoRewindDelaySec,
                    autoRewindChapterBarrier = state.autoRewindChapterBarrier,
                    autoRewindSessionStart = state.autoRewindSessionStart,
                    skipChapterBarrier = state.skipChapterBarrier,
                    speedAdjustedTime = state.speedAdjustedTime,
                    sleepAddMinutes = state.sleepAddMinutes,
                    speedPresets = state.speedPresets.ifEmpty { PlaybackManager.settings.speedPresets },
                ),
            )
            if (state.hasBook) PlaybackManager.adoptSpeed(state.speed)
            PhoneTheme.adopt(state.themeColor)
        }
    }
}
