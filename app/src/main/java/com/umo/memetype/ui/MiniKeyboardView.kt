package com.umo.memetype.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.umo.memetype.R

/**
 * Compact QWERTY drawn directly on a Canvas (no child views → cheap to inflate,
 * which matters because the IME is loaded into every text field).
 *
 * Layers: letters (with shift) and symbols. Bottom row: layer toggle, space, enter.
 */
class MiniKeyboardView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    interface Listener {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
    }

    var listener: Listener? = null

    private data class Key(
        val code: String,          // text to insert, or one of the SPECIAL_* codes
        val label: String,
        val widthUnits: Float = 1f,
        val special: Boolean = false
    ) {
        val rect = RectF()
    }

    private var rows: List<List<Key>> = emptyList()
    private var shifted = true          // memes shout; start in caps
    private var capsLock = false
    private var symbols = false
    private var pressed: Key? = null
    private var lastShiftTap = 0L

    private val keyPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_bg) }
    private val specialPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_special) }
    private val pressedPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.key_bg_pressed) }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.accent) }
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.key_text)
        textAlign = Paint.Align.CENTER
    }

    private val density = resources.displayMetrics.density
    private val gap = 3f * density
    private val radius = 6f * density

    private val handler = Handler(Looper.getMainLooper())
    private val repeatBackspace = object : Runnable {
        override fun run() {
            if (pressed?.code == CODE_BACKSPACE) {
                listener?.onBackspace()
                handler.postDelayed(this, 50)
            }
        }
    }

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))
        rebuildRows()
    }

    // ---- layout -----------------------------------------------------------------

    private fun rebuildRows() {
        val r1: String; val r2: String; val r3: String
        if (symbols) {
            r1 = "1234567890"; r2 = "@#\$%&-+()"; r3 = "*\"':;!?/"
        } else {
            r1 = "qwertyuiop"; r2 = "asdfghjkl"; r3 = "zxcvbnm"
        }
        fun letters(s: String) = s.map { c ->
            val ch = if (!symbols && (shifted || capsLock)) c.uppercaseChar() else c
            Key(ch.toString(), ch.toString())
        }
        val shiftLabel = when {
            symbols -> "=<"
            capsLock -> "⇪"
            shifted -> "⇧"
            else -> "⇧"
        }
        rows = listOf(
            letters(r1),
            letters(r2),
            listOf(Key(CODE_SHIFT, shiftLabel, 1.5f, true)) + letters(r3) + listOf(Key(CODE_BACKSPACE, "⌫", 1.5f, true)),
            listOf(
                Key(CODE_SYMBOLS, if (symbols) "ABC" else "123", 2f, true),
                Key(" ", "space", 5f),
                Key(CODE_ENTER, "↵", 2f, true)
            )
        )
        layoutKeys()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val desiredH = (4 * 42 * density).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(desiredH, MeasureSpec.getSize(heightMeasureSpec))
            else -> desiredH
        }
        setMeasuredDimension(w, h)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutKeys()
    }

    private fun layoutKeys() {
        if (rows.isEmpty() || width == 0 || height == 0) return
        val rowH = height / rows.size.toFloat()
        // 10 units per row is the widest row; narrower rows are centered.
        val unit = width / 10f
        rows.forEachIndexed { ri, row ->
            val totalUnits = row.sumOf { it.widthUnits.toDouble() }.toFloat()
            var x = (width - totalUnits * unit) / 2f
            val top = ri * rowH
            row.forEach { k ->
                k.rect.set(x + gap / 2, top + gap / 2, x + k.widthUnits * unit - gap / 2, top + rowH - gap / 2)
                x += k.widthUnits * unit
            }
        }
        textPaint.textSize = rowH * 0.42f
    }

    // ---- drawing ----------------------------------------------------------------

    override fun onDraw(canvas: Canvas) {
        if (rows.first().first().rect.isEmpty) layoutKeys()
        for (row in rows) for (k in row) {
            val paint = when {
                k === pressed -> pressedPaint
                k.code == CODE_ENTER -> accentPaint
                k.special -> specialPaint
                else -> keyPaint
            }
            canvas.drawRoundRect(k.rect, radius, radius, paint)
            val baseline = k.rect.centerY() - (textPaint.descent() + textPaint.ascent()) / 2
            val savedSize = textPaint.textSize
            if (k.label.length > 1 && k.code != CODE_ENTER) textPaint.textSize = savedSize * 0.7f
            canvas.drawText(k.label, k.rect.centerX(), baseline, textPaint)
            textPaint.textSize = savedSize
        }
    }

    // ---- touch ------------------------------------------------------------------

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                pressed = keyAt(e.x, e.y)
                if (pressed?.code == CODE_BACKSPACE) {
                    listener?.onBackspace()
                    handler.postDelayed(repeatBackspace, 400)
                }
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                invalidate()
            }
            MotionEvent.ACTION_MOVE -> {
                val k = keyAt(e.x, e.y)
                if (k !== pressed) {
                    handler.removeCallbacks(repeatBackspace)
                    pressed = k
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(repeatBackspace)
                pressed?.let { if (it.code != CODE_BACKSPACE) fire(it) }
                pressed = null
                invalidate()
            }
            MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(repeatBackspace)
                pressed = null
                invalidate()
            }
        }
        return true
    }

    private fun keyAt(x: Float, y: Float): Key? {
        for (row in rows) for (k in row) if (k.rect.contains(x, y)) return k
        return null
    }

    private fun fire(k: Key) {
        when (k.code) {
            CODE_SHIFT -> {
                if (symbols) return
                val now = System.currentTimeMillis()
                if (now - lastShiftTap < 300) {         // double tap → caps lock
                    capsLock = !capsLock
                    shifted = capsLock
                } else {
                    shifted = !shifted
                    if (!shifted) capsLock = false
                }
                lastShiftTap = now
                rebuildRows()
            }
            CODE_SYMBOLS -> { symbols = !symbols; rebuildRows() }
            CODE_ENTER -> listener?.onEnter()
            CODE_BACKSPACE -> listener?.onBackspace()
            else -> {
                listener?.onText(k.code)
                if (shifted && !capsLock && !symbols && k.code != " ") {
                    shifted = false
                    rebuildRows()
                }
            }
        }
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(repeatBackspace)
        super.onDetachedFromWindow()
    }

    private companion object {
        const val CODE_SHIFT = " shift"
        const val CODE_BACKSPACE = " bksp"
        const val CODE_SYMBOLS = " sym"
        const val CODE_ENTER = " enter"
    }
}
