package de.danoeh.antennapod.wearos.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import de.danoeh.antennapod.net.sync.wearinterface.PlusWear
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeoutOrNull

class WatchDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        WatchDownloads.sync(applicationContext)
        return Result.success()
    }
}

object WatchDownloads {
    private const val WORK = "plus_watch_downloads"
    private const val PERIODIC_WORK = "plus_watch_downloads_periodic"
    private const val PLANS_WAIT_MILLIS = 30_000L
    private const val OFFER_WAIT_MILLIS = 10_000L
    private const val MAX_FAILURES = 3

    @Volatile
    private var plansReply: CompletableDeferred<Boolean>? = null

    @Volatile
    private var offerReply: CompletableDeferred<PlusWear.WatchMedia?>? = null
    private val running = Mutex()

    private val _changed = MutableStateFlow(0)
    val changed: StateFlow<Int> = _changed

    fun handleReply(context: Context, path: String, data: ByteArray): Boolean {
        when (path) {
            PlusWear.WATCH_PLAN -> WatchStore.savePlan(context, data)
            PlusWear.WATCH_PLANS_DONE -> {
                WatchStore.plansDone(context, data)
                plansReply?.complete(true)
                _changed.update { it + 1 }
            }
            PlusWear.WATCH_OFFER -> offerReply?.complete(PlusWear.watchMediaFromBytes(data))
            PlusWear.WATCH_RECORD_DONE -> WatchStore.clearRecord(context, WatchPlayback.recordSentAt)
            else -> return false
        }
        return true
    }

    private fun constraints(): Constraints = Constraints.Builder().setRequiresBatteryNotLow(true).build()

    fun schedule(context: Context) {
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            PERIODIC_WORK,
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<WatchDownloadWorker>(1, TimeUnit.HOURS).setConstraints(constraints()).build()
        )
    }

    fun request(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            WORK,
            ExistingWorkPolicy.APPEND_OR_REPLACE,
            OneTimeWorkRequestBuilder<WatchDownloadWorker>().setConstraints(constraints()).build()
        )
    }

    suspend fun refreshPlans(context: Context): Boolean {
        val reply = CompletableDeferred<Boolean>()
        plansReply = reply
        if (!WearMessageSender.sendToPhone(context, PlusWear.WATCH_PLANS)) {
            return false
        }
        return withTimeoutOrNull(PLANS_WAIT_MILLIS) { reply.await() } ?: false
    }

    private suspend fun requestOffer(context: Context, itemId: Long): PlusWear.WatchMedia? {
        val reply = CompletableDeferred<PlusWear.WatchMedia?>()
        offerReply = reply
        if (!WearMessageSender.sendToPhone(context, PlusWear.watchOfferPath(itemId))) {
            return null
        }
        return withTimeoutOrNull(OFFER_WAIT_MILLIS) { reply.await() }
    }

    private fun cleanup(context: Context, wantedOnly: Boolean) {
        val keep = HashSet<Long>()
        for (plan in WatchStore.allPlans(context)) {
            val minutes = WatchStore.amount(context, plan.id)
            val items = if (wantedOnly) WatchStore.wanted(plan, minutes) else {
                if (minutes == WatchStore.AMOUNT_OFF) emptyList() else plan.items
            }
            items.forEach { keep.add(it.mediaId) }
        }
        keep.addAll(WatchPlayback.protectedMediaIds())
        WatchFiles.deleteExcept(context, keep)
    }

    suspend fun sync(context: Context) {
        if (!running.tryLock()) {
            return
        }
        try {
            val nearby = WearMessageSender.isPhoneNearby(context)
            if (nearby) {
                refreshPlans(context)
            }
            cleanup(context, false)
            _changed.update { it + 1 }
            var failures = 0
            for (plan in WatchStore.allPlans(context)) {
                for (media in WatchStore.wanted(plan, WatchStore.amount(context, plan.id))) {
                    if (WatchFiles.has(context, media)) {
                        continue
                    }
                    if (WatchFiles.freeBytes(context) < WatchFiles.MIN_FREE_BYTES) {
                        cleanup(context, true)
                        if (WatchFiles.freeBytes(context) < WatchFiles.MIN_FREE_BYTES) {
                            return
                        }
                    }
                    if (WatchPlayback.moving.value != null) {
                        return
                    }
                    val source = (if (nearby) requestOffer(context, media.itemId) else null) ?: media
                    val file = coroutineScope {
                        val job = async {
                            WatchFiles.fetch(context, source, WatchStore.useLte(context), false) { _, _ -> }
                        }
                        WatchFiles.background = job
                        try {
                            job.await()
                        } catch (e: CancellationException) {
                            currentCoroutineContext().ensureActive()
                            return@coroutineScope null
                        } finally {
                            WatchFiles.background = null
                        }
                    }
                    _changed.update { it + 1 }
                    if (file == null) {
                        failures++
                        if (failures >= MAX_FAILURES) {
                            return
                        }
                    }
                }
            }
        } finally {
            running.unlock()
        }
    }
}
