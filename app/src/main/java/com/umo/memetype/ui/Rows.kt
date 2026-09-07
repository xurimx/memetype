package com.umo.memetype.ui

import android.content.Context
import android.graphics.Typeface
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.umo.memetype.R

/**
 * Programmatic settings-style rows for the app's activities (no layout XML, no EditText:
 * an EditText in our own activity would summon the meme panel when we are the selected IME).
 */
object Rows {

    class SwitchRow(val root: View, val switch: Switch, val subtitle: TextView)
    class TextRow(val root: View, val title: TextView, val subtitle: TextView)

    fun header(ctx: Context, text: CharSequence): TextView = TextView(ctx).apply {
        this.text = text
        textSize = 13f
        setTypeface(typeface, Typeface.BOLD)
        isAllCaps = true
        setTextColor(ContextCompat.getColor(ctx, R.color.accent))
        val d = ctx.resources.displayMetrics.density
        setPadding((16 * d).toInt(), (20 * d).toInt(), (16 * d).toInt(), (6 * d).toInt())
    }

    fun textRow(ctx: Context, title: CharSequence, subtitle: CharSequence? = null): TextRow {
        val d = ctx.resources.displayMetrics.density
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
        }
        val titleView = TextView(ctx).apply {
            text = title
            textSize = 16f
            setTextColor(primary(ctx))
        }
        val subtitleView = TextView(ctx).apply {
            text = subtitle ?: ""
            textSize = 13f
            setTextColor(secondary(ctx))
            visibility = if (subtitle.isNullOrEmpty()) View.GONE else View.VISIBLE
        }
        root.addView(titleView)
        root.addView(subtitleView)
        return TextRow(root, titleView, subtitleView)
    }

    /** A tappable row with the platform ripple. */
    fun buttonRow(ctx: Context, title: CharSequence, subtitle: CharSequence? = null, onClick: () -> Unit): TextRow {
        val row = textRow(ctx, title, subtitle)
        row.root.apply {
            isClickable = true
            isFocusable = true
            background = selectableBackground(ctx)
            setOnClickListener { onClick() }
        }
        return row
    }

    fun switchRow(
        ctx: Context,
        title: CharSequence,
        subtitle: CharSequence?,
        checked: Boolean,
        onChange: (Boolean) -> Unit
    ): SwitchRow {
        val d = ctx.resources.displayMetrics.density
        val texts = textRow(ctx, title, subtitle)
        texts.root.setPadding(0, 0, (12 * d).toInt(), 0)
        val sw = Switch(ctx).apply {
            isChecked = checked
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            isFocusable = true
            background = selectableBackground(ctx)
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (12 * d).toInt())
            setOnClickListener { sw.toggle() }
        }
        root.addView(texts.root, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        root.addView(sw)
        return SwitchRow(root, sw, texts.subtitle)
    }

    fun divider(ctx: Context): View = View(ctx).apply {
        val d = ctx.resources.displayMetrics.density
        setBackgroundColor(0x1F000000)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, maxOf(1, (0.5f * d).toInt()))
    }

    private fun selectableBackground(ctx: Context) = TypedValue().let { tv ->
        ctx.theme.resolveAttribute(android.R.attr.selectableItemBackground, tv, true)
        ContextCompat.getDrawable(ctx, tv.resourceId)
    }

    private fun primary(ctx: Context) = TypedValue().let { tv ->
        ctx.theme.resolveAttribute(android.R.attr.textColorPrimary, tv, true)
        ContextCompat.getColorStateList(ctx, tv.resourceId)
    }

    private fun secondary(ctx: Context) = TypedValue().let { tv ->
        ctx.theme.resolveAttribute(android.R.attr.textColorSecondary, tv, true)
        ContextCompat.getColorStateList(ctx, tv.resourceId)
    }
}
