package com.imageorga.app.index

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.ModelFile
import com.imageorga.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URL

/**
 * Downloads the model file the current settings call for, with resume support and a
 * progress notification. When NPU is selected it fetches the build compiled for this
 * device's chip; if no such build is published it switches to CPU and fetches the
 * generic build instead.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private class NotPublished : Exception()

    override suspend fun doWork(): Result {
        val ctx = applicationContext
        Settings.init(ctx)
        Notifications.createChannel(ctx)

        var file = ModelCatalog.resolve(Settings.current)
        if (file.isReady(ctx)) return Result.success()

        try {
            try {
                download(file)
            } catch (e: NotPublished) {
                val npu = file.npu ?: throw IOException("Model file not found on the server.")
                Settings.fallBackToCpu("No NPU build of this model is published for ${npu.label}, so the app uses CPU.")
                file = ModelCatalog.resolve(Settings.current)
                if (!file.isReady(ctx)) download(file)
            }
        } catch (e: NotPublished) {
            return fail("Model file not found on the server.")
        } catch (e: IOException) {
            Log.w(TAG, "download interrupted", e)
            Progress.download.value = Progress.download.value.copy(running = false, error = "Download interrupted: ${e.message}. It resumes automatically.")
            Notifications.cancel(ctx, Notifications.ID_DOWNLOAD)
            return if (runAttemptCount < 5) Result.retry() else Result.failure()
        } catch (t: Throwable) {
            Log.w(TAG, "download failed", t)
            return fail(t.message ?: t.javaClass.simpleName)
        }

        Notifications.cancel(ctx, Notifications.ID_DOWNLOAD)
        if (isStopped) {
            Progress.download.value = DownloadProgress()
            return Result.success()
        }
        Progress.download.value = DownloadProgress()
        Notifications.post(ctx, Notifications.ID_RESULT, Notifications.result(ctx, "Model ready", "Indexing your photos now."))
        Scheduler.indexNow(ctx)
        return Result.success()
    }

    private fun fail(message: String): Result {
        Progress.download.value = DownloadProgress(error = message)
        Notifications.cancel(applicationContext, Notifications.ID_DOWNLOAD)
        return Result.failure()
    }

    private suspend fun download(file: ModelFile) = withContext(Dispatchers.IO) {
        val ctx = applicationContext
        val part = file.partFile(ctx)
        var offset = if (part.exists()) part.length() else 0L

        var conn = open(file.url, offset)
        var code = conn.responseCode
        if (code == 416) {
            // The partial file is not a valid prefix (e.g. the server copy changed): restart.
            conn.disconnect()
            part.delete()
            offset = 0
            conn = open(file.url, 0)
            code = conn.responseCode
        }
        if (code == 404) {
            conn.disconnect()
            throw NotPublished()
        }
        if (code != 200 && code != 206) {
            conn.disconnect()
            throw IOException("Server answered $code")
        }
        if (code == 200) offset = 0 // server ignored the range request

        val remaining = conn.contentLengthLong
        val total = if (remaining > 0) offset + remaining else 0L
        if (total > 0 && ctx.filesDir.usableSpace < remaining + 64L * 1024 * 1024) {
            conn.disconnect()
            throw IllegalStateException("Not enough free storage: ${remaining / 1_000_000} MB needed.")
        }

        val cancel = WorkManager.getInstance(ctx).createCancelPendingIntent(id)
        fun notification(bytes: Long) = Notifications.progress(
            ctx, "Downloading model",
            if (total > 0) "${bytes / 1_000_000} of ${total / 1_000_000} MB" else "${bytes / 1_000_000} MB",
            if (total > 0) (bytes * 1000 / total).toInt() else 0,
            if (total > 0) 1000 else 0,
            cancel,
        )
        Progress.download.value = DownloadProgress(true, file.fileName, offset, total)
        try {
            setForeground(Notifications.foreground(Notifications.ID_DOWNLOAD, notification(offset)))
        } catch (t: Throwable) {
            Notifications.post(ctx, Notifications.ID_DOWNLOAD, notification(offset))
        }

        try {
            RandomAccessFile(part, "rw").use { out ->
                out.setLength(offset)
                out.seek(offset)
                conn.inputStream.use { input ->
                    val buf = ByteArray(256 * 1024)
                    var bytes = offset
                    var lastUi = 0L
                    while (!isStopped) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        bytes += n
                        val t = SystemClock.elapsedRealtime()
                        if (t - lastUi > 500) {
                            lastUi = t
                            Progress.download.value = DownloadProgress(true, file.fileName, bytes, total)
                            Notifications.post(ctx, Notifications.ID_DOWNLOAD, notification(bytes))
                        }
                    }
                }
            }
        } finally {
            conn.disconnect()
        }

        if (isStopped) return@withContext // keep the partial file so the download can resume
        if (total > 0 && part.length() != total) throw IOException("connection closed early")
        val target = file.file(ctx)
        target.delete()
        if (!part.renameTo(target)) throw IOException("could not store the model file")
        file.markReady(ctx)
    }

    private fun open(url: String, offset: Long): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 30_000
        conn.readTimeout = 60_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", "ImageOrga-Android")
        // Ask for the raw bytes so the reported length matches the file size.
        conn.setRequestProperty("Accept-Encoding", "identity")
        if (offset > 0) conn.setRequestProperty("Range", "bytes=$offset-")
        return conn
    }

    private companion object {
        const val TAG = "DownloadWorker"
    }
}
