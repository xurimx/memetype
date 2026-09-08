package com.umo.memetype.ui

import android.content.Context
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.umo.memetype.R

/**
 * The panel when the focused field belongs to Memetype itself (Sources login, future settings):
 * a plain keyboard, so typing into our own screens never needs another IME. A thin strip on top
 * names the mode and offers "switch keyboard" and "hide".
 */
class TextModeView(context: Context, private val callbacks: Callbacks) : LinearLayout(context) {

    interface Callbacks {
        fun onText(text: String)
        fun onBackspace()
        fun onEnter()
        fun onSwitchKeyboard()
        fun onHide()
    }

    private val keyboard = MiniKeyboardView(context)

    init {
        orientation = VERTICAL
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))

        val strip = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(4), dp(6), 0)
        }
        strip.addView(TextView(context).apply {
            text = context.getString(R.string.text_mode_title)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
        }, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f))
        strip.addView(iconButton(R.drawable.ic_keyboard, R.string.switch_keyboard) { callbacks.onSwitchKeyboard() })
        strip.addView(iconButton(R.drawable.ic_keyboard_hide, R.string.hide_keyboard) { callbacks.onHide() })
        addView(strip, LayoutParams(LayoutParams.MATCH_PARENT, dp(STRIP_DP)))

        keyboard.listener = object : MiniKeyboardView.Listener {
            override fun onText(text: String) = callbacks.onText(text)
            override fun onBackspace() = callbacks.onBackspace()
            override fun onEnter() = callbacks.onEnter()
        }
        addView(keyboard, LayoutParams(LayoutParams.MATCH_PARENT, dp(KEYBOARD_DP)))
    }

    private fun iconButton(icon: Int, description: Int, onClick: () -> Unit): View {
        val button = ImageView(context).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.text_primary))
            background = ContextCompat.getDrawable(context, R.drawable.bg_icon_button)
            contentDescription = context.getString(description)
            scaleType = ImageView.ScaleType.CENTER
            setOnClickListener { onClick() }
        }
        return button.also {
            it.layoutParams = LayoutParams(dp(34), dp(34)).apply { marginStart = dp(6) }
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val STRIP_DP = 40
        const val KEYBOARD_DP = 168
        /** Total content height the host should allot to this mode (without the navigation inset). */
        const val HEIGHT_DP = STRIP_DP + KEYBOARD_DP + 6
    }
}
