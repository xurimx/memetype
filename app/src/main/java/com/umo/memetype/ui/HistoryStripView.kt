package com.umo.memetype.ui

import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.umo.memetype.R
import com.umo.memetype.meme.MemeRenderer
import com.umo.memetype.store.HistoryStore
import java.util.concurrent.ExecutorService

/**
 * Horizontal strip of small previews, one per history entry of the current template.
 * Tap applies the entry, long-press deletes it. Thumbnails are rendered on the caller's
 * io executor from a shared (never recycled) thumbnail base and cached here.
 */
class HistoryStripView(context: Context) : HorizontalScrollView(context) {

    private val row = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        val d = resources.displayMetrics.density
        setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
    }
    private val main = Handler(Looper.getMainLooper())
    private var serial = 0

    private val cache = object : LruCache<String, Bitmap>(CACHE_ENTRIES) {
        override fun entryRemoved(evicted: Boolean, key: String, old: Bitmap, new: Bitmap?) {
            if (old !== new) old.recycle()
        }
    }

    init {
        isHorizontalScrollBarEnabled = false
        addView(row, LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.MATCH_PARENT))
    }

    fun bind(
        key: String,
        entries: List<HistoryStore.Entry>,
        thumbBase: Bitmap?,
        renderer: MemeRenderer,
        io: ExecutorService,
        onPick: (HistoryStore.Entry) -> Unit,
        onDelete: (HistoryStore.Entry) -> Unit
    ) {
        val mySerial = ++serial
        row.removeAllViews()
        val d = resources.displayMetrics.density
        if (entries.isEmpty()) {
            row.addView(TextView(context).apply {
                text = context.getString(R.string.history_empty)
                textSize = 12f
                setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
                setPadding((8 * d).toInt(), 0, (8 * d).toInt(), 0)
            })
            return
        }
        val size = (THUMB_DP * d).toInt()
        entries.forEach { entry ->
            val image = ImageView(context).apply {
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = ContextCompat.getDrawable(context, R.drawable.bg_history_thumb)
                clipToOutline = true
                contentDescription = entry.boxes.joinToString(" / ") { it.text }.ifBlank { null }
            }
            val cell = FrameLayout(context).apply {
                addView(image, LayoutParams(size, size))
                isClickable = true
                isFocusable = true
                setOnClickListener { onPick(entry) }
                setOnLongClickListener { onDelete(entry); true }
            }
            val lp = LinearLayout.LayoutParams(size, size)
            lp.marginEnd = (6 * d).toInt()
            row.addView(cell, lp)

            val cacheKey = "$key:${entry.ts}"
            cache.get(cacheKey)?.let { image.setImageBitmap(it); return@forEach }
            if (thumbBase == null || io.isShutdown) return@forEach
            io.execute {
                val bmp = try {
                    if (thumbBase.isRecycled) null else renderer.render(thumbBase, entry.boxes)
                } catch (e: Exception) { null }
                main.post {
                    if (bmp == null) return@post
                    if (mySerial != serial) { bmp.recycle(); return@post }
                    cache.put(cacheKey, bmp)
                    image.setImageBitmap(bmp)
                }
            }
        }
    }

    /** Drop views and cached bitmaps (template changed or panel hidden). */
    fun clear() {
        serial++
        row.removeAllViews()
        cache.evictAll()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        clear()
    }

    companion object {
        private const val THUMB_DP = 64
        private const val CACHE_ENTRIES = 32
        /** Base bitmap width to request from the repository for strip previews. */
        const val THUMB_BASE_PX = 160
    }
}
