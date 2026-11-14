package com.imageorga.app.core

/** A neural processing unit that a chip-specific model build exists (or may exist) for. */
data class NpuChip(
    /** Vendor name exactly as used in model file names: Qualcomm, MediaTek or Google. */
    val vendor: String,
    /** Chip name exactly as used in model file names, e.g. SM8750, MT6991, Tensor_G5. */
    val chip: String,
) {
    /** File name suffix of the matching model build, e.g. "Qualcomm_SM8750". */
    val suffix: String get() = "${vendor}_$chip"

    /** True when a build for this chip is known to be published. */
    val known: Boolean get() = suffix in KNOWN

    val label: String get() = "$vendor ${chip.replace('_', ' ')}"

    companion object {
        /** Chip builds published for EmbeddingGemma 2 at the time of writing. */
        val KNOWN = setOf(
            "Qualcomm_SM8550", "Qualcomm_SM8650", "Qualcomm_SM8750", "Qualcomm_SM8850", "Qualcomm_QCS8275",
            "MediaTek_MT6991", "MediaTek_MT6993",
            "Google_Tensor_G5", "Google_Tensor_G6",
        )

        private val qualcomm = Regex("(SM|QCS)\\d{4}")
        private val mediatek = Regex("MT\\d{4}")
        private val tensor = Regex("TENSOR[ _-]?(G\\d+)")

        /**
         * Maps the system-reported chip model (Build.SOC_MODEL, e.g. "SM8750", "MT6991Z/CZA",
         * "Tensor G5") to the naming used by published model builds. Pure, for unit testing.
         */
        fun detect(socModel: String?): NpuChip? {
            val m = socModel?.trim()?.uppercase().orEmpty()
            if (m.isEmpty()) return null
            tensor.find(m)?.let { return NpuChip("Google", "Tensor_${it.groupValues[1]}") }
            qualcomm.find(m)?.let { return NpuChip("Qualcomm", it.value) }
            mediatek.find(m)?.let { return NpuChip("MediaTek", it.value) }
            return null
        }
    }
}
