package com.imageorga.app.ml

import android.content.Context
import android.system.Os
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.EmbeddingEngine
import com.google.ai.edge.litertlm.EmbeddingEngineConfig
import com.google.ai.edge.litertlm.EmbeddingOptions
import com.google.ai.edge.litertlm.InputData
import com.imageorga.app.data.Config
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/** The model file for the current settings has not been downloaded yet. */
class ModelMissingException : Exception("The model has not been downloaded yet.")

/**
 * Owns the single on-device embedding engine. All calls are serialised, because the
 * engine holds several hundred MB of memory and is not meant to run requests in parallel.
 */
object Embedder {
    private const val TAG = "Embedder"

    /** EmbeddingGemma 2 is trained with this instruction in front of search queries. */
    private const val QUERY_PREFIX = "task: search result | query: "

    private val mutex = Mutex()
    private var engine: EmbeddingEngine? = null
    private var engineKey: String? = null

    /** Set while the main screen is visible, so the engine stays loaded for instant search. */
    @Volatile
    var uiVisible = false

    /** Set while background indexing runs. */
    @Volatile
    var indexing = false

    suspend fun embedQuery(context: Context, text: String): FloatArray =
        embedText(context, QUERY_PREFIX + text.trim())

    private suspend fun embedText(context: Context, text: String): FloatArray = run(context) { e, opts ->
        try {
            e.computeEmbedding(listOf(InputData.Text(text.take(MAX_TEXT_CHARS))), opts).embedding
        } catch (t: Throwable) {
            // Most likely more tokens than the loaded text signature accepts: retry shorter.
            if (text.length <= SHORT_TEXT_CHARS) throw t
            e.computeEmbedding(listOf(InputData.Text(text.take(SHORT_TEXT_CHARS))), opts).embedding
        }
    }

    /** [imageBytes] must be an encoded JPEG or PNG. */
    suspend fun embedImage(context: Context, imageBytes: ByteArray): FloatArray = run(context) { e, opts ->
        e.computeEmbedding(listOf(InputData.Image(imageBytes)), opts).embedding
    }

    /** Frees the engine's memory unless something still needs it. */
    suspend fun releaseIfIdle() {
        if (uiVisible || indexing) return
        mutex.withLock { closeLocked() }
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private fun closeLocked() {
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        engine = null
        engineKey = null
    }

    private suspend fun <T> run(context: Context, block: (EmbeddingEngine, EmbeddingOptions) -> T): T =
        mutex.withLock {
            withContext(Dispatchers.Default) {
                val app = context.applicationContext
                val e = ensureLocked(app)
                // Read the settings again: starting the engine may have fallen back to CPU.
                val cfg = Settings.current
                block(e, EmbeddingOptions(true, null, cfg.dim, cfg.visionTokens))
            }
        }

    private fun ensureLocked(context: Context): EmbeddingEngine {
        var cfg = Settings.current
        var key = keyOf(cfg)
        engine?.let { if (engineKey == key) return it }
        closeLocked()

        if (cfg.backend != Config.BACKEND_CPU) {
            val started = startAccelerated(context, cfg)
            if (started != null) {
                engine = started
                engineKey = key
                return started
            }
            // startAccelerated switched the settings to CPU.
            cfg = Settings.current
            key = keyOf(cfg)
        }

        val e = create(context, cfg, Backend.CPU(), Backend.CPU())
        engine = e
        engineKey = key
        return e
    }

    /** Starts the engine on NPU or GPU; on failure switches settings to CPU and returns null. */
    private fun startAccelerated(context: Context, cfg: Config): EmbeddingEngine? {
        val file = ModelCatalog.resolve(cfg)
        if (!file.isReady(context)) throw ModelMissingException()
        val name = cfg.backend.uppercase()
        // If the vendor driver crashes the process, the next launch sees this marker and
        // stays on CPU instead of crashing again.
        Settings.markAccelStarting(cfg.backend)
        val notes = ArrayList<String>()
        val failures = ArrayList<Pair<String, Throwable>>()
        val attempts: List<Pair<Backend, Backend>> = if (cfg.backend == Config.BACKEND_NPU) {
            val libDir = try {
                prepareNpuLibraryDir(context, notes)
            } catch (t: Throwable) {
                failures.add("preparing NPU libraries" to t)
                null
            }
            if (libDir == null) {
                emptyList()
            } else {
                // Qualcomm's runtime finds its DSP-side libraries through this variable.
                try {
                    Os.setenv("ADSP_LIBRARY_PATH", context.applicationInfo.nativeLibraryDir, true)
                } catch (t: Throwable) {
                    Log.w(TAG, "setenv failed", t)
                }
                listOf(
                    Backend.NPU(libDir) to Backend.NPU(libDir),
                    // Some chip builds keep the text part on CPU and only run vision on the NPU.
                    Backend.CPU() to Backend.NPU(libDir),
                )
            }
        } else {
            listOf(Backend.GPU() to Backend.GPU())
        }
        for ((text, vision) in attempts) {
            try {
                val e = create(context, cfg, text, vision)
                Settings.markAccelStarted()
                Settings.clearAccelNote()
                Diagnostics.clear(context)
                return e
            } catch (t: Throwable) {
                if (t is ModelMissingException) throw t
                Log.w(TAG, "$name start failed (text=${text.name}, vision=${vision.name})", t)
                failures.add("text on ${text.name}, vision on ${vision.name}" to t)
            }
        }
        notes.add("Model file: ${file.fileName}")
        Diagnostics.record(context, "$name acceleration could not start", failures, notes)
        Settings.fallBackToCpu("$name acceleration could not start on this device, so the app switched to CPU.")
        return null
    }

    /**
     * Builds a folder holding only the NPU libraries of this device's chip vendor and
     * returns its path.
     *
     * The app bundles the connector libraries of all three vendors, but the runtime takes
     * whichever connector it finds first in the folder it is given. Pointing it at the
     * app's shared library folder can therefore load, say, the Google Tensor connector on
     * a MediaTek phone. The folder made here contains links to the right vendor's files only.
     */
    internal fun prepareNpuLibraryDir(context: Context, notes: MutableList<String>): String {
        val chip = ModelCatalog.deviceNpu ?: throw IllegalStateException("This chip has no supported NPU.")
        val source = File(context.applicationInfo.nativeLibraryDir)
        val all = source.listFiles().orEmpty()
        // Compiler plugins are only for compiling models on the device; the downloaded
        // NPU builds are already compiled.
        val wanted = all.filter { !it.name.contains("CompilerPlugin") }.filter { f ->
            when (chip.vendor) {
                "MediaTek" -> f.name.contains("MediaTek", ignoreCase = true)
                "Qualcomm" -> f.name.contains("Qualcomm", ignoreCase = true) || f.name.startsWith("libQnn")
                else -> f.name.contains("GoogleTensor", ignoreCase = true) || f.name.contains("google_tensor", ignoreCase = true)
            }
        }
        notes.add("Bundled libraries: ${all.map { it.name }.sorted().joinToString(", ").ifEmpty { "none found in ${source.path}" }}")
        notes.add("Used for ${chip.label}: ${wanted.map { it.name }.sorted().joinToString(", ").ifEmpty { "none" }}")
        if (wanted.none { it.name.startsWith("libLiteRtDispatch") }) {
            throw IllegalStateException("The NPU connector library for ${chip.vendor} is missing from the app.")
        }
        val target = File(context.filesDir, "npu-libs")
        target.deleteRecursively()
        target.mkdirs()
        for (f in wanted) {
            val link = File(target, f.name)
            try {
                Os.symlink(f.absolutePath, link.absolutePath)
            } catch (t: Throwable) {
                f.copyTo(link, overwrite = true)
            }
        }
        return target.absolutePath
    }

    private fun create(context: Context, cfg: Config, text: Backend, vision: Backend): EmbeddingEngine {
        val file = ModelCatalog.resolve(cfg)
        if (!file.isReady(context)) throw ModelMissingException()
        val cache = File(context.cacheDir, "litertlm").apply { mkdirs() }
        val config = EmbeddingEngineConfig(
            modelPath = file.file(context).absolutePath,
            backend = text,
            visionBackend = vision,
            audioBackend = null,
            cacheDir = cache.absolutePath,
            modelFd = null,
            // 140 image tokens do not fit the smallest (128 token) text signature.
            maxInputLength = if (cfg.visionTokens > 70) 256 else 128,
            visionTokensPerImage = cfg.visionTokens,
        )
        val e = EmbeddingEngine(config)
        try {
            e.initialize()
        } catch (t: Throwable) {
            try {
                e.close()
            } catch (_: Throwable) {
            }
            throw t
        }
        return e
    }

    private fun keyOf(cfg: Config) = "${cfg.signature}|${cfg.backend}"

    private const val MAX_TEXT_CHARS = 400
    private const val SHORT_TEXT_CHARS = 120
}
