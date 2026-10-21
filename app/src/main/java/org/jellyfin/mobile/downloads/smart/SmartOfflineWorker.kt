package org.jellyfin.mobile.downloads.smart

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import org.jellyfin.mobile.R
import org.jellyfin.mobile.downloads.DownloadNotificationManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.concurrent.TimeUnit

class SmartOfflineWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(
        context,
        parameters
    ),
    KoinComponent {
    private val manager by inject<SmartOfflineManager>()
    private val dao by inject<SmartOfflineDao>()
    private val notifications by inject<DownloadNotificationManager>()

    override suspend fun getForegroundInfo(): ForegroundInfo {
        // Initialize the shared channel, but use a distinct notification from manual downloads.
        notifications.createForegroundInfo()
        val cancel = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        val notification = NotificationCompat.Builder(applicationContext, DownloadNotificationManager.CHANNEL_ID)
            .setContentTitle(applicationContext.getString(R.string.smart_offline_title))
            .setSmallIcon(android.R.drawable.stat_sys_download).setOngoing(true)
            .addAction(0, applicationContext.getString(R.string.download_cancel), cancel).build()
        return ForegroundInfo(
            NOTIFICATION_ID_BASE + (id.hashCode() and NOTIFICATION_ID_MASK),
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC else 0
        )
    }

    override suspend fun doWork(): Result {
        val key = inputData.getString("account") ?: return Result.failure()
        return try {
            setForeground(getForegroundInfo())
            withTimeout(TimeUnit.MINUTES.toMillis(MAX_PASS_MINUTES)) { manager.reconcile(key) }
            Result.success()
        } catch (_: TimeoutCancellationException) {
            Result.retry()
        } catch (error: CancellationException) {
            throw error
        } catch (_: StorageBudgetExceeded) {
            dao.status(key, "Storage budget reached; free space or raise the cap")
            Result.success()
        } catch (error: DownloadRejected) {
            dao.status(key, "Download rejected (${error.status}); check server access")
            if (SmartOfflinePolicy.retryable(error.status)) Result.retry() else Result.success()
        } catch (_: Exception) {
            // Avoid persisting exception messages: HTTP failures can contain tokens or private URLs.
            dao.status(key, "Waiting for server, permitted network or valid endpoint; will retry")
            Result.retry()
        }
    }

    companion object {
        private const val NOTIFICATION_ID_BASE = 1000
        private const val NOTIFICATION_ID_MASK = 0x3fffffff
        private const val MAX_PASS_MINUTES = 8L

        fun schedule(context: Context, account: SmartOfflineAccount) {
            val work = WorkManager.getInstance(context)
            val name = "smart-offline-${account.key}"
            if (!account.enabled) {
                work.cancelUniqueWork(name)
                work.cancelUniqueWork("$name-now")
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(
                if (account.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED,
            ).setRequiresStorageNotLow(true).build()
            val data = workDataOf("account" to account.key)
            work.enqueueUniquePeriodicWork(
                name,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SmartOfflineWorker>(1, TimeUnit.HOURS).setConstraints(constraints)
                    .setInputData(data).setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
            )
            work.enqueueUniqueWork(
                "$name-now",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<SmartOfflineWorker>().setConstraints(constraints).setInputData(data)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
            )
        }
    }
}

class OfflineProgressWorker(context: Context, parameters: WorkerParameters) :
    CoroutineWorker(
        context,
        parameters
    ),
    KoinComponent {
    private val manager by inject<SmartOfflineManager>()
    private val dao by inject<SmartOfflineDao>()

    override suspend fun doWork(): Result = try {
        manager.syncProgress()
        dao.accounts().filter { it.enabled }.forEach { SmartOfflineWorker.schedule(applicationContext, it) }
        Result.success()
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        Result.retry()
    }

    companion object {
        private val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                "offline-progress-now",
                ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<OfflineProgressWorker>().setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 1, TimeUnit.MINUTES).build()
            )
        }

        fun recover(context: Context) {
            WorkManager.getInstance(
                context
            ).enqueueUniquePeriodicWork(
                "offline-progress",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<OfflineProgressWorker>(1, TimeUnit.HOURS).setConstraints(constraints).build()
            )
            enqueue(context)
        }
    }
}
