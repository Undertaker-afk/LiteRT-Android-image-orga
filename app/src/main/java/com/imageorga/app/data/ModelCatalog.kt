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
)

/**
 * One concrete model file: either the generic build (runs on CPU and GPU) or a build
 * compiled for one specific chip's NPU (runs only on that NPU).
 */
data class ModelFile(val spec: ModelSpec, val npu: NpuChip?) {
    val fileName: String
        get() = if (npu == null) "${spec.baseName}.litertlm" else "${spec.baseName}_${npu.suffix}.litertlm"
    val url: String get() = "${spec.repoUrl}/resolve/main/$fileName"

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
 * Models the app can run. All are official LiteRT-LM packages of Google's EmbeddingGemma 2
 * (Apache 2.0, not gated), which embeds text and images into one shared vector space.
 */
object ModelCatalog {
    private const val HF = "https://huggingface.co/litert-community"
    const val DEFAULT_ID = "eg2-text-vision-440m"

    val models = listOf(
        ModelSpec(
            id = DEFAULT_ID,
            title = "EmbeddingGemma 2 · Text + Vision 440M",
            summary = "Recommended. Text and image search in 100+ languages.",
            repoUrl = "$HF/embeddinggemma-2-text-vision-440m-litert-lm",
            baseName = "embeddinggemma-2-text-vision-440m",
            approxMb = 388,
        ),
        ModelSpec(
            id = "eg2-full-740m",
            title = "EmbeddingGemma 2 · Full 740M",
            summary = "Same image encoder plus an audio encoder this app does not use. Larger download.",
            repoUrl = "$HF/embeddinggemma-2-740m-litert-lm",
            baseName = "embeddinggemma-2-740m",
            approxMb = 485,
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

    /** The model file the given configuration runs on. */
    fun resolve(config: Config): ModelFile {
        val spec = byId(config.modelId) ?: models.first()
        val npu = if (config.backend == Config.BACKEND_NPU) deviceNpu else null
        return ModelFile(spec, npu)
    }

    fun dir(context: Context): File = File(context.filesDir, "models").apply { mkdirs() }

    /** Removes every downloaded file that the given configuration does not use. */
    fun deleteUnused(context: Context, config: Config) {
        val keep = resolve(config).fileName
        dir(context).listFiles()?.forEach { f ->
            if (!f.name.startsWith(keep)) f.delete()
        }
    }
}
