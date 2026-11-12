package com.barnabas.absorb.wear.sync

import android.content.Context
import android.net.Uri
import android.util.Log
import com.barnabas.absorb.wear.player.PlayerState
import com.barnabas.absorb.wear.player.PlayerStateStore
import com.google.android.gms.wearable.DataMapItem
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json

/**
 * One-shot reader for the persisted `/absorb/player/state/v1` DataItem.
 *
 * [PlayerSyncService.onDataChanged] only fires on *changes*. On a cold
 * process start (app or media service launched while the phone is already
 * playing) the in-memory [PlayerStateStore] is empty, so we proactively
 * pull the last-published state instead of showing "nothing playing" until
 * the next ~2s push. Without this, a cold-started surface flashes blank.
 */
object PlayerStateClient {

    private const val TAG = "AbsorbWearPlayer"
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun seedFromPhone(context: Context) {
        val dataClient = Wearable.getDataClient(context.applicationContext)
        val nodeClient = Wearable.getNodeClient(context.applicationContext)

        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes() failed while seeding player state", e)
            return
        }

        for (node in nodes) {
            val uri = Uri.Builder()
                .scheme("wear")
                .authority(node.id)
                .path(WearPaths.PLAYER_STATE)
                .build()
            val item = try {
                dataClient.getDataItem(uri).await()
            } catch (e: Exception) {
                Log.w(TAG, "getDataItem($uri) failed", e)
                continue
            } ?: continue

            val payload = DataMapItem.fromDataItem(item).dataMap
                .getByteArray(PlayerSyncService.KEY_PAYLOAD) ?: continue
            val state = try {
                json.decodeFromString(PlayerState.serializer(), payload.decodeToString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to decode seeded player state", e)
                continue
            }
            PlayerStateStore.update(state)
            PlayerSyncService.adoptSettings(state)
            Log.i(TAG, "seeded the phone's player state and settings")
            break
        }
        seedChapters(context)
        seedBookmarks(context)
    }

    /** The phone's bookmarks for its book, from the Data Layer's copy, the
     *  way [seedChapters] gets the chapters. */
    suspend fun seedBookmarks(context: Context): Boolean {
        val dataClient = Wearable.getDataClient(context.applicationContext)
        val uri = Uri.Builder().scheme("wear").authority("*").path(WearPaths.PLAYER_BOOKMARKS).build()
        val items = try {
            dataClient.getDataItems(uri).await()
        } catch (e: Exception) {
            Log.w(TAG, "getDataItems($uri) failed", e)
            return false
        }
        try {
            for (item in items) {
                val payload = DataMapItem.fromDataItem(item).dataMap.getByteArray(PlayerSyncService.KEY_PAYLOAD) ?: continue
                val (itemId, bookmarks) = PlayerSyncService.parseBookmarks(payload) ?: continue
                PlayerStateStore.updateBookmarks(itemId, bookmarks)
                Log.i(TAG, "seeded the phone's bookmarks for $itemId: ${bookmarks.size}")
                return true
            }
        } finally {
            items.release()
        }
        return false
    }

    /**
     * The phone's chapter list for its book, from the copy the Data Layer
     * keeps on the watch. The phone sends the list once per book, so after
     * this process has been away it is here and nowhere else. True when a
     * list was found.
     */
    suspend fun seedChapters(context: Context): Boolean {
        val dataClient = Wearable.getDataClient(context.applicationContext)
        val uri = Uri.Builder().scheme("wear").authority("*").path(WearPaths.PLAYER_CHAPTERS).build()
        val items = try {
            dataClient.getDataItems(uri).await()
        } catch (e: Exception) {
            Log.w(TAG, "getDataItems($uri) failed", e)
            return false
        }
        try {
            for (item in items) {
                val payload = DataMapItem.fromDataItem(item).dataMap.getByteArray(PlayerSyncService.KEY_PAYLOAD) ?: continue
                val (itemId, chapters) = PlayerSyncService.parseChapters(payload) ?: continue
                PlayerStateStore.updateChapters(itemId, chapters)
                Log.i(TAG, "seeded the phone's chapters for $itemId: ${chapters.size}")
                return true
            }
        } finally {
            items.release()
        }
        return false
    }
}
