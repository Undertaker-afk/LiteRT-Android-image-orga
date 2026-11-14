package com.imageorga.app.data

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** A photo known to the app. [id] is the MediaStore id. */
data class ImageRow(
    val id: Long,
    val name: String,
    val bucket: String,
    val dateTaken: Long,
    val categoryId: Long?,
    /** True when the user placed the photo by hand; automatic sorting leaves it alone. */
    val manual: Boolean,
) {
    val uri: Uri get() = Db.imageUri(id)
}

data class Category(
    val id: Long,
    val name: String,
    val description: String,
)

/** A photo found on the device by the media scan. */
data class MediaItem(val id: Long, val name: String, val bucket: String, val dateTaken: Long, val dateModified: Long)

class SyncResult(val added: Int, val removed: Int, val changed: Int)

/** A category together with the vectors its texts were embedded to. */
class CategoryVector(val category: Category, val vectors: List<FloatArray>)

class Db private constructor(context: Context) :
    SQLiteOpenHelper(context.applicationContext, "imageorga.db", null, 1) {

    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE images(
                id INTEGER PRIMARY KEY,
                name TEXT NOT NULL,
                bucket TEXT NOT NULL,
                date_taken INTEGER NOT NULL,
                date_modified INTEGER NOT NULL,
                sig TEXT,
                emb BLOB,
                category_id INTEGER,
                manual INTEGER NOT NULL DEFAULT 0,
                failed INTEGER NOT NULL DEFAULT 0
            )"""
        )
        db.execSQL("CREATE INDEX images_sig ON images(sig)")
        db.execSQL("CREATE INDEX images_cat ON images(category_id)")
        db.execSQL(
            """CREATE TABLE categories(
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                name TEXT NOT NULL,
                description TEXT NOT NULL,
                sig TEXT,
                emb BLOB
            )"""
        )
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit

    // ---- media sync -------------------------------------------------------------------

    /** Brings the image table in line with what is on the device right now. */
    fun syncMedia(items: List<MediaItem>): SyncResult {
        val db = writableDatabase
        val existing = HashMap<Long, Long>()
        db.rawQuery("SELECT id, date_modified FROM images", null).use { c ->
            while (c.moveToNext()) existing[c.getLong(0)] = c.getLong(1)
        }
        var added = 0
        var changed = 0
        var removed = 0
        db.beginTransaction()
        try {
            val seen = HashSet<Long>(items.size * 2)
            for (m in items) {
                seen.add(m.id)
                val old = existing[m.id]
                if (old == null) {
                    db.insert("images", null, ContentValues().apply {
                        put("id", m.id)
                        put("name", m.name)
                        put("bucket", m.bucket)
                        put("date_taken", m.dateTaken)
                        put("date_modified", m.dateModified)
                    })
                    added++
                } else if (old != m.dateModified) {
                    // The file was edited: its old vector no longer describes it.
                    db.execSQL(
                        "UPDATE images SET name=?, bucket=?, date_taken=?, date_modified=?, sig=NULL, emb=NULL, failed=0 WHERE id=?",
                        arrayOf<Any>(m.name, m.bucket, m.dateTaken, m.dateModified, m.id)
                    )
                    changed++
                }
            }
            for (id in existing.keys) {
                if (id !in seen) {
                    db.delete("images", "id=?", arrayOf(id.toString()))
                    removed++
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (added + changed + removed > 0) bump()
        return SyncResult(added, removed, changed)
    }

    // ---- embeddings -------------------------------------------------------------------

    /** Ids of photos that still need a vector for [sig], newest first. */
    fun pendingIds(sig: String): LongArray {
        val out = ArrayList<Long>()
        readableDatabase.rawQuery(
            "SELECT id FROM images WHERE (emb IS NULL OR sig IS NULL OR sig != ?) AND failed < $MAX_FAILURES ORDER BY date_taken DESC",
            arrayOf(sig)
        ).use { c -> while (c.moveToNext()) out.add(c.getLong(0)) }
        return out.toLongArray()
    }

    fun indexedCount(sig: String): Int = count("SELECT COUNT(*) FROM images WHERE emb IS NOT NULL AND sig = ?", sig)

    fun totalCount(): Int = count("SELECT COUNT(*) FROM images")

    fun failedCount(): Int = count("SELECT COUNT(*) FROM images WHERE failed >= $MAX_FAILURES")

    private fun count(sql: String, vararg args: String): Int =
        readableDatabase.rawQuery(sql, args).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    fun saveEmbedding(id: Long, sig: String, vector: FloatArray) {
        writableDatabase.execSQL(
            "UPDATE images SET sig=?, emb=?, failed=0 WHERE id=?",
            arrayOf<Any>(sig, toBlob(vector), id)
        )
    }

    fun markFailed(id: Long) {
        writableDatabase.execSQL("UPDATE images SET failed = failed + 1 WHERE id=?", arrayOf<Any>(id))
    }

    /** Lets photos that failed before be tried again and drops all vectors. */
    fun resetIndex() {
        writableDatabase.execSQL("UPDATE images SET sig=NULL, emb=NULL, failed=0, category_id = CASE WHEN manual=1 THEN category_id ELSE NULL END")
        bump()
    }

    /** All vectors stored for [sig] as (ids, flat row-major matrix). */
    fun loadVectors(sig: String, dim: Int): Pair<LongArray, FloatArray> {
        val db = readableDatabase
        val n = indexedCount(sig)
        var ids = LongArray(n)
        var data = FloatArray(n * dim)
        var i = 0
        db.rawQuery("SELECT id, emb FROM images WHERE emb IS NOT NULL AND sig = ?", arrayOf(sig)).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(1)
                if (blob.size != dim * 4) continue
                if (i == ids.size) {
                    ids = ids.copyOf(maxOf(16, i * 2))
                    data = data.copyOf(ids.size * dim)
                }
                ids[i] = c.getLong(0)
                ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer().get(data, i * dim, dim)
                i++
            }
        }
        return ids.copyOf(i) to data.copyOf(i * dim)
    }

    // ---- images -----------------------------------------------------------------------

    fun allImages(): List<ImageRow> {
        val out = ArrayList<ImageRow>()
        readableDatabase.rawQuery(
            "SELECT id, name, bucket, date_taken, category_id, manual FROM images ORDER BY date_taken DESC, id DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                out.add(
                    ImageRow(
                        id = c.getLong(0),
                        name = c.getString(1),
                        bucket = c.getString(2),
                        dateTaken = c.getLong(3),
                        categoryId = if (c.isNull(4)) null else c.getLong(4),
                        manual = c.getInt(5) != 0,
                    )
                )
            }
        }
        return out
    }

    /** Ids the user sorted by hand; automatic sorting skips them. */
    fun manualIds(): Set<Long> {
        val out = HashSet<Long>()
        readableDatabase.rawQuery("SELECT id FROM images WHERE manual=1", null).use { c ->
            while (c.moveToNext()) out.add(c.getLong(0))
        }
        return out
    }

    /** Writes automatic sorting results. [categoryIds] holds null for "unsorted". */
    fun applyAssignments(ids: LongArray, categoryIds: Array<Long?>) {
        val db = writableDatabase
        val current = HashMap<Long, Long?>()
        db.rawQuery("SELECT id, category_id FROM images WHERE manual=0", null).use { c ->
            while (c.moveToNext()) current[c.getLong(0)] = if (c.isNull(1)) null else c.getLong(1)
        }
        var changes = 0
        db.beginTransaction()
        try {
            val stmt = db.compileStatement("UPDATE images SET category_id=? WHERE id=? AND manual=0")
            for (i in ids.indices) {
                val id = ids[i]
                if (!current.containsKey(id) || current[id] == categoryIds[i]) continue
                val cat = categoryIds[i]
                if (cat == null) stmt.bindNull(1) else stmt.bindLong(1, cat)
                stmt.bindLong(2, id)
                stmt.executeUpdateDelete()
                changes++
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if (changes > 0) bump()
    }

    /** Places a photo by hand ([categoryId] null = keep it out of every category). */
    fun setManual(id: Long, categoryId: Long?) {
        writableDatabase.execSQL(
            "UPDATE images SET manual=1, category_id=? WHERE id=?",
            arrayOf<Any?>(categoryId, id)
        )
        bump()
    }

    /** Hands a photo back to automatic sorting. */
    fun clearManual(id: Long) {
        writableDatabase.execSQL("UPDATE images SET manual=0 WHERE id=?", arrayOf<Any>(id))
        bump()
    }

    // ---- categories -------------------------------------------------------------------

    fun categories(): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery("SELECT id, name, description FROM categories ORDER BY name COLLATE NOCASE", null).use { c ->
            while (c.moveToNext()) out.add(Category(c.getLong(0), c.getString(1), c.getString(2)))
        }
        return out
    }

    fun insertCategory(name: String, description: String): Long {
        val id = writableDatabase.insert("categories", null, ContentValues().apply {
            put("name", name)
            put("description", description)
        })
        bump()
        return id
    }

    fun updateCategory(id: Long, name: String, description: String) {
        // The text changed, so the stored vector is stale.
        writableDatabase.execSQL(
            "UPDATE categories SET name=?, description=?, sig=NULL, emb=NULL WHERE id=?",
            arrayOf<Any>(name, description, id)
        )
        bump()
    }

    fun deleteCategory(id: Long) {
        val db = writableDatabase
        db.beginTransaction()
        try {
            db.delete("categories", "id=?", arrayOf(id.toString()))
            db.execSQL("UPDATE images SET category_id=NULL, manual=0 WHERE category_id=?", arrayOf<Any>(id))
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        bump()
    }

    /** Categories whose text has not been embedded for [sig] yet. */
    fun categoriesNeedingVector(sig: String): List<Category> {
        val out = ArrayList<Category>()
        readableDatabase.rawQuery(
            "SELECT id, name, description FROM categories WHERE emb IS NULL OR sig IS NULL OR sig != ?", arrayOf(sig)
        ).use { c -> while (c.moveToNext()) out.add(Category(c.getLong(0), c.getString(1), c.getString(2))) }
        return out
    }

    /** [vectors] holds one or more vectors of equal length, stored back to back. */
    fun saveCategoryVectors(id: Long, sig: String, vectors: List<FloatArray>) {
        val flat = FloatArray(vectors.sumOf { it.size })
        var off = 0
        for (v in vectors) {
            System.arraycopy(v, 0, flat, off, v.size)
            off += v.size
        }
        writableDatabase.execSQL("UPDATE categories SET sig=?, emb=? WHERE id=?", arrayOf<Any>(sig, toBlob(flat), id))
    }

    fun categoryVectors(sig: String, dim: Int): List<CategoryVector> {
        val out = ArrayList<CategoryVector>()
        readableDatabase.rawQuery(
            "SELECT id, name, description, emb FROM categories WHERE emb IS NOT NULL AND sig = ? ORDER BY id", arrayOf(sig)
        ).use { c ->
            while (c.moveToNext()) {
                val blob = c.getBlob(3)
                if (blob.isEmpty() || blob.size % (dim * 4) != 0) continue
                val buf = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
                val vectors = List(blob.size / (dim * 4)) { FloatArray(dim).also { v -> buf.get(v) } }
                out.add(CategoryVector(Category(c.getLong(0), c.getString(1), c.getString(2)), vectors))
            }
        }
        return out
    }

    companion object {
        /** A photo that cannot be read this many times is skipped until the index is rebuilt. */
        const val MAX_FAILURES = 3

        @Volatile
        private var instance: Db? = null

        fun get(context: Context): Db = instance ?: synchronized(this) {
            instance ?: Db(context).also { instance = it }
        }

        private val versionState = MutableStateFlow(0L)

        /** Increases whenever stored data changes; screens reload when it does. */
        val version: StateFlow<Long> get() = versionState

        fun bump() {
            versionState.value = versionState.value + 1
        }

        fun imageUri(id: Long): Uri =
            ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)

        private fun toBlob(v: FloatArray): ByteArray {
            val bb = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN)
            bb.asFloatBuffer().put(v)
            return bb.array()
        }
    }
}
