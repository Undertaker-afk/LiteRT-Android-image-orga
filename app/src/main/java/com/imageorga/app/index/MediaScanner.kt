package com.imageorga.app.index

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.MediaStore
import com.imageorga.app.data.MediaItem
import java.io.ByteArrayOutputStream
import kotlin.math.max
import kotlin.math.roundToInt

object MediaScanner {

    /** Lists every photo the app is allowed to see. */
    fun scan(context: Context): List<MediaItem> {
        val out = ArrayList<MediaItem>()
        val projection = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.DATE_MODIFIED,
        )
        context.contentResolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI, projection, null, null, null
        )?.use { c ->
            val iId = c.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val iName = c.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val iBucket = c.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
            val iTaken = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)
            val iAdded = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_ADDED)
            val iModified = c.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            while (c.moveToNext()) {
                // DATE_TAKEN is in milliseconds and often missing (screenshots, downloads);
                // DATE_ADDED is in seconds and always present.
                val taken = c.getLong(iTaken).takeIf { it > 0 } ?: (c.getLong(iAdded) * 1000L)
                out.add(
                    MediaItem(
                        id = c.getLong(iId),
                        name = c.getString(iName) ?: "",
                        bucket = c.getString(iBucket) ?: "",
                        dateTaken = taken,
                        dateModified = c.getLong(iModified),
                    )
                )
            }
        }
        return out
    }

    /**
     * Decodes a photo at reduced size and returns it as JPEG bytes for the model.
     * Going through Android's decoder means HEIC, WebP and rotated photos all work, which
     * the model runtime's own decoder would not handle.
     */
    fun loadForModel(context: Context, uri: Uri, maxSide: Int): ByteArray {
        val source = ImageDecoder.createSource(context.contentResolver, uri)
        val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            val w = info.size.width
            val h = info.size.height
            val longest = max(w, h)
            if (longest > maxSide) {
                val scale = maxSide.toFloat() / longest
                decoder.setTargetSize(max(1, (w * scale).roundToInt()), max(1, (h * scale).roundToInt()))
            }
        }
        try {
            val out = ByteArrayOutputStream(96 * 1024)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            return out.toByteArray()
        } finally {
            bitmap.recycle()
        }
    }
}
