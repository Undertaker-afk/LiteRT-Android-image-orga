package com.imageorga.app.index

import android.content.Context
import com.imageorga.app.core.VectorMath
import com.imageorga.app.data.Config
import com.imageorga.app.data.Db

/** A photo id with its similarity to the query. */
data class ScoredId(val id: Long, val score: Float)

/**
 * All photo vectors of the current embedding space, held in memory as one flat matrix so
 * that a search is a single pass of dot products (a few milliseconds for tens of thousands
 * of photos).
 */
object VectorIndex {
    private var sig: String? = null
    private var dim = 0
    private var n = 0
    private var ids = LongArray(0)
    private var data = FloatArray(0)
    private val rowOf = HashMap<Long, Int>()

    @Synchronized
    private fun ensure(context: Context, cfg: Config) {
        if (sig == cfg.signature) return
        val (loadedIds, loadedData) = Db.get(context).loadVectors(cfg.signature, cfg.dim)
        ids = loadedIds
        data = loadedData
        n = loadedIds.size
        dim = cfg.dim
        sig = cfg.signature
        rowOf.clear()
        for (i in 0 until n) rowOf[ids[i]] = i
    }

    /** Forces a reload from the database on next use (after photos were deleted or reset). */
    @Synchronized
    fun invalidate() {
        sig = null
    }

    @Synchronized
    fun add(context: Context, cfg: Config, id: Long, vector: FloatArray) {
        ensure(context, cfg)
        if (vector.size != dim) return
        val existing = rowOf[id]
        if (existing != null) {
            System.arraycopy(vector, 0, data, existing * dim, dim)
            return
        }
        if (n == ids.size) {
            val cap = maxOf(64, n * 2)
            ids = ids.copyOf(cap)
            data = data.copyOf(cap * dim)
        }
        ids[n] = id
        System.arraycopy(vector, 0, data, n * dim, dim)
        rowOf[id] = n
        n++
    }

    @Synchronized
    fun size(context: Context, cfg: Config): Int {
        ensure(context, cfg)
        return n
    }

    @Synchronized
    fun vectorOf(context: Context, cfg: Config, id: Long): FloatArray? {
        ensure(context, cfg)
        val row = rowOf[id] ?: return null
        return data.copyOfRange(row * dim, row * dim + dim)
    }

    @Synchronized
    fun search(context: Context, cfg: Config, query: FloatArray, limit: Int, relativeCut: Float): List<ScoredId> {
        ensure(context, cfg)
        if (query.size != dim) return emptyList()
        return VectorMath.search(data, n, dim, query, limit, relativeCut).map { ScoredId(ids[it.row], it.score) }
    }

    /** Sorts every indexed photo into the best matching category; null means unsorted. */
    @Synchronized
    fun assign(context: Context, cfg: Config, categoryIds: LongArray, vectors: List<List<FloatArray>>): Pair<LongArray, Array<Long?>> {
        ensure(context, cfg)
        val a = VectorMath.assign(data, n, dim, vectors, cfg.effectiveThreshold)
        val cats = Array<Long?>(n) { i -> if (a.category[i] < 0) null else categoryIds[a.category[i]] }
        return ids.copyOf(n) to cats
    }
}
