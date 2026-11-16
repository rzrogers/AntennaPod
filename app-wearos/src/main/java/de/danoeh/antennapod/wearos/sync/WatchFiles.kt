package de.danoeh.antennapod.wearos.sync

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.util.Log
import com.google.android.gms.wearable.Wearable
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object WatchFiles {
    const val SOURCE_NONE = 0
    const val SOURCE_PHONE_WIFI = 1
    const val SOURCE_WIFI = 2
    const val SOURCE_BLUETOOTH = 3
    private const val TAG = "WatchFiles"
    private const val KEEP_FILES = 3
    private const val PING_MILLIS = 3_000L
    private const val WIFI_WAIT_MILLIS = 12_000
    private const val MAX_REDIRECTS = 6

    private class WifiHold(
        val manager: ConnectivityManager,
        val callback: ConnectivityManager.NetworkCallback,
        val network: Network
    ) {
        fun release() {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.d(TAG, "Wi-Fi request already released", e)
            }
        }
    }

    private fun directory(context: Context): File = File(context.filesDir, "watch_media").apply { mkdirs() }

    fun fileFor(context: Context, mediaId: Long): File = File(directory(context), "$mediaId.audio")

    fun has(context: Context, media: PlusWear.WatchMedia): Boolean {
        val target = fileFor(context, media.mediaId)
        return target.isFile && target.length() > 0 && (media.size <= 0 || target.length() == media.size)
    }

    suspend fun fetch(
        context: Context,
        media: PlusWear.WatchMedia,
        onProgress: (Float, Int) -> Unit
    ): File? = withContext(Dispatchers.IO) {
        val target = fileFor(context, media.mediaId)
        if (has(context, media)) {
            target.setLastModified(System.currentTimeMillis())
            return@withContext target
        }
        val part = File(target.path + ".part")
        val pinger = launch {
            while (isActive) {
                WearMessageSender.sendToPhone(context, PlusWear.WATCH_PING)
                delay(PING_MILLIS)
            }
        }
        try {
            var done = false
            val wifi = acquireWifi(context)
            if (wifi != null) {
                try {
                    if (media.hasFile && media.port > 0 && media.host.isNotEmpty()) {
                        done = attempt { copyFromPhone(wifi.network, media, part, onProgress) }
                    }
                    if (!done && media.url.startsWith("http")) {
                        done = attempt { download(wifi.network, media, part, onProgress) }
                    }
                } finally {
                    wifi.release()
                }
            }
            if (!done && media.hasFile) {
                done = attempt { copyOverBluetooth(context, media, part, onProgress) }
            }
            if (done && part.renameTo(target)) {
                trim(context, target)
                target
            } else {
                part.delete()
                null
            }
        } catch (e: CancellationException) {
            part.delete()
            throw e
        } finally {
            pinger.cancel()
        }
    }

    private suspend fun attempt(block: suspend () -> Boolean): Boolean {
        return try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: IOException) {
            Log.w(TAG, "Getting the episode this way failed", e)
            false
        } catch (e: RuntimeException) {
            Log.w(TAG, "Getting the episode this way failed", e)
            false
        }
    }

    private suspend fun acquireWifi(context: Context): WifiHold? {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val found = CompletableDeferred<Network?>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                found.complete(network)
            }

            override fun onUnavailable() {
                found.complete(null)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            manager.requestNetwork(request, callback, WIFI_WAIT_MILLIS)
        } catch (e: RuntimeException) {
            Log.w(TAG, "Could not ask for Wi-Fi", e)
            return null
        }
        val network = withTimeoutOrNull(WIFI_WAIT_MILLIS + 1_000L) { found.await() }
        if (network == null) {
            try {
                manager.unregisterNetworkCallback(callback)
            } catch (e: RuntimeException) {
                Log.d(TAG, "Wi-Fi request already released", e)
            }
            return null
        }
        return WifiHold(manager, callback, network)
    }

    private suspend fun copy(
        input: InputStream,
        part: File,
        size: Long,
        source: Int,
        onProgress: (Float, Int) -> Unit
    ): Boolean {
        FileOutputStream(part).use { out ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count < 0) {
                    break
                }
                out.write(buffer, 0, count)
                total += count
                onProgress(if (size > 0) (total.toFloat() / size).coerceIn(0f, 1f) else -1f, source)
            }
        }
        return part.length() > 0
    }

    private suspend fun copyFromPhone(
        network: Network,
        media: PlusWear.WatchMedia,
        part: File,
        onProgress: (Float, Int) -> Unit
    ): Boolean {
        val socket = network.socketFactory.createSocket()
        try {
            socket.connect(InetSocketAddress(media.host, media.port), 6_000)
            socket.soTimeout = 20_000
            val out = socket.getOutputStream()
            out.write((media.token + "\n").toByteArray())
            out.flush()
            val copied = copy(socket.getInputStream(), part, media.size, SOURCE_PHONE_WIFI, onProgress)
            return copied && (media.size <= 0 || part.length() == media.size)
        } finally {
            socket.close()
        }
    }

    private suspend fun download(
        network: Network,
        media: PlusWear.WatchMedia,
        part: File,
        onProgress: (Float, Int) -> Unit
    ): Boolean {
        var url = URL(media.url)
        for (redirect in 0 until MAX_REDIRECTS) {
            val connection = network.openConnection(url) as HttpURLConnection
            try {
                connection.connectTimeout = 15_000
                connection.readTimeout = 30_000
                connection.instanceFollowRedirects = false
                connection.setRequestProperty("User-Agent", "AntennaPod+ Wear")
                val code = connection.responseCode
                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return false
                    url = URL(url, location)
                    continue
                }
                if (code != HttpURLConnection.HTTP_OK) {
                    return false
                }
                val length = connection.contentLengthLong
                val copied = connection.inputStream.use {
                    copy(it, part, if (length > 0) length else media.size, SOURCE_WIFI, onProgress)
                }
                return copied && (length <= 0 || part.length() == length)
            } finally {
                connection.disconnect()
            }
        }
        return false
    }

    private suspend fun copyOverBluetooth(
        context: Context,
        media: PlusWear.WatchMedia,
        part: File,
        onProgress: (Float, Int) -> Unit
    ): Boolean {
        val node = Wearable.getNodeClient(context).connectedNodes.await().firstOrNull() ?: return false
        val client = Wearable.getChannelClient(context)
        val channel = client.openChannel(node.id, PlusWear.watchFileChannelPath(media.mediaId)).await()
        try {
            val copied = client.getInputStream(channel).await().use {
                copy(it, part, media.size, SOURCE_BLUETOOTH, onProgress)
            }
            return copied && part.length() == media.size
        } finally {
            client.close(channel)
        }
    }

    private fun trim(context: Context, keep: File) {
        val files = directory(context).listFiles() ?: return
        files.filter { it != keep }
            .sortedByDescending { it.lastModified() }
            .drop(KEEP_FILES - 1)
            .forEach { it.delete() }
    }
}
