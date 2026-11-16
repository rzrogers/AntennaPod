package de.danoeh.antennapod.wearos.sync

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.android.gms.wearable.Node
import com.google.android.gms.wearable.Wearable
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.tasks.await

object WearMessageSender {
    private const val TAG = "WearMessageSender"
    private const val FLUSH_EVERY_MILLIS = 10_000L

    private val _nearby = MutableStateFlow(true)
    val nearby: StateFlow<Boolean> = _nearby

    @Volatile
    private var lastFlush = 0L

    suspend fun send(context: Context, path: String) {
        if (WatchPlayback.intercept(context, path)) {
            return
        }
        val node = phoneNode(context)
        if ((node == null || !node.isNearby) && WatchPlayback.offline(context, path)) {
            return
        }
        if (node != null) {
            deliver(context, node, path, null)
        }
    }

    suspend fun sendToPhone(context: Context, path: String, data: ByteArray? = null): Boolean {
        val node = phoneNode(context) ?: return false
        return deliver(context, node, path, data) && node.isNearby
    }

    suspend fun isPhoneNearby(context: Context): Boolean = phoneNode(context)?.isNearby == true

    private suspend fun phoneNode(context: Context): Node? {
        val node = try {
            val nodes = Wearable.getNodeClient(context).connectedNodes.await()
            nodes.firstOrNull { it.isNearby } ?: nodes.firstOrNull()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to look for the phone", e)
            null
        }
        val isNearby = node?.isNearby == true
        _nearby.value = isNearby
        if (isNearby && node != null) {
            flushRecord(context, node)
        }
        return node
    }

    private suspend fun flushRecord(context: Context, node: Node) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastFlush < FLUSH_EVERY_MILLIS) {
            return
        }
        lastFlush = now
        val app = context.applicationContext
        val data = WatchStore.recordBytes(app) ?: return
        WatchPlayback.recordSentAt = System.currentTimeMillis()
        deliver(app, node, PlusWear.WATCH_RECORD, data)
    }

    private suspend fun deliver(context: Context, node: Node, path: String, data: ByteArray?): Boolean {
        return try {
            Wearable.getMessageClient(context).sendMessage(node.id, path, data).await()
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send message to phone", e)
            false
        }
    }
}
