package com.umo.memetype.meme

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ConcurrentHashMap

data class Watermark(val text: String)

data class RenderOptions(val watermark: Watermark? = null)

/**
 * Draws caption boxes onto a template bitmap: each [TextBox] is fitted into its normalised
 * rectangle (shrinking the text until width, height and line limits hold), drawn as an outline
 * plus fill, then an optional watermark goes bottom-right.
 */
class MemeRenderer(private val context: Context) {

    /** A caption laid out for a given image size; [left]/[top] are pixel offsets, [boxRect] the box in pixels. */
    class Fitted(
        val fill: StaticLayout,
        val stroke: StaticLayout?,
        val textSize: Float,
        val left: Float,
        val top: Float,
        val boxRect: RectF
    )

    private val typefaces = ConcurrentHashMap<String, Typeface>()

    /** Renders and returns a new bitmap (caller owns it). [base] is not modified. The box at [skipIndex] is left out. */
    fun render(
        base: Bitmap,
        boxes: List<TextBox>,
        options: RenderOptions = RenderOptions(),
        skipIndex: Int = -1
    ): Bitmap {
        val out = base.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(out)
        boxes.forEachIndexed { i, box ->
            if (i == skipIndex) return@forEachIndexed
            val fitted = fit(out.width, out.height, box) ?: return@forEachIndexed
            draw(canvas, fitted, box.spec)
        }
        options.watermark?.let { drawWatermark(canvas, out.width, out.height, it) }
        return out
    }

    /** Renders to a PNG in cacheDir/memes and returns the file. Prunes old files. */
    fun renderToFile(base: Bitmap, boxes: List<TextBox>, options: RenderOptions = RenderOptions()): File {
        val bmp = render(base, boxes, options)
        try {
            val dir = File(context.cacheDir, "memes").apply { mkdirs() }
            prune(dir)
            val file = File(dir, "meme_${System.currentTimeMillis()}.png")
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file
        } finally {
            bmp.recycle()
        }
    }

    /**
     * Lays out one box for an image of [imageW] x [imageH] pixels, shrinking the text size
     * until the layout fits the box width and height (and maxLines when set). Returns null
     * for blank text. When even the minimum size does not fit, the text overflows unclipped.
     */
    fun fit(imageW: Int, imageH: Int, box: TextBox): Fitted? {
        val spec = box.spec
        val raw = box.text.trim()
        if (raw.isEmpty()) return null
        val text = if (spec.uppercase) raw.uppercase() else raw
        val bw = (spec.w * imageW).toInt().coerceAtLeast(1)
        val bh = spec.h * imageH
        val typeface = typefaceFor(spec.font)
        val alignment = when (spec.align) {
            Align.START -> Layout.Alignment.ALIGN_NORMAL
            Align.CENTER -> Layout.Alignment.ALIGN_CENTER
            Align.END -> Layout.Alignment.ALIGN_OPPOSITE
        }
        var size = minOf(bh, imageH * spec.maxTextSize)
        val minSize = imageH / 30f
        while (true) {
            val layout = build(text, makePaint(size, Paint.Style.FILL, spec.color, typeface), bw, alignment)
            val fits = layout.height <= bh + 0.5f &&
                (spec.maxLines <= 0 || layout.lineCount <= spec.maxLines) &&
                (0 until layout.lineCount).all { layout.getLineWidth(it) <= bw + 0.5f }
            if (fits || size <= minSize) {
                val stroke = if (spec.outlineColor == Color.TRANSPARENT) null
                else build(text, makePaint(size, Paint.Style.STROKE, spec.outlineColor, typeface), bw, alignment)
                val offset = when (spec.vAlign) {
                    VAlign.TOP -> 0f
                    VAlign.CENTER -> (bh - layout.height) / 2f
                    VAlign.BOTTOM -> bh - layout.height
                }
                val left = spec.x * imageW
                val boxTop = spec.y * imageH
                return Fitted(layout, stroke, size, left, boxTop + offset, RectF(left, boxTop, left + bw, boxTop + bh))
            }
            size *= 0.9f
        }
    }

    /** Draws a fitted caption: rotation about the box centre, then outline, then fill. */
    fun draw(canvas: Canvas, fitted: Fitted, spec: TextBoxSpec) {
        canvas.save()
        if (spec.angle != 0f) canvas.rotate(spec.angle, fitted.boxRect.centerX(), fitted.boxRect.centerY())
        canvas.translate(fitted.left, fitted.top)
        fitted.stroke?.draw(canvas)
        fitted.fill.draw(canvas)
        canvas.restore()
    }

    /** Small semi-transparent text in the bottom-right corner, same face and outline as captions. */
    fun drawWatermark(canvas: Canvas, w: Int, h: Int, wm: Watermark) {
        val text = wm.text.trim()
        if (text.isEmpty()) return
        val size = maxOf(10f, w * 0.025f)
        val typeface = typefaceFor(TextBoxSpec.FONT_THICK)
        val fill = makePaint(size, Paint.Style.FILL, Color.WHITE, typeface).apply { alpha = WATERMARK_ALPHA }
        val stroke = makePaint(size, Paint.Style.STROKE, Color.BLACK, typeface).apply { alpha = WATERMARK_ALPHA }
        val margin = w * 0.015f
        val x = w - margin - fill.measureText(text)
        val y = h - margin - fill.descent()
        canvas.drawText(text, x, y, stroke)
        canvas.drawText(text, x, y, fill)
    }

    /** thick = Anton from assets (Impact substitute); sans, condensed, serif, mono = system faces; unknown = thick. */
    fun typefaceFor(key: String): Typeface = typefaces.getOrPut(key) {
        when (key) {
            "sans" -> Typeface.create("sans-serif", Typeface.BOLD)
            "condensed" -> Typeface.create("sans-serif-condensed", Typeface.BOLD)
            "serif" -> Typeface.create("serif", Typeface.BOLD)
            "mono" -> Typeface.create("monospace", Typeface.BOLD)
            else -> loadThick()
        }
    }

    // ---- internals ----------------------------------------------------------------

    private fun build(text: String, paint: TextPaint, width: Int, alignment: Layout.Alignment): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(alignment)
            .setIncludePad(false)
            .setLineSpacing(0f, 0.95f)
            .build()

    private fun makePaint(size: Float, style: Paint.Style, color: Int, typeface: Typeface): TextPaint =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.typeface = typeface
            textSize = size
            this.style = style
            this.color = color
            if (style == Paint.Style.STROKE) {
                strokeWidth = size / 10f
                strokeJoin = Paint.Join.ROUND
                strokeCap = Paint.Cap.ROUND
            }
        }

    private fun loadThick(): Typeface = try {
        Typeface.createFromAsset(context.assets, "fonts/impact.ttf")
    } catch (e: Exception) {
        Typeface.create("sans-serif-condensed", Typeface.BOLD)
    }

    /** Delete old renders, but never anything recent: the receiving app may still be reading it. */
    private fun prune(dir: File) {
        val files = dir.listFiles() ?: return
        if (files.size < MAX_CACHED_FILES) return
        val cutoff = System.currentTimeMillis() - PRUNE_MIN_AGE_MS
        files.sortedBy { it.lastModified() }
            .dropLast(MAX_CACHED_FILES - 1)
            .filter { it.lastModified() < cutoff }
            .forEach { it.delete() }
    }

    companion object {
        const val MAX_OUTPUT_WIDTH = 800
        private const val WATERMARK_ALPHA = 153
        private const val MAX_CACHED_FILES = 10
        private const val PRUNE_MIN_AGE_MS = 10 * 60 * 1000L
    }
}
