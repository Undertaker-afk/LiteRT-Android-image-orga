package com.imageorga.app.ml

import android.content.Context
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import com.imageorga.app.core.VectorMath
import com.imageorga.app.data.Config
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * The optional local chat model (Gemma). It does two jobs:
 *
 *  - turn a category or a search into a few concrete things photos would show, and
 *  - look at one photo and say whether it belongs in a category.
 *
 * The model needs gigabytes of memory, so it is loaded on first use and unloaded again
 * shortly after the last request.
 */
object Llm {
    private const val TAG = "Llm"
    private const val IDLE_UNLOAD_MS = 45_000L

    private val mutex = Mutex()
    private var engine: Engine? = null
    private var engineKey: String? = null
    private var unloadJob: Job? = null

    /** Set while the background job is checking photos, so the model stays loaded. */
    @Volatile
    var busy = false

    /** True when smart features are switched on and the chat model file is on the device. */
    fun isReady(context: Context, cfg: Config = Settings.current): Boolean =
        cfg.llmEnabled && ModelCatalog.resolveLlm(cfg)?.isReady(context) == true

    /** Identifies the chat model in stored answers, so a different model starts fresh. */
    fun modelKey(cfg: Config = Settings.current): String = ModelCatalog.resolveLlm(cfg)?.fileName.orEmpty()

    /**
     * Concrete things that photos matching [topic] typically show, as short phrases.
     * Returns an empty list when the model gives nothing usable.
     */
    suspend fun expand(context: Context, topic: String, description: String = ""): List<String> {
        val what = if (description.isBlank()) "\"$topic\"" else "\"$topic\" ($description)"
        val reply = ask(
            context,
            system = "You help search a personal photo gallery. You reply with a plain list and nothing else.",
            parts = listOf(
                Content.Text(
                    "Someone looks for photos of $what in their own photo gallery. The words may be in any language. " +
                        "List up to 5 different things such photos typically show, as short English noun phrases of 1 to 4 words, " +
                        "one per line. No numbering, no explanations."
                )
            ),
        )
        return VectorMath.parsePhraseList(reply, max = 5)
    }

    /**
     * Whether the photo ([imageBytes], JPEG or PNG) belongs in the category. Returns null
     * when the model answers with something other than yes or no.
     */
    suspend fun belongs(context: Context, imageBytes: ByteArray, topic: String, description: String): Boolean? {
        val label = if (description.isBlank()) "\"$topic\"" else "\"$topic\" ($description)"
        val reply = ask(
            context,
            system = "You sort photos of a personal gallery into categories. You answer with exactly one word: yes or no.",
            parts = listOf(
                Content.ImageBytes(imageBytes),
                Content.Text("Does this photo belong in the category $label? Answer yes or no."),
            ),
        )
        return VectorMath.parseYesNo(reply)
    }

    suspend fun release() = mutex.withLock { closeLocked() }

    private suspend fun ask(context: Context, system: String, parts: List<Content>): String = mutex.withLock {
        withContext(Dispatchers.Default) {
            unloadJob?.cancel()
            try {
                val e = ensureLocked(context.applicationContext)
                // A fresh conversation per question: questions are independent of each other.
                val config = ConversationConfig(
                    systemInstruction = Contents.of(system),
                    // Always pick the most likely word, so the same photo gets the same answer.
                    samplerConfig = SamplerConfig(topK = 1, topP = 1.0, temperature = 0.0, seed = 0),
                )
                e.createConversation(config).use { conversation ->
                    val message = conversation.sendMessage(Contents.of(parts))
                    message.contents.contents.filterIsInstance<Content.Text>().joinToString("") { it.text }.trim()
                }
            } finally {
                scheduleUnload()
            }
        }
    }

    private fun scheduleUnload() {
        unloadJob?.cancel()
        @Suppress("OPT_IN_USAGE")
        unloadJob = GlobalScope.launch {
            delay(IDLE_UNLOAD_MS)
            if (!busy) mutex.withLock { closeLocked() }
        }
    }

    private fun closeLocked() {
        try {
            engine?.close()
        } catch (t: Throwable) {
            Log.w(TAG, "close failed", t)
        }
        engine = null
        engineKey = null
    }

    private fun ensureLocked(context: Context): Engine {
        var cfg = Settings.current
        var key = "${modelKey(cfg)}|${cfg.llmBackend}"
        engine?.let { if (engineKey == key) return it }
        closeLocked()

        if (cfg.llmBackend != Config.BACKEND_CPU) {
            val name = cfg.llmBackend.uppercase()
            try {
                val backend: Backend = if (cfg.llmBackend == Config.BACKEND_NPU) {
                    Backend.NPU(Embedder.prepareNpuLibraryDir(context, ArrayList()))
                } else {
                    Backend.GPU()
                }
                val e = create(context, cfg, backend)
                Settings.clearLlmNote()
                engine = e
                engineKey = key
                return e
            } catch (t: Throwable) {
                if (t is ModelMissingException) throw t
                Log.w(TAG, "$name start failed", t)
                Settings.llmFallBackToCpu("The chat model could not start on $name, so it runs on CPU. (${t.message?.lineSequence()?.lastOrNull()?.take(120) ?: "unknown error"})")
                cfg = Settings.current
                key = "${modelKey(cfg)}|${cfg.llmBackend}"
            }
        }

        val e = create(context, cfg, Backend.CPU())
        engine = e
        engineKey = key
        return e
    }

    private fun create(context: Context, cfg: Config, backend: Backend): Engine {
        val file = ModelCatalog.resolveLlm(cfg)
        if (file == null || !file.isReady(context)) throw ModelMissingException()
        val cache = File(context.cacheDir, "litertlm-chat").apply { mkdirs() }
        val config = EngineConfig(
            modelPath = file.file(context).absolutePath,
            backend = backend,
            visionBackend = if (file.spec.vision) backend else null,
            // One photo plus a short question and a one-word answer fit easily. NPU builds
            // are compiled for a fixed size and must not be given another one.
            maxNumTokens = if (backend is Backend.NPU) null else 2048,
            maxNumImages = if (file.spec.vision) 1 else null,
            cacheDir = cache.absolutePath,
        )
        val e = Engine(config)
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
}
