package com.imageorga.app.index

import android.content.Context
import android.util.Log
import com.imageorga.app.core.VectorMath
import com.imageorga.app.data.Category
import com.imageorga.app.data.Config
import com.imageorga.app.data.Db
import com.imageorga.app.data.ModelCatalog
import com.imageorga.app.data.Settings
import com.imageorga.app.ml.Embedder
import com.imageorga.app.ml.Llm
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Everything needed to decide which category each photo goes into. */
class SortPlan(
    val categories: List<Category>,
    /** Per category: identifies its text and the chat model, for stored photo checks. */
    val checkKeys: List<String>,
    val candidates: PhotoCandidates,
    /** Photo id -> category id -> the chat model's answer. */
    val checks: HashMap<Long, HashMap<Long, Boolean>>,
    /** Whether the chat model's photo checks take part in sorting. */
    val useChecks: Boolean,
    val threshold: Float,
)

/** Sorts photos into the user's categories by comparing photo vectors with category text. */
object Categorizer {
    private const val TAG = "Categorizer"

    /**
     * With photo checks on, photos scoring this fraction of the usual threshold are
     * already shown to the chat model. It can then accept photos the vectors alone
     * would have left out.
     */
    private const val LOOSE_FACTOR = 0.6f

    private val mutex = Mutex()

    /**
     * Brings category vectors up to date, then re-sorts every indexed photo that the user
     * has not placed by hand. Cheap enough to run after every change.
     */
    suspend fun run(context: Context) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val app = context.applicationContext
            val plan = plan(app)
            apply(app, plan)
        }
    }

    /** Builds the current [SortPlan], embedding and expanding category texts as needed. */
    suspend fun plan(context: Context): SortPlan {
        val db = Db.get(context)
        val cfg = Settings.current
        val llmReady = Llm.isReady(context, cfg)

        // 1. Let the chat model suggest concrete phrases for categories it has not seen yet.
        if (llmReady) {
            for (s in db.categoryStates()) {
                val key = expansionKey(s.category, cfg)
                if (s.expandedKey == key) continue
                try {
                    db.saveCategoryExpansion(s.category.id, key, Llm.expand(context, s.category.name, s.category.description))
                } catch (t: Throwable) {
                    Log.w(TAG, "could not expand category ${s.category.name}", t)
                    break // most likely the model cannot start; do not retry for every category
                }
            }
        }

        // 2. Embed every category whose texts changed.
        val states = db.categoryStates()
        val wanted = HashMap<Long, String>()
        for (s in states) {
            val extra = if (llmReady && s.expandedKey == expansionKey(s.category, cfg)) s.expanded else emptyList()
            val texts = VectorMath.categoryTexts(s.category.name, s.category.description, extra)
            val sig = "${cfg.signature}#${texts.hashCode()}"
            wanted[s.category.id] = sig
            if (s.vectorSig != sig) {
                db.saveCategoryVectors(s.category.id, sig, texts.map { Embedder.embedQuery(context, it) })
            }
        }

        // 3. Candidates per photo, and what the chat model already said about them.
        val vectors = db.categoryVectors(wanted, cfg.dim)
        val categories = vectors.map { it.category }
        val useChecks = llmReady && cfg.llmVerify && ModelCatalog.resolveLlm(cfg)?.spec?.vision == true
        val threshold = cfg.effectiveThreshold
        val candidates = VectorIndex.candidates(
            context, cfg, vectors.map { it.vectors },
            minScore = if (useChecks) threshold * LOOSE_FACTOR else threshold,
        )
        val keys = categories.map { checkKey(it, cfg) }
        val checks = if (useChecks) db.checks(categories.indices.associate { categories[it].id to keys[it] }) else HashMap()
        return SortPlan(categories, keys, candidates, checks, useChecks, threshold)
    }

    /**
     * Decides each photo's category from the plan and stores it.
     *
     * Without photo checks, a photo joins its best candidate. With them, candidates are
     * tried best first: a "yes" from the chat model places the photo, a "no" rules the
     * category out, and a category not checked yet counts only if the vectors alone are
     * confident enough, so sorting stays useful while checks are still running.
     */
    fun apply(context: Context, plan: SortPlan) {
        val c = plan.candidates
        val assigned = Array<Long?>(c.ids.size) { row ->
            var result: Long? = null
            val answers = plan.checks[c.ids[row]]
            for (k in c.category[row].indices) {
                val category = plan.categories[c.category[row][k]]
                val answer = if (plan.useChecks) answers?.get(category.id) else null
                if (answer == true || (answer == null && c.score[row][k] >= plan.threshold)) {
                    result = category.id
                    break
                }
            }
            result
        }
        Db.get(context).applyAssignments(c.ids, assigned)
    }

    private fun expansionKey(category: Category, cfg: Config) =
        "${(category.name + "\u0000" + category.description).hashCode()}|${Llm.modelKey(cfg)}"

    private fun checkKey(category: Category, cfg: Config) = expansionKey(category, cfg)
}
