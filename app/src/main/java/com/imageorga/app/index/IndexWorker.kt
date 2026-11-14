package com.imageorga.app.index

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imageorga.app.data.Db
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.Settings
import com.imageorga.app.ml.Embedder
import com.imageorga.app.ml.ModelMissingException

/**
 * Finds photos that have no vector yet, embeds them one by one and sorts them into
 * categories. Runs as a foreground job with a progress notification where the system
 * allows it, and as ordinary background work otherwise.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        // The manual, periodic and photo-change jobs are separate; only one may index at a time.
        if (!running.tryLock()) return Result.success()
        try {
            return index()
        } finally {
            running.unlock()
        }
    }

    private suspend fun index(): Result {
        val ctx = applicationContext
        Settings.init(ctx)
        Notifications.createChannel(ctx)

        if (!Permissions.hasPhotoAccess(ctx)) {
            Progress.index.value = IndexProgress(problem = "Photo access has not been granted.")
            return Result.success()
        }

        val db = Db.get(ctx)
        val sync = db.syncMedia(MediaScanner.scan(ctx))
        if (sync.removed > 0 || sync.changed > 0) VectorIndex.invalidate()

        var cfg = Settings.current
        if (!ModelCatalog.resolve(cfg).isReady(ctx)) {
            Progress.index.value = IndexProgress(problem = "The model has not been downloaded yet.")
            return Result.success()
        }

        var pending = db.pendingIds(cfg.signature)
        if (pending.isEmpty()) {
            sortQuietly()
            Progress.index.value = IndexProgress()
            return Result.success()
        }

        val total = pending.size
        var done = 0
        var indexed = 0
        var problem: String? = null
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        fun notification() = Notifications.progress(
            ctx, "Indexing photos", "$done of $total", done, total, cancel
        )

        Embedder.indexing = true
        Progress.index.value = IndexProgress(running = true, done = 0, total = total)
        try {
            // Starting a foreground job is refused when the app is in the background on
            // newer Android versions. The work then simply continues as a background job.
            try {
                setForeground(Notifications.foreground(Notifications.ID_INDEX, notification()))
            } catch (t: Throwable) {
                Log.i(TAG, "running without foreground service: ${t.message}")
                Notifications.post(ctx, Notifications.ID_INDEX, notification())
            }

            val maxSide = if (cfg.visionTokens > 70) 896 else 640
            var lastUi = 0L
            var sinceSort = 0
            var consecutiveFailures = 0
            var i = 0
            while (i < pending.size && !isStopped) {
                val imageId = pending[i]
                i++
                try {
                    val bytes = MediaScanner.loadForModel(ctx, Db.imageUri(imageId), maxSide)
                    val vector = Embedder.embedImage(ctx, bytes)
                    val now = Settings.current
                    if (now.signature != cfg.signature) {
                        // The accelerator failed and the app fell back to another model file.
                        // Vectors of different files are not mixed, so start over with it.
                        cfg = now
                        pending = db.pendingIds(cfg.signature)
                        i = 0
                        if (!ModelCatalog.resolve(cfg).isReady(ctx)) throw ModelMissingException()
                        continue
                    }
                    db.saveEmbedding(imageId, cfg.signature, vector)
                    VectorIndex.add(ctx, cfg, imageId, vector)
                    indexed++
                    sinceSort++
                    consecutiveFailures = 0
                } catch (e: ModelMissingException) {
                    problem = "The model for the current settings has not been downloaded yet."
                    break
                } catch (t: Throwable) {
                    Log.w(TAG, "could not index image $imageId", t)
                    db.markFailed(imageId)
                    consecutiveFailures++
                    if (consecutiveFailures >= 5) {
                        // Five in a row is the model failing, not five broken photos.
                        problem = "Indexing stopped: ${t.message?.take(160) ?: t.javaClass.simpleName}"
                        break
                    }
                }
                done = minOf(done + 1, total)

                val t = SystemClock.elapsedRealtime()
                if (t - lastUi > 700) {
                    lastUi = t
                    Progress.index.value = IndexProgress(running = true, done = done, total = total)
                    Notifications.post(ctx, Notifications.ID_INDEX, notification())
                }
                if (sinceSort >= 40) {
                    // Sort as we go so categories fill up while indexing is still running.
                    sinceSort = 0
                    sortQuietly()
                    Db.bump()
                }
            }
        } finally {
            Embedder.indexing = false
            sortQuietly()
            Db.bump()
            Notifications.cancel(ctx, Notifications.ID_INDEX)
            Progress.index.value = IndexProgress(problem = problem)
            Embedder.releaseIfIdle()
        }

        if (indexed > 0 && !Embedder.uiVisible) {
            Notifications.post(
                ctx, Notifications.ID_RESULT,
                Notifications.result(ctx, "Photos indexed", "$indexed new ${if (indexed == 1) "photo" else "photos"} sorted and searchable."),
            )
        }
        return Result.success()
    }

    private suspend fun sortQuietly() {
        try {
            Categorizer.run(applicationContext)
        } catch (t: Throwable) {
            Log.w(TAG, "sorting failed", t)
        }
    }

    private companion object {
        const val TAG = "IndexWorker"
        val running = kotlinx.coroutines.sync.Mutex()
    }
}
