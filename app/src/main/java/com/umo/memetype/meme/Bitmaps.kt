package com.umo.memetype.meme

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.InputStream

/** Bitmap decoding shared by the repository and the import screen. */
object Bitmaps {

    /**
     * Two-pass decode: bounds first, then inSampleSize so the result is narrower than
     * 2 x [maxWidth] (a 0 width, seen during early measure passes, is treated as 1).
     * Returns null when the stream cannot be opened or decoded.
     */
    fun decodeScaled(open: () -> InputStream?, maxWidth: Int): Bitmap? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0) return null
            val limit = maxOf(1, maxWidth)
            var sample = 1
            while (bounds.outWidth / (sample * 2) >= limit) sample *= 2
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    /** Width and height without decoding pixels; null if unreadable. */
    fun bounds(open: () -> InputStream?): Pair<Int, Int>? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            (open() ?: return null).use { BitmapFactory.decodeStream(it, null, o) }
            if (o.outWidth > 0 && o.outHeight > 0) o.outWidth to o.outHeight else null
        } catch (e: Exception) {
            null
        }
    }
}
