package com.imageorga.app.index

import android.content.Context
import com.imageorga.app.core.VectorMath
import com.imageorga.app.data.Category
import com.imageorga.app.data.Db
import com.imageorga.app.data.Settings
import com.imageorga.app.ml.Embedder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Sorts photos into the user's categories by comparing photo vectors with category text. */
object Categorizer {
    private val mutex = Mutex()

    /**
     * Embeds any category whose text is new or changed, then re-sorts every indexed photo
     * that the user has not placed by hand. Cheap enough to run after every change.
     */
    suspend fun run(context: Context) = mutex.withLock {
        withContext(Dispatchers.Default) {
            val app = context.applicationContext
            val db = Db.get(app)
            val cfg = Settings.current
            val sig = cfg.signature

            for (c in db.categoriesNeedingVector(sig)) {
                db.saveCategoryVectors(c.id, sig, embed(app, c))
            }

            val cats = db.categoryVectors(sig, cfg.dim)
            val (ids, assigned) = VectorIndex.assign(
                app, cfg,
                LongArray(cats.size) { cats[it].category.id },
                cats.map { it.vectors },
            )
            db.applyAssignments(ids, assigned)
        }
    }

    /** One vector per text the category is matched with; see [VectorMath.categoryTexts]. */
    private suspend fun embed(context: Context, c: Category): List<FloatArray> =
        VectorMath.categoryTexts(c.name, c.description).map { Embedder.embedQuery(context, it) }
}
