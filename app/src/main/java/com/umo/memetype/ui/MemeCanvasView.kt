package com.umo.memetype.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PointF
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.umo.memetype.R
import com.umo.memetype.meme.MemeRenderer
import com.umo.memetype.meme.TextBox
import com.umo.memetype.meme.TextBoxSpec
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * The editor canvas: draws the baked bitmap (template + every box except the selected one +
 * watermark) fit-centred, draws the selected box live through the same renderer, and turns
 * touches into normalised box changes (tap to select, drag to move, corner handles or pinch
 * to resize). Owns no bitmaps and no threads, so the import screen can reuse it.
 */
class MemeCanvasView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onBoxTapped(index: Int)
        /** [gestureEnded] is false while a finger is down and true once for the final geometry. */
        fun onBoxChanged(index: Int, spec: TextBoxSpec, gestureEnded: Boolean)
    }

    var listener: Listener? = null
    var renderer: MemeRenderer? = null
    var editable: Boolean = true
        set(value) {
            field = value
            invalidate()
        }

    private var baked: Bitmap? = null
    private var boxes: List<TextBox> = emptyList()
    private var selected = -1
    private val imageRect = RectF()
    private val matrix = Matrix()
    private var fitted: MemeRenderer.Fitted? = null
    private var fittedDirty = true

    private val density = resources.displayMetrics.density
    private val handleRadius = 6f * density
    private val handleHitRadius = 14f * density
    private val touchSlop = 8f * density

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1f * density
        color = ContextCompat.getColor(context, R.color.overlay_box)
        pathEffect = DashPathEffect(floatArrayOf(4f * density, 4f * density), 0f)
    }
    private val selectedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * density
        color = ContextCompat.getColor(context, R.color.accent)
    }
    private val handleFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = ContextCompat.getColor(context, R.color.overlay_handle)
    }
    private val handleStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.5f * density
        color = ContextCompat.getColor(context, R.color.accent)
    }

    private enum class Mode { NONE, MOVE, RESIZE, PINCH }

    private var mode = Mode.NONE
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var startSpec: TextBoxSpec? = null
    private var resizeCorner = -1
    private var pinchStartDist = 0f

    /** The bitmap to show behind the live box. The caller keeps ownership and recycles it after replacing it. */
    fun setBaked(bitmap: Bitmap?) {
        baked = bitmap
        computeImageRect()
        fittedDirty = true
        invalidate()
    }

    /** Current boxes (copied) and the selected index, or -1 for none. */
    fun setBoxes(boxes: List<TextBox>, selected: Int) {
        this.boxes = boxes.toList()
        this.selected = selected
        fittedDirty = true
        invalidate()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        computeImageRect()
        fittedDirty = true
    }

    private fun computeImageRect() {
        val b = baked ?: return
        val vw = width - paddingLeft - paddingRight
        val vh = height - paddingTop - paddingBottom
        if (vw <= 0 || vh <= 0 || b.isRecycled) return
        val scale = min(vw / b.width.toFloat(), vh / b.height.toFloat())
        val w = b.width * scale
        val h = b.height * scale
        val left = paddingLeft + (vw - w) / 2f
        val top = paddingTop + (vh - h) / 2f
        imageRect.set(left, top, left + w, top + h)
        matrix.setRectToRect(RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()), imageRect, Matrix.ScaleToFit.CENTER)
    }

    override fun onDraw(canvas: Canvas) {
        val b = baked ?: return
        if (b.isRecycled) return
        canvas.drawBitmap(b, matrix, bitmapPaint)

        val sel = selected
        val r = renderer
        if (r != null && sel in boxes.indices) {
            if (fittedDirty) {
                fitted = r.fit(b.width, b.height, boxes[sel])
                fittedDirty = false
            }
            fitted?.let { f ->
                canvas.save()
                canvas.concat(matrix)
                r.draw(canvas, f, boxes[sel].spec)
                canvas.restore()
            }
        }

        if (!editable) return
        boxes.forEachIndexed { i, box ->
            val rect = boxRectPx(box.spec)
            if (i == sel) {
                canvas.drawRect(rect, selectedPaint)
                for (c in corners(rect)) {
                    canvas.drawCircle(c.x, c.y, handleRadius, handleFill)
                    canvas.drawCircle(c.x, c.y, handleRadius, handleStroke)
                }
            } else {
                canvas.drawRect(rect, guidePaint)
            }
        }
    }

    // ---- touch ------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!editable || baked == null) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x
                downY = e.y
                moved = false
                mode = Mode.NONE
                startSpec = null
                val sel = selected
                if (sel in boxes.indices) {
                    val rect = boxRectPx(boxes[sel].spec)
                    val corner = hitCorner(rect, e.x, e.y)
                    if (corner >= 0) {
                        mode = Mode.RESIZE
                        resizeCorner = corner
                        startSpec = boxes[sel].spec
                    } else if (rect.contains(e.x, e.y)) {
                        mode = Mode.MOVE
                        startSpec = boxes[sel].spec
                    }
                }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                if (e.pointerCount == 2 && selected in boxes.indices) {
                    mode = Mode.PINCH
                    startSpec = boxes[selected].spec
                    pinchStartDist = dist(e)
                    moved = true
                }
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                onMove(e)
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (mode == Mode.PINCH) {
                    commit()
                    mode = Mode.NONE
                    startSpec = null
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (moved) commit() else if (mode == Mode.NONE || mode == Mode.MOVE) tapAt(e.x, e.y)
                mode = Mode.NONE
                startSpec = null
                return true
            }
            MotionEvent.ACTION_CANCEL -> {
                if (moved) commit()
                mode = Mode.NONE
                startSpec = null
                return true
            }
        }
        return super.onTouchEvent(e)
    }

    private fun onMove(e: MotionEvent) {
        val sel = selected
        val start = startSpec ?: return
        if (sel !in boxes.indices || imageRect.isEmpty) return
        when (mode) {
            Mode.MOVE -> {
                if (!moved && abs(e.x - downX) < touchSlop && abs(e.y - downY) < touchSlop) return
                moved = true
                val dx = (e.x - downX) / imageRect.width()
                val dy = (e.y - downY) / imageRect.height()
                update(sel, start.copy(x = start.x + dx, y = start.y + dy).clamped())
            }
            Mode.RESIZE -> {
                moved = true
                update(sel, resized(start, resizeCorner, e.x, e.y))
            }
            Mode.PINCH -> {
                if (e.pointerCount < 2 || pinchStartDist <= 0f) return
                val s = (dist(e) / pinchStartDist).coerceIn(0.2f, 5f)
                val cx = start.x + start.w / 2f
                val cy = start.y + start.h / 2f
                val nw = (start.w * s).coerceIn(TextBoxSpec.MIN_SIZE, 1f)
                val nh = (start.h * s).coerceIn(TextBoxSpec.MIN_SIZE, 1f)
                update(sel, start.copy(x = cx - nw / 2f, y = cy - nh / 2f, w = nw, h = nh).clamped())
            }
            Mode.NONE -> Unit
        }
    }

    private fun update(index: Int, spec: TextBoxSpec) {
        boxes = boxes.toMutableList().also { it[index] = it[index].copy(spec = spec) }
        fittedDirty = true
        invalidate()
        listener?.onBoxChanged(index, spec, false)
    }

    private fun commit() {
        val sel = selected
        if (sel in boxes.indices) listener?.onBoxChanged(sel, boxes[sel].spec, true)
        moved = false
    }

    private fun tapAt(x: Float, y: Float) {
        val hit = boxes.indices.lastOrNull { boxRectPx(boxes[it].spec).contains(x, y) }
        if (hit != null) listener?.onBoxTapped(hit)
    }

    /** New spec for a corner drag; the opposite corner stays fixed. Corners: 0 TL, 1 TR, 2 BR, 3 BL. */
    private fun resized(start: TextBoxSpec, corner: Int, px: Float, py: Float): TextBoxSpec {
        val nx = ((px - imageRect.left) / imageRect.width()).coerceIn(0f, 1f)
        val ny = ((py - imageRect.top) / imageRect.height()).coerceIn(0f, 1f)
        var left = start.x
        var top = start.y
        var right = start.x + start.w
        var bottom = start.y + start.h
        when (corner) {
            0 -> { left = nx; top = ny }
            1 -> { right = nx; top = ny }
            2 -> { right = nx; bottom = ny }
            3 -> { left = nx; bottom = ny }
        }
        val minSize = TextBoxSpec.MIN_SIZE
        if (right - left < minSize) {
            if (corner == 0 || corner == 3) left = right - minSize else right = left + minSize
        }
        if (bottom - top < minSize) {
            if (corner == 0 || corner == 1) top = bottom - minSize else bottom = top + minSize
        }
        return start.copy(x = left, y = top, w = right - left, h = bottom - top).clamped()
    }

    private fun boxRectPx(spec: TextBoxSpec): RectF = RectF(
        imageRect.left + spec.x * imageRect.width(),
        imageRect.top + spec.y * imageRect.height(),
        imageRect.left + (spec.x + spec.w) * imageRect.width(),
        imageRect.top + (spec.y + spec.h) * imageRect.height()
    )

    private fun corners(rect: RectF): List<PointF> = listOf(
        PointF(rect.left, rect.top),
        PointF(rect.right, rect.top),
        PointF(rect.right, rect.bottom),
        PointF(rect.left, rect.bottom)
    )

    private fun hitCorner(rect: RectF, x: Float, y: Float): Int {
        corners(rect).forEachIndexed { i, c ->
            if (hypot(x - c.x, y - c.y) <= handleHitRadius) return i
        }
        return -1
    }

    private fun dist(e: MotionEvent): Float =
        hypot(e.getX(0) - e.getX(1), e.getY(0) - e.getY(1))
}
