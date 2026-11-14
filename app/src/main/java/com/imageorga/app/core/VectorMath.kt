package com.imageorga.app.core

import kotlin.math.sqrt

/** A search hit: row index into the vector matrix plus its cosine similarity. */
data class Hit(val row: Int, val score: Float)

/** Per row: candidate category indices, best first, with their scores. */
class Candidates(val category: Array<IntArray>, val score: Array<FloatArray>)

/** Result of sorting every image into the best matching category. */
class Assignment(val category: IntArray, val score: FloatArray)

/**
 * Pure vector maths over a flat row-major matrix of L2-normalised embeddings.
 * No Android dependencies so it can be unit tested on the JVM.
 */
object VectorMath {

    fun dot(data: FloatArray, row: Int, dim: Int, q: FloatArray): Float {
        var s = 0f
        val off = row * dim
        for (i in 0 until dim) s += data[off + i] * q[i]
        return s
    }

    fun normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val n = sqrt(s)
        if (n < 1e-12f) return v
        return FloatArray(v.size) { v[it] / n }
    }

    /** Mean of the first [n] rows. Used as the "typical photo" baseline. */
    fun mean(data: FloatArray, n: Int, dim: Int): FloatArray {
        val m = FloatArray(dim)
        if (n == 0) return m
        for (r in 0 until n) {
            val off = r * dim
            for (i in 0 until dim) m[i] += data[off + i]
        }
        for (i in 0 until dim) m[i] /= n
        return m
    }

    /**
     * Ranks all rows against [q].
     *
     * With [relativeCut] > 0, results are trimmed to those whose lead over the library
     * average is at least that fraction of the best hit's lead. This hides the long
     * tail of unrelated photos without relying on an absolute similarity value, which
     * shifts with the model and vector size.
     */
    fun search(
        data: FloatArray,
        n: Int,
        dim: Int,
        q: FloatArray,
        limit: Int,
        relativeCut: Float = 0f,
    ): List<Hit> {
        if (n == 0) return emptyList()
        val scores = FloatArray(n)
        var sum = 0.0
        for (r in 0 until n) {
            val s = dot(data, r, dim, q)
            scores[r] = s
            sum += s
        }
        val order = (0 until n).sortedByDescending { scores[it] }
        val avg = (sum / n).toFloat()
        val best = scores[order[0]]
        val floor = if (relativeCut > 0f) avg + (best - avg) * relativeCut else Float.NEGATIVE_INFINITY
        val out = ArrayList<Hit>(minOf(limit, n))
        for (r in order) {
            if (out.size >= limit) break
            // Always keep the single best hit, even in a one-photo library.
            if (scores[r] < floor && out.isNotEmpty()) break
            out.add(Hit(r, scores[r]))
        }
        return out
    }

    /**
     * Assigns each row to the category it matches best.
     *
     * Each category is described by several text vectors (its topic, its full text and
     * every item listed in its description). A photo's score for one text is how much more
     * similar it is to that text than the average photo in the library is; its score for
     * a category is the best of those. Taking the best rather than an average matters:
     * a photo of a cat matches "cats" strongly but "pets, dogs, hamsters" only weakly.
     *
     * A photo joins the best scoring category only if that score reaches [threshold];
     * otherwise it stays unsorted (-1).
     */
    fun assign(
        data: FloatArray,
        n: Int,
        dim: Int,
        categories: List<List<FloatArray>>,
        threshold: Float,
    ): Assignment {
        val cat = IntArray(n) { -1 }
        val score = FloatArray(n)
        if (n == 0 || categories.isEmpty()) return Assignment(cat, score)
        val m = mean(data, n, dim)
        val texts = ArrayList<FloatArray>()
        val owner = ArrayList<Int>()
        categories.forEachIndexed { c, vectors ->
            for (v in vectors) {
                texts.add(v)
                owner.add(c)
            }
        }
        val baseline = FloatArray(texts.size) { t ->
            var s = 0f
            val v = texts[t]
            for (i in 0 until dim) s += v[i] * m[i]
            s
        }
        for (r in 0 until n) {
            var bestC = -1
            var bestS = Float.NEGATIVE_INFINITY
            for (t in texts.indices) {
                val s = dot(data, r, dim, texts[t]) - baseline[t]
                if (s > bestS) {
                    bestS = s
                    bestC = owner[t]
                }
            }
            if (bestS >= threshold) {
                cat[r] = bestC
                score[r] = bestS
            }
        }
        return Assignment(cat, score)
    }

    /**
     * For each row, the categories it could belong to, best first.
     *
     * Scores are computed as in [assign]. A category is a candidate when its score reaches
     * [minScore]; at most [maxPerRow] are kept. Rows without candidates get empty arrays.
     */
    fun candidates(
        data: FloatArray,
        n: Int,
        dim: Int,
        categories: List<List<FloatArray>>,
        minScore: Float,
        maxPerRow: Int,
    ): Candidates {
        val cats = Array(n) { EMPTY_INTS }
        val scores = Array(n) { EMPTY_FLOATS }
        if (n == 0 || categories.isEmpty()) return Candidates(cats, scores)
        val m = mean(data, n, dim)
        val baselines = categories.map { vectors ->
            FloatArray(vectors.size) { t ->
                var s = 0f
                for (i in 0 until dim) s += vectors[t][i] * m[i]
                s
            }
        }
        val best = FloatArray(categories.size)
        for (r in 0 until n) {
            for (c in categories.indices) {
                var b = Float.NEGATIVE_INFINITY
                val vectors = categories[c]
                for (t in vectors.indices) {
                    val s = dot(data, r, dim, vectors[t]) - baselines[c][t]
                    if (s > b) b = s
                }
                best[c] = b
            }
            val order = categories.indices.filter { best[it] >= minScore }.sortedByDescending { best[it] }.take(maxPerRow)
            if (order.isNotEmpty()) {
                cats[r] = order.toIntArray()
                scores[r] = FloatArray(order.size) { best[order[it]] }
            }
        }
        return Candidates(cats, scores)
    }

    /**
     * Ranks all rows against several phrasings of one search and keeps, per row, the best.
     * Each phrasing is scored against its own library average, so a phrasing that is
     * simply closer to every photo does not dominate.
     */
    fun searchMulti(
        data: FloatArray,
        n: Int,
        dim: Int,
        queries: List<FloatArray>,
        limit: Int,
        relativeCut: Float,
    ): List<Hit> {
        if (n == 0 || queries.isEmpty()) return emptyList()
        val m = mean(data, n, dim)
        val scores = FloatArray(n) { Float.NEGATIVE_INFINITY }
        for (q in queries) {
            var base = 0f
            for (i in 0 until dim) base += q[i] * m[i]
            for (r in 0 until n) {
                val s = dot(data, r, dim, q) - base
                if (s > scores[r]) scores[r] = s
            }
        }
        val order = (0 until n).sortedByDescending { scores[it] }
        val floor = scores[order[0]] * relativeCut
        val out = ArrayList<Hit>(minOf(limit, n))
        for (r in order) {
            if (out.size >= limit) break
            if (scores[r] < floor && out.isNotEmpty()) break
            out.add(Hit(r, scores[r]))
        }
        return out
    }

    /**
     * The texts a category is matched with: the topic, topic plus description, each item
     * of the description when it is written as a list ("cats, dogs, hamsters"), and any
     * [extra] phrases (suggested by the chat model).
     */
    fun categoryTexts(topic: String, description: String, extra: List<String> = emptyList()): List<String> {
        val out = LinkedHashSet<String>()
        val t = topic.trim()
        val d = description.trim()
        if (t.isNotEmpty()) out.add(t)
        if (d.isNotEmpty()) {
            out.add(if (t.isEmpty()) d else "$t. $d")
            d.split(ITEM_SEPARATOR).map { it.trim() }.filter { it.length > 1 }.take(MAX_ITEMS).forEach { out.add(it) }
        }
        extra.map { it.trim() }.filter { it.length > 1 }.take(MAX_ITEMS).forEach { out.add(it) }
        return out.toList()
    }

    /**
     * Turns a chat model's free-text list into clean phrases: one per line or comma,
     * without bullets, numbering or quotes.
     */
    fun parsePhraseList(reply: String, max: Int): List<String> =
        reply.split('\n', ',', ';')
            .map { it.trim().trimStart('-', '*', '•', '·').replace(LEADING_NUMBER, "").trim().trim('"', '“', '”', '\'', '.') }
            .filter { it.length in 2..60 && !it.endsWith(":") }
            .distinctBy { it.lowercase() }
            .take(max)

    /** Reads a yes/no reply in English or German; null when it is neither. */
    fun parseYesNo(reply: String): Boolean? {
        val word = reply.trim().lowercase().takeWhile { it.isLetter() }
        return when (word) {
            "yes", "ja", "true" -> true
            "no", "nein", "false" -> false
            else -> null
        }
    }

    private val LEADING_NUMBER = Regex("^\\d+[.)]\\s*")
    private val EMPTY_INTS = IntArray(0)
    private val EMPTY_FLOATS = FloatArray(0)

    private val ITEM_SEPARATOR = Regex("[,;:\\n•]|\\s(?:or|and|oder|und)\\s", RegexOption.IGNORE_CASE)
    private const val MAX_ITEMS = 10
}
