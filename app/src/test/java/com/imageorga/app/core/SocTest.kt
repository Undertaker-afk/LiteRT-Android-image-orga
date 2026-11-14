package com.imageorga.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SocTest {
    @Test
    fun detectsQualcomm() {
        val c = NpuChip.detect("SM8750")!!
        assertEquals("Qualcomm_SM8750", c.suffix)
        assertTrue(c.known)
        assertEquals("Qualcomm_SM8650", NpuChip.detect("sm8650-ab")!!.suffix)
    }

    @Test
    fun detectsMediaTekWithRevisionSuffix() {
        assertEquals("MediaTek_MT6991", NpuChip.detect("MT6991Z/CZA")!!.suffix)
    }

    @Test
    fun detectsTensorInBothSpellings() {
        assertEquals("Google_Tensor_G5", NpuChip.detect("Tensor G5")!!.suffix)
        assertEquals("Google_Tensor_G6", NpuChip.detect("tensor_g6")!!.suffix)
    }

    @Test
    fun unknownChipIsDetectedButNotKnown() {
        val c = NpuChip.detect("SM7550")!!
        assertEquals("Qualcomm_SM7550", c.suffix)
        assertFalse(c.known)
    }

    @Test
    fun unrecognisedOrMissingModelGivesNull() {
        assertNull(NpuChip.detect(null))
        assertNull(NpuChip.detect(""))
        assertNull(NpuChip.detect("Exynos 2400"))
    }
}
