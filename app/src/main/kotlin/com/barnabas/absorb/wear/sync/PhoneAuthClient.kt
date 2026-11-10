package com.barnabas.absorb.wear.sync

import android.content.Context
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import kotlinx.coroutines.tasks.await
import kotlinx.serialization.json.Json
import com.barnabas.absorb.wear.auth.Credentials

/**
 * Sends one-shot messages from the watch back to the phone, e.g. "please
 * republish auth" (when the watch was just installed and missed the last
 * push) or "the user signed out on the watch — wipe your DataItem".
 */
class PhoneAuthClient(context: Context) {

    private val messageClient = Wearable.getMessageClient(context.applicationContext)
    private val nodeClient = Wearable.getNodeClient(context.applicationContext)
    private val json = Json { encodeDefaults = true }

    sealed class SendResult {
        /** No paired phone is currently reachable. */
        object NoConnectedNodes : SendResult()
        /** Wearable API returned a list of nodes but every send threw. */
        data class AllFailed(val nodes: List<String>, val error: String) : SendResult()
        /** At least one node accepted the message — they're listed by display name. */
        data class Delivered(val nodes: List<String>) : SendResult()
    }

    suspend fun requestSync(): SendResult = sendToAllConnectedNodes(WearPaths.AUTH_REQUEST, ByteArray(0))

    suspend fun signOut(): SendResult = sendToAllConnectedNodes(WearPaths.AUTH_SIGN_OUT, ByteArray(0))

    suspend fun canReachPhone(): Boolean = try {
        nodeClient.connectedNodes.await().isNotEmpty()
    } catch (e: Exception) {
        Log.w(TAG, "connectedNodes() preflight failed", e)
        false
    }

    suspend fun returnRotatedCredentials(credentials: Credentials): SendResult {
        val payload = json.encodeToString(Credentials.serializer(), credentials)
            .toByteArray(Charsets.UTF_8)
        return sendToAllConnectedNodes(WearPaths.AUTH_ROTATED, payload)
    }

    private suspend fun sendToAllConnectedNodes(path: String, data: ByteArray): SendResult {
        val nodes: List<Node> = try {
            nodeClient.connectedNodes.await()
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes() failed", e)
            return SendResult.AllFailed(emptyList(), e.message ?: e.javaClass.simpleName)
        }
        Log.i(TAG, "connectedNodes -> ${nodes.size} (${nodes.joinToString { "${it.displayName}(nearby=${it.isNearby})" }})")
        if (nodes.isEmpty()) return SendResult.NoConnectedNodes

        // Don't filter on `isNearby` — paired phones occasionally report
        // false even when connected, and skipping them gives the user a
        // silent failure. Let MessageClient itself decide what's reachable.
        val delivered = mutableListOf<String>()
        var lastError: String? = null
        for (node in nodes) {
            try {
                messageClient.sendMessage(node.id, path, data).await()
                delivered += node.displayName
                Log.i(TAG, "sent $path to ${node.displayName}")
            } catch (e: Exception) {
                lastError = e.message ?: e.javaClass.simpleName
                Log.w(TAG, "sendMessage($path) to ${node.displayName} failed", e)
            }
        }
        return when {
            delivered.isNotEmpty() -> SendResult.Delivered(delivered)
            else -> SendResult.AllFailed(nodes.map { it.displayName }, lastError ?: "unknown")
        }
    }

    companion object {
        private const val TAG = "AbsorbWearAuth"
    }
}
