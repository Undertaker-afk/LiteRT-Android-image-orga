package com.imageorga.app.index

import android.content.Context
import android.provider.MediaStore
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imageorga.app.data.Settings
import java.util.concurrent.TimeUnit

/** Decides when indexing and downloads run. */
object Scheduler {
    private const val INDEX = "index"
    private const val DOWNLOAD = "download"
    private const val PERIODIC = "index-periodic"
    private const val TRIGGER = "media-trigger"

    /** Indexes right away (keeps an already running job). */
    fun indexNow(context: Context) {
        WorkManager.getInstance(context).enqueueUniqueWork(
            INDEX, ExistingWorkPolicy.KEEP, OneTimeWorkRequestBuilder<IndexWorker>().build()
        )
    }

    /** Indexes in the background, respecting the battery settings. */
    fun indexInBackground(context: Context) {
        val cfg = Settings.current
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(cfg.chargingOnly)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            INDEX, ExistingWorkPolicy.KEEP,
            OneTimeWorkRequestBuilder<IndexWorker>().setConstraints(constraints).build()
        )
    }

    fun cancelIndex(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(INDEX)
    }

    /** Downloads the model file the current settings call for. */
    fun download(context: Context) {
        val cfg = Settings.current
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(if (cfg.wifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED)
            .build()
        Progress.download.value = DownloadProgress(running = true)
        WorkManager.getInstance(context).enqueueUniqueWork(
            DOWNLOAD, ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<DownloadWorker>().setConstraints(constraints).build()
        )
    }

    fun cancelDownload(context: Context) {
        WorkManager.getInstance(context).cancelUniqueWork(DOWNLOAD)
        Progress.download.value = DownloadProgress()
    }

    /**
     * Applies the "index new photos automatically" setting: a job that fires whenever the
     * photo library changes, plus a periodic sweep as a safety net.
     */
    fun applyAutoIndex(context: Context) {
        val wm = WorkManager.getInstance(context)
        val cfg = Settings.current
        if (!cfg.autoIndex) {
            wm.cancelUniqueWork(TRIGGER)
            wm.cancelUniqueWork(PERIODIC)
            return
        }
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .setRequiresCharging(cfg.chargingOnly)
            .build()
        wm.enqueueUniquePeriodicWork(
            PERIODIC, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<IndexWorker>(6, TimeUnit.HOURS).setConstraints(constraints).build()
        )
        armMediaTrigger(context, ExistingWorkPolicy.KEEP)
    }

    internal fun armMediaTrigger(context: Context, policy: ExistingWorkPolicy) {
        val constraints = Constraints.Builder()
            .addContentUriTrigger(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true)
            // Wait until a burst of new photos has settled before waking up.
            .setTriggerContentUpdateDelay(10, TimeUnit.SECONDS)
            .setTriggerContentMaxDelay(60, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(
            TRIGGER, policy,
            OneTimeWorkRequestBuilder<MediaTriggerWorker>().setConstraints(constraints).build()
        )
    }
}

/**
 * Fires when the photo library changes. A content trigger only fires once, so this worker
 * starts indexing and then arms the trigger again for the next change.
 */
class MediaTriggerWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx)
        if (Settings.current.autoIndex) {
            Scheduler.indexInBackground(ctx)
            Scheduler.armMediaTrigger(ctx, ExistingWorkPolicy.APPEND_OR_REPLACE)
        }
        return Result.success()
    }
}
