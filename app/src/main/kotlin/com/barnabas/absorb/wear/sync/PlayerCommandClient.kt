package com.barnabas.absorb.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await

/**
 * Fires play-pause / skip messages from the watch to the paired phone.
 * Bare-bones: every command is an empty-payload message, the path is the
 * verb. We don't wait for a "command applied" reply — the next
 * `/absorb/player/state/v1` push from the phone serves as the implicit
 * acknowledgement.
 */
class PlayerCommandClient(context: Context) {

    private val messageClient = Wearable.getMessageClient(context.applicationContext)
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)

    suspend fun playPause() = send(WearPaths.PLAYER_CMD_PLAY_PAUSE)
    suspend fun skipBack() = send(WearPaths.PLAYER_CMD_SKIP_BACK)
    suspend fun skipForward() = send(WearPaths.PLAYER_CMD_SKIP_FORWARD)
    suspend fun volumeUp() = send(WearPaths.PLAYER_CMD_VOLUME_UP)
    suspend fun volumeDown() = send(WearPaths.PLAYER_CMD_VOLUME_DOWN)
    suspend fun volumeGet() = send(WearPaths.PLAYER_CMD_VOLUME_GET)
    suspend fun nextChapter() = send(WearPaths.PLAYER_CMD_NEXT_CHAPTER)
    suspend fun previousChapter() = send(WearPaths.PLAYER_CMD_PREV_CHAPTER)
    suspend fun bookmark() = send(WearPaths.PLAYER_CMD_BOOKMARK)
    suspend fun sleepMinutes(minutes: Int) = send(WearPaths.PLAYER_CMD_SLEEP, "{\"minutes\":$minutes}".toByteArray())
    suspend fun sleepChapters(chapters: Int) = send(WearPaths.PLAYER_CMD_SLEEP, "{\"chapters\":$chapters}".toByteArray())
    suspend fun sleepAdd(minutes: Int) = send(WearPaths.PLAYER_CMD_SLEEP, "{\"add\":$minutes}".toByteArray())
    suspend fun sleepCancel() = send(WearPaths.PLAYER_CMD_SLEEP, "{\"cancel\":true}".toByteArray())

    /** The watch saw the wearer fall asleep at [sinceMs]. With
     *  [handledOnWatch] the watch paused its own player and the phone only
     *  notes it; otherwise the phone pauses what it plays. */
    suspend fun sleepDetected(sinceMs: Long, handledOnWatch: Boolean) = send(
        WearPaths.PLAYER_CMD_SLEEP,
        "{\"asleep\":true,\"sinceMs\":$sinceMs,\"watchHandled\":$handledOnWatch}".toByteArray(),
    )
    suspend fun seekTo(positionMs: Long) = send(WearPaths.PLAYER_CMD_SEEK, "{\"positionMs\":$positionMs}".toByteArray())
    suspend fun stop() = send(WearPaths.PLAYER_CMD_STOP)
    suspend fun setSpeed(speed: Float) = send(WearPaths.PLAYER_CMD_SPEED, "{\"speed\":$speed}".toByteArray())

    /** Ask the phone to open this app on the watch; see [PhoneAutoOpen]. */
    suspend fun openWatchApp() = send(WearPaths.PLAYER_CMD_OPEN_WATCH)

    /** Ask the phone to start playback of a specific item (replaces any
     *  current session). Payload is JSON because empty messages can't
     *  carry the item id. False when it reached no phone. */
    /** [positionSec] is where the watch was in the book, for the phone to
     *  start at; null lets the phone pick its own spot. */
    suspend fun playItem(itemId: String, episodeId: String? = null, positionSec: Double? = null): Boolean {
        val payload = buildString {
            append('{')
            append("\"itemId\":\"")
            append(itemId.escapeForJson())
            append('"')
            if (episodeId != null) {
                append(",\"episodeId\":\"")
                append(episodeId.escapeForJson())
                append('"')
            }
            if (positionSec != null && positionSec.isFinite()) {
                append(",\"position\":")
                append(String.format(java.util.Locale.ROOT, "%.3f", positionSec))
            }
            append('}')
        }.toByteArray(Charsets.UTF_8)
        return send(WearPaths.PLAYER_CMD_PLAY_ITEM, payload)
    }

    private fun String.escapeForJson(): String = buildString(length) {
        for (c in this@escapeForJson) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> append(c)
        }
    }

    /** True when at least one phone took the message. */
    private suspend fun send(path: String, data: ByteArray = EMPTY): Boolean {
        if (path != WearPaths.PLAYER_CMD_OPEN_WATCH) lastCommandAt = System.currentTimeMillis()
        val nodes = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes() failed for $path", e)
            return false
        }
        if (nodes.isEmpty()) Log.w(TAG, "no connected node for $path")
        var sent = false
        for (node in nodes) {
            try {
                Log.i(TAG, "$path -> ${node.displayName}")
                messageClient.sendMessage(node.id, path, data).await()
                sent = true
            } catch (e: Exception) {
                Log.w(TAG, "sendMessage($path) to ${node.displayName} failed", e)
            }
        }
        return sent
    }

    companion object {
        private const val TAG = "AbsorbWearPlayer"
        private val EMPTY = ByteArray(0)

        /** When the watch last sent the phone a player command. */
        @Volatile
        var lastCommandAt = 0L
            private set
    }
}
