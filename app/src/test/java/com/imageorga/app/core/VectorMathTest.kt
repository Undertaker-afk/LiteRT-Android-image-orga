package com.imageorga.app.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class VectorMathTest {

    private fun unit(vararg v: Float) = VectorMath.normalize(v)

    /** Four photos in 3 dimensions: two "cat" photos, one "beach" photo, one unrelated. */
    private val rows = listOf(
        unit(1f, 0.1f, 0f),
        unit(0.9f, 0f, 0.2f),
        unit(0f, 1f, 0.1f),
        unit(0.3f, 0.3f, 1f),
    )
    private val data = rows.flatMap { it.toList() }.toFloatArray()

    @Test
    fun searchRanksBestMatchFirst() {
        val hits = VectorMath.search(data, 4, 3, unit(1f, 0f, 0f), limit = 10)
        assertEquals(4, hits.size)
        assertEquals(0, hits[0].row)
        assertEquals(1, hits[1].row)
        assertTrue(hits[0].score >= hits[1].score)
    }

    @Test
    fun relativeCutDropsUnrelatedTail() {
        val hits = VectorMath.search(data, 4, 3, unit(1f, 0f, 0f), limit = 10, relativeCut = 0.4f)
        assertEquals(listOf(0, 1), hits.map { it.row })
    }

    @Test
    fun searchRespectsLimitAndEmptyIndex() {
        assertEquals(1, VectorMath.search(data, 4, 3, unit(1f, 0f, 0f), limit = 1).size)
        assertTrue(VectorMath.search(FloatArray(0), 0, 3, unit(1f, 0f, 0f), limit = 5).isEmpty())
    }

    @Test
    fun assignPicksBestCategoryAndLeavesWeakMatchesUnsorted() {
        val cats = listOf(listOf(unit(1f, 0f, 0f)), listOf(unit(0f, 1f, 0f)))
        val a = VectorMath.assign(data, 4, 3, cats, threshold = 0.2f)
        assertEquals(0, a.category[0])
        assertEquals(0, a.category[1])
        assertEquals(1, a.category[2])
        assertEquals(-1, a.category[3])
    }

    @Test
    fun assignUsesBestOfSeveralTextsPerCategory() {
        // Category 0 has one unrelated text and one that matches the "beach" photo.
        val cats = listOf(listOf(unit(0f, 0f, -1f), unit(0f, 1f, 0f)), listOf(unit(1f, 0f, 0f)))
        val a = VectorMath.assign(data, 4, 3, cats, threshold = 0.2f)
        assertEquals(0, a.category[2])
        assertEquals(1, a.category[0])
    }

    @Test
    fun categoryTextsSplitsListedItems() {
        assertEquals(
            listOf("Pets", "Pets. cats, dogs and hamsters", "cats", "dogs", "hamsters"),
            VectorMath.categoryTexts("Pets", "cats, dogs and hamsters"),
        )
        assertEquals(listOf("Pets"), VectorMath.categoryTexts(" Pets ", ""))
        assertEquals(listOf("Katzen", "Katzen. Hund oder Katze", "Hund", "Katze"), VectorMath.categoryTexts("Katzen", "Hund oder Katze"))
    }

    @Test
    fun assignWithNoCategoriesLeavesEverythingUnsorted() {
        val a = VectorMath.assign(data, 4, 3, emptyList(), threshold = 0.1f)
        assertTrue(a.category.all { it == -1 })
    }
}
