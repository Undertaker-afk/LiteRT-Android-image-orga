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
import com.imageorga.app.ml.Llm
import com.imageorga.app.ml.ModelMissingException
import kotlinx.coroutines.sync.Mutex

/**
 * The background job. It runs in two phases:
 *
 *  1. Find photos that have no vector yet, embed them one by one and sort them.
 *  2. If smart sorting is on, let the chat model look at the photos that are candidates
 *     for a category and confirm or reject them.
 *
 * It runs as a foreground job with a progress notification where the system allows it,
 * and as ordinary background work otherwise.
 */
class IndexWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private var inForeground = false

    override suspend fun doWork(): Result {
        // The manual, periodic and photo-change jobs are separate; only one may run at a time.
        if (!running.tryLock()) return Result.success()
        try {
            return work()
        } finally {
            running.unlock()
        }
    }

    private suspend fun work(): Result {
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

        if (!ModelCatalog.resolve(Settings.current).isReady(ctx)) {
            Progress.index.value = IndexProgress(problem = "The model has not been downloaded yet.")
            return Result.success()
        }

        var indexed = 0
        var problem: String? = null
        try {
            val embedded = embedPhotos()
            indexed = embedded.first
            problem = embedded.second
            sortQuietly()
            Db.bump()
            if (problem == null && !isStopped) problem = checkPhotos()
        } finally {
            Embedder.indexing = false
            Llm.busy = false
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

    /** Phase 1. Returns how many photos were embedded and, if it had to stop, why. */
    private suspend fun embedPhotos(): Pair<Int, String?> {
        val ctx = applicationContext
        val db = Db.get(ctx)
        var cfg = Settings.current
        var pending = db.pendingIds(cfg.signature)
        if (pending.isEmpty()) return 0 to null

        val total = pending.size
        var done = 0
        var indexed = 0
        var problem: String? = null
        Embedder.indexing = true
        show("Indexing photos", 0, total)

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
                show("Indexing photos", done, total)
            }
            if (sinceSort >= 40) {
                // Sort as we go so categories fill up while indexing is still running.
                sinceSort = 0
                sortQuietly()
                Db.bump()
            }
        }
        Embedder.indexing = false
        return indexed to problem
    }

    /** Phase 2. Returns why it had to stop, if it did. */
    private suspend fun checkPhotos(): String? {
        val ctx = applicationContext
        val cfg = Settings.current
        if (!cfg.llmEnabled || !cfg.llmVerify) return null
        if (!Llm.isReady(ctx, cfg)) return null

        val plan = try {
            Categorizer.plan(ctx)
        } catch (t: Throwable) {
            Log.w(TAG, "could not prepare photo checks", t)
            return null
        }
        val manual = Db.get(ctx).manualIds()
        val rows = SmartSorter.pendingRows(plan).filter { plan.candidates.ids[it] !in manual }.toIntArray()
        if (rows.isEmpty()) return null

        // The two models together need a lot of memory; the embedding model is done for now.
        if (!Embedder.uiVisible) Embedder.release()
        Llm.busy = true
        val title = "Checking photos with the chat model"
        show(title, 0, rows.size)
        var lastUi = 0L
        var sinceApply = 0
        val problem = SmartSorter.run(ctx, plan, rows, isStopped = { isStopped }) { done ->
            val t = SystemClock.elapsedRealtime()
            if (t - lastUi > 700) {
                lastUi = t
                show(title, done, rows.size)
            }
            if (++sinceApply >= 15) {
                sinceApply = 0
                Categorizer.apply(ctx, plan)
            }
        }
        Categorizer.apply(ctx, plan)
        Llm.busy = false
        Llm.release()
        return problem
    }

    /** Updates the notification and the in-app progress bar. */
    private suspend fun show(title: String, done: Int, total: Int) {
        val ctx = applicationContext
        Progress.index.value = IndexProgress(running = true, done = done, total = total, title = title)
        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        val notification = Notifications.progress(ctx, title, "$done of $total", done, total, cancel)
        if (!inForeground) {
            inForeground = true
            // Starting a foreground job is refused when the app is in the background on
            // newer Android versions. The work then simply continues as a background job.
            try {
                setForeground(Notifications.foreground(Notifications.ID_INDEX, notification))
                return
            } catch (t: Throwable) {
                Log.i(TAG, "running without foreground service: ${t.message}")
            }
        }
        Notifications.post(ctx, Notifications.ID_INDEX, notification)
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
        val running = Mutex()
    }
}
