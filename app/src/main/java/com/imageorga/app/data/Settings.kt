package com.imageorga.app.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** User-adjustable configuration. */
data class Config(
    val modelId: String = ModelCatalog.DEFAULT_ID,
    /** Image detail: how many tokens the vision encoder spends per photo (70 or 140). */
    val visionTokens: Int = 70,
    /** Length of each stored vector (128, 256, 512 or 768). */
    val dim: Int = 256,
    /** One of [BACKEND_NPU], [BACKEND_GPU], [BACKEND_CPU]. */
    val backend: String = BACKEND_CPU,
    val autoIndex: Boolean = true,
    val chargingOnly: Boolean = false,
    val wifiOnly: Boolean = true,
    /** How clearly a photo must match a category before it is sorted into it. */
    val strictness: Float = DEFAULT_STRICTNESS,
) {
    /**
     * Identifies the embedding space. Vectors produced under different signatures are not
     * compared with each other, so changing the model file (which differs between the NPU
     * and the generic build), image detail or vector size means photos are embedded again.
     */
    val signature: String get() = "${ModelCatalog.resolve(this).fileName}/$visionTokens/$dim"

    /**
     * Threshold actually applied when sorting. Short vectors compress similarity
     * differences, so the same slider position needs a smaller cut-off there.
     */
    val effectiveThreshold: Float get() = strictness * if (dim <= 128) 0.75f else 1f

    companion object {
        const val BACKEND_NPU = "npu"
        const val BACKEND_GPU = "gpu"
        const val BACKEND_CPU = "cpu"
        /** Chosen from test runs: real matches scored 0.09–0.16, non-matches below 0.07. */
        const val DEFAULT_STRICTNESS = 0.08f
        val DIMS = listOf(128, 256, 512, 768)
        val VISION_TOKENS = listOf(70, 140)
    }
}

object Settings {
    private const val FILE = "settings"
    private const val KEY_PENDING = "accelPending"
    private const val KEY_NOTE = "accelNote"
    private lateinit var prefs: SharedPreferences
    private val state = MutableStateFlow(Config())
    val flow: StateFlow<Config> get() = state
    val current: Config get() = state.value

    /** Explains the last automatic fallback away from NPU or GPU, if any. */
    private val note = MutableStateFlow<String?>(null)
    val accelNote: StateFlow<String?> get() = note

    fun init(context: Context) {
        if (::prefs.isInitialized) return
        prefs = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val d = Config()
        // First launch: use the NPU when a build for this chip is known to exist.
        val defaultBackend = if (ModelCatalog.deviceNpu?.known == true) Config.BACKEND_NPU else Config.BACKEND_CPU
        var backend = prefs.getString("backend", defaultBackend)!!
        if (backend == Config.BACKEND_NPU && ModelCatalog.deviceNpu == null) backend = Config.BACKEND_CPU

        // An accelerator that was being started when the process died took the app down
        // with it. Do not try it again automatically.
        val crashed = prefs.getString(KEY_PENDING, null)
        if (crashed != null) {
            if (backend == crashed) backend = Config.BACKEND_CPU
            prefs.edit().remove(KEY_PENDING)
                .putString(KEY_NOTE, "${crashed.uppercase()} acceleration crashed while starting, so the app switched to CPU.")
                .apply()
        }
        note.value = prefs.getString(KEY_NOTE, null)

        state.value = Config(
            modelId = prefs.getString("modelId", d.modelId)!!.let { if (ModelCatalog.byId(it) == null) d.modelId else it },
            visionTokens = prefs.getInt("visionTokens", d.visionTokens).let { if (it in Config.VISION_TOKENS) it else d.visionTokens },
            dim = prefs.getInt("dim", d.dim).let { if (it in Config.DIMS) it else d.dim },
            backend = backend,
            autoIndex = prefs.getBoolean("autoIndex", d.autoIndex),
            chargingOnly = prefs.getBoolean("chargingOnly", d.chargingOnly),
            wifiOnly = prefs.getBoolean("wifiOnly", d.wifiOnly),
            strictness = prefs.getFloat("strictness", d.strictness),
        )
        persist(state.value)
    }

    fun update(transform: (Config) -> Config) {
        val c = transform(state.value)
        state.value = c
        persist(c)
    }

    private fun persist(c: Config) {
        prefs.edit()
            .putString("modelId", c.modelId)
            .putInt("visionTokens", c.visionTokens)
            .putInt("dim", c.dim)
            .putString("backend", c.backend)
            .putBoolean("autoIndex", c.autoIndex)
            .putBoolean("chargingOnly", c.chargingOnly)
            .putBoolean("wifiOnly", c.wifiOnly)
            .putFloat("strictness", c.strictness)
            .apply()
    }

    /** Called right before an accelerator is started; survives a native crash. */
    fun markAccelStarting(backend: String) {
        prefs.edit().putString(KEY_PENDING, backend).commit()
    }

    fun markAccelStarted() {
        prefs.edit().remove(KEY_PENDING).apply()
    }

    /** Switches to CPU after an accelerator failed, remembering why. */
    fun fallBackToCpu(reason: String) {
        prefs.edit().remove(KEY_PENDING).putString(KEY_NOTE, reason).apply()
        note.value = reason
        update { it.copy(backend = Config.BACKEND_CPU) }
    }

    fun clearAccelNote() {
        prefs.edit().remove(KEY_NOTE).apply()
        note.value = null
    }
}
