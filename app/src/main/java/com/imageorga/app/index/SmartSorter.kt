package com.imageorga.app.index

import android.content.Context
import android.util.Log
import com.imageorga.app.data.Db
import com.imageorga.app.ml.Llm
import com.imageorga.app.ml.ModelMissingException

/**
 * Lets the chat model look at photos before they are sorted.
 *
 * For each photo, its candidate categories are tried best first: the model is asked
 * "does this photo belong in this category?" until it says yes or the candidates run out.
 * Every answer is stored, so a photo is only looked at again when a category's text or
 * the chat model changes.
 */
object SmartSorter {
    private const val TAG = "SmartSorter"

    /** Photos that still have at least one question open. */
    fun pendingRows(plan: SortPlan): IntArray {
        if (!plan.useChecks) return IntArray(0)
        val c = plan.candidates
        val rows = ArrayList<Int>()
        for (row in c.ids.indices) {
            val answers = plan.checks[c.ids[row]]
            for (k in c.category[row].indices) {
                val answer = answers?.get(plan.categories[c.category[row][k]].id)
                if (answer == true) break
                if (answer == null) {
                    rows.add(row)
                    break
                }
            }
        }
        return rows.toIntArray()
    }

    /**
     * Works through [rows]. Returns a message when it had to stop because of a problem.
     * [onProgress] is called with the number of photos finished.
     */
    suspend fun run(
        context: Context,
        plan: SortPlan,
        rows: IntArray,
        isStopped: () -> Boolean,
        onProgress: suspend (done: Int) -> Unit,
    ): String? {
        val db = Db.get(context)
        val c = plan.candidates
        var consecutiveFailures = 0
        for ((done, row) in rows.withIndex()) {
            if (isStopped()) return null
            val imageId = c.ids[row]
            try {
                var bytes: ByteArray? = null
                val answers = plan.checks.getOrPut(imageId) { HashMap() }
                for (k in c.category[row].indices) {
                    val index = c.category[row][k]
                    val category = plan.categories[index]
                    var answer = answers[category.id]
                    if (answer == null) {
                        if (bytes == null) bytes = MediaScanner.loadForModel(context, Db.imageUri(imageId), 768)
                        // An unclear reply counts as "no" so the photo is not asked about forever.
                        answer = Llm.belongs(context, bytes, category.name, category.description) ?: false
                        answers[category.id] = answer
                        db.saveCheck(imageId, category.id, plan.checkKeys[index], answer)
                    }
                    if (answer) break
                }
                consecutiveFailures = 0
            } catch (e: ModelMissingException) {
                return "The chat model has not been downloaded yet."
            } catch (t: Throwable) {
                Log.w(TAG, "could not check image $imageId", t)
                consecutiveFailures++
                if (consecutiveFailures >= 3) {
                    return "Photo checks stopped: ${t.message?.lineSequence()?.lastOrNull()?.take(160) ?: t.javaClass.simpleName}"
                }
            }
            onProgress(done + 1)
        }
        return null
    }
}
