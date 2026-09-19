package eu.kanade.tachiyomi.data.tsuzuki.drivesync

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkerParameters
import dev.zacsweers.metro.Inject
import mihon.app.di.AppGraph
import mihon.core.metro.metroGraph
import tachiyomi.domain.tsuzuki.sync.model.SyncDocumentResult
import tachiyomi.domain.tsuzuki.sync.model.SyncFailureReason
import tachiyomi.domain.tsuzuki.sync.service.SyncTrigger
import java.util.concurrent.TimeUnit

class DriveSyncJob(
    private val context: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(context, workerParams) {

    private val graph: AppGraph = context.metroGraph()

    @Inject
    private lateinit var runtime: DriveSyncRuntime

    override suspend fun doWork(): Result {
        graph.inject(this)

        val report = try {
            runtime.run(SyncTrigger.BACKGROUND)
        } catch (_: Throwable) {
            return Result.retry()
        }

        val failures = buildList {
            report.globalFailure?.let(::add)
            report.documentResults
                .filterIsInstance<SyncDocumentResult.Failed>()
                .mapTo(this) { it.failure }
        }

        return if (failures.any { it.reason.isWorkManagerRetryable() }) {
            Result.retry()
        } else {
            Result.success()
        }
    }

    companion object {
        private const val WORK_NAME = "TsuzukiDriveSync-periodic"

        fun setupTask(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<DriveSyncJob>(
                6,
                TimeUnit.HOURS,
                30,
                TimeUnit.MINUTES,
            )
                .setConstraints(constraints)
                .setBackoffCriteria(
                    BackoffPolicy.EXPONENTIAL,
                    15,
                    TimeUnit.MINUTES,
                )
                .build()

            androidx.work.WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(
                    WORK_NAME,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    request,
                )
        }
    }
}

private fun SyncFailureReason.isWorkManagerRetryable(): Boolean {
    return this == SyncFailureReason.NETWORK_UNAVAILABLE ||
        this == SyncFailureReason.LOCAL_STATE_UNAVAILABLE ||
        this == SyncFailureReason.REMOTE_UNAVAILABLE ||
        this == SyncFailureReason.REMOTE_NOT_FOUND ||
        this == SyncFailureReason.REMOTE_CHANGED ||
        this == SyncFailureReason.RATE_LIMITED ||
        this == SyncFailureReason.UNKNOWN
}
