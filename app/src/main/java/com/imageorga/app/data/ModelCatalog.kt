package com.imageorga.app.data

import android.content.Context
import android.os.Build
import com.imageorga.app.core.NpuChip
import java.io.File

/** A model family the user can pick in settings. */
data class ModelSpec(
    val id: String,
    val title: String,
    val summary: String,
    /** Hugging Face repository holding the generic build and all chip builds. */
    val repoUrl: String,
    /** File name without extension; chip builds append "_<Vendor>_<Chip>". */
    val baseName: String,
    /** Download size of the generic build, shown in settings. */
    val approxMb: Int,
    /** Chips an NPU build is published for; null means "ask the server" (see [NpuChip.known]). */
    val npuSuffixes: Set<String>? = null,
    /** Whether the model can look at images. */
    val vision: Boolean = true,
    /** Complete download link, for models that do not follow the repository naming. */
    val directUrl: String? = null,
) {
    /** Whether an NPU build of this model can be requested for [chip]. */
    fun offersNpu(chip: NpuChip?): Boolean =
        chip != null && directUrl == null && (npuSuffixes == null || chip.suffix in npuSuffixes)
}

/**
 * One concrete model file: either the generic build (runs on CPU and GPU) or a build
 * compiled for one specific chip's NPU (runs only on that NPU).
 */
data class ModelFile(val spec: ModelSpec, val npu: NpuChip?) {
    val fileName: String
        get() = when {
            spec.directUrl != null -> spec.baseName
            npu == null -> "${spec.baseName}.litertlm"
            else -> "${spec.baseName}_${npu.suffix}.litertlm"
        }
    val url: String get() = spec.directUrl ?: "${spec.repoUrl}/resolve/main/$fileName"

    fun file(context: Context): File = File(ModelCatalog.dir(context), fileName)
    fun partFile(context: Context): File = File(ModelCatalog.dir(context), "$fileName.part")

    /** Marker written after a completed download, so half-written files are never loaded. */
    private fun doneMarker(context: Context): File = File(ModelCatalog.dir(context), "$fileName.ok")

    fun isReady(context: Context): Boolean = file(context).exists() && doneMarker(context).exists()

    fun markReady(context: Context) {
        doneMarker(context).writeText("ok")
    }

    fun delete(context: Context) {
        doneMarker(context).delete()
        file(context).delete()
        partFile(context).delete()
    }
}

/**
 * Models the app can run.
 *
 * Embedding models are official LiteRT-LM packages of Google's EmbeddingGemma 2
 * (Apache 2.0, not gated), which embeds text and images into one shared vector space.
 * Chat models are optional and used to double-check sorting and to improve search.
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co"
    const val DEFAULT_ID = "eg2-text-vision-440m"
    const val DEFAULT_LLM_ID = "gemma-4-e2b"
    const val CUSTOM_LLM_ID = "custom"

    val models = listOf(
        ModelSpec(
            id = DEFAULT_ID,
            title = "EmbeddingGemma 2 · Text + Vision 440M",
            summary = "Recommended. Text and image search in 100+ languages.",
            repoUrl = "$HF/litert-community/embeddinggemma-2-text-vision-440m-litert-lm",
            baseName = "embeddinggemma-2-text-vision-440m",
            approxMb = 388,
        ),
        ModelSpec(
            id = "eg2-full-740m",
            title = "EmbeddingGemma 2 · Full 740M",
            summary = "Same image encoder plus an audio encoder this app does not use. Larger download.",
            repoUrl = "$HF/litert-community/embeddinggemma-2-740m-litert-lm",
            baseName = "embeddinggemma-2-740m",
            approxMb = 485,
        ),
    )

    /** Chat models offered for smart sorting and search. */
    val llmModels = listOf(
        ModelSpec(
            id = DEFAULT_LLM_ID,
            title = "Gemma 4 E2B",
            summary = "Google's official build. Looks at photos and understands text.",
            repoUrl = "$HF/litert-community/gemma-4-E2B-it-litert-lm",
            baseName = "gemma-4-E2B-it",
            approxMb = 2588,
            npuSuffixes = setOf("Google_Tensor_G5", "Google_Tensor_G6", "Qualcomm_QCS8275"),
        ),
        ModelSpec(
            id = "gemma-4-e2b-abliterated",
            title = "Gemma 4 E2B abliterated (uncensored)",
            summary = "Community build by huihui-ai / edp1096 with refusals removed. Looks at photos. Unofficial; CPU and GPU only.",
            repoUrl = "$HF/edp1096/Huihui-gemma-4-E2B-it-abliterated-litert-lm",
            baseName = "Huihui-gemma-4-E2B-it-abliterated",
            approxMb = 5248,
            npuSuffixes = emptySet(),
        ),
    )

    /** The NPU of this device, if its chip is one that model builds are named after. */
    val deviceNpu: NpuChip? by lazy {
        if (Build.VERSION.SDK_INT >= 31) NpuChip.detect(Build.SOC_MODEL) else null
    }

    /** Chip model as reported by the system, for display. */
    val deviceChipName: String
        get() = if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}".trim() else Build.HARDWARE

    fun byId(id: String): ModelSpec? = models.firstOrNull { it.id == id }

    fun llmById(id: String): ModelSpec? =
        if (id == CUSTOM_LLM_ID) customLlm("") else llmModels.firstOrNull { it.id == id }

    /** The embedding model file the given configuration runs on. */
    fun resolve(config: Config): ModelFile {
        val spec = byId(config.modelId) ?: models.first()
        val npu = if (config.backend == Config.BACKEND_NPU) deviceNpu else null
        return ModelFile(spec, npu)
    }

    /** The chat model file the given configuration runs on; null when a custom link is missing. */
    fun resolveLlm(config: Config): ModelFile? {
        val spec = if (config.llmModelId == CUSTOM_LLM_ID) {
            if (config.llmCustomUrl.isBlank()) return null
            customLlm(config.llmCustomUrl) ?: return null
        } else {
            llmModels.firstOrNull { it.id == config.llmModelId } ?: llmModels.first()
        }
        val npu = deviceNpu.takeIf { config.llmBackend == Config.BACKEND_NPU && spec.offersNpu(it) }
        return ModelFile(spec, npu)
    }

    /** Describes a model the user points to by link. Returns null for a link that is not usable. */
    fun customLlm(url: String): ModelSpec? {
        val clean = url.trim()
        if (clean.isEmpty()) {
            return ModelSpec(CUSTOM_LLM_ID, "Your own model", "Any .litertlm chat model, by download link.", "", "", 0, emptySet())
        }
        if (!clean.startsWith("https://")) return null
        // Hugging Face page links ("/blob/") do not serve the file itself.
        val direct = clean.replace("/blob/", "/resolve/").substringBefore('?')
        val name = direct.substringAfterLast('/').replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (!name.endsWith(".litertlm")) return null
        return ModelSpec(CUSTOM_LLM_ID, "Your own model", name, "", "custom-$name", 0, emptySet(), vision = true, directUrl = direct)
    }

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Removes every downloaded file that the given configuration does not use. */
    fun deleteUnused(context: Context, config: Config) {
        val keep = usedFileNames(config)
        dir(context).listFiles()?.forEach { f ->
            if (keep.none { f.name.startsWith(it) }) f.delete()
        }
    }

    /** File names the configuration uses; partial downloads and markers share the prefix. */
    fun usedFileNames(config: Config): List<String> = listOfNotNull(
        resolve(config).fileName,
        resolveLlm(config)?.fileName?.takeIf { config.llmEnabled },
    )
}
