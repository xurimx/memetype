package com.umo.memetype.ui

import android.content.Context
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.umo.memetype.R
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TemplateRepository
import com.umo.memetype.meme.TextBox

/**
 * Which screen the panel shows; the host maps it to a height (grid 45%, editor 65% of the screen,
 * TEXT = a plain keyboard for Memetype's own text fields).
 */
enum class PanelMode { GRID, EDITOR, TEXT }

/**
 * Root view returned from onCreateInputView. Fixed height (from [Host.panelHeightPx]);
 * a content area that swaps between the template grid, the editor and the plain-text keyboard.
 * The global actions (switch keyboard, settings, sources, GitHub, donate) sit in the grid's top row.
 */
class KeyboardPanelView(
    context: Context,
    private val host: Host,
    repository: TemplateRepository
) : FrameLayout(context) {

    interface Host {
        fun panelHeightPx(mode: PanelMode): Int
        fun sendMeme(template: MemeTemplate, boxes: List<TextBox>)
        /** Render and save to the device without sending; reports through [showMessage] and [onSendFinished]. */
        fun saveMeme(template: MemeTemplate, boxes: List<TextBox>)
        fun switchBackToPreviousKeyboard()
        fun openSettings()
        fun openSources()
        fun openUrl(url: String)
        fun openDonate()
        // Plain-text mode (the focused field belongs to Memetype itself).
        fun typeText(text: String)
        fun deleteBackward()
        fun pressEnter()
        fun hidePanel()
    }

    private val grid = TemplateGridView(context, repository, object : TemplateGridView.Callbacks {
        override fun onTemplatePicked(template: MemeTemplate) = showEditor(template)
        override fun onSwitchKeyboard() = host.switchBackToPreviousKeyboard()
        override fun onOpenSettings() = host.openSettings()
        override fun onOpenSources() = host.openSources()
        override fun onOpenGithub() = host.openUrl(context.getString(R.string.github_url))
        override fun onOpenDonate() = host.openDonate()
    })

    private val editor = MemeEditorView(context, repository, object : MemeEditorView.Callbacks {
        override fun onBack() = showGrid()
        override fun onSend(template: MemeTemplate, boxes: List<TextBox>) = host.sendMeme(template, boxes)
        override fun onSave(template: MemeTemplate, boxes: List<TextBox>) = host.saveMeme(template, boxes)
    })

    private val textMode = TextModeView(context, object : TextModeView.Callbacks {
        override fun onText(text: String) = host.typeText(text)
        override fun onBackspace() = host.deleteBackward()
        override fun onEnter() = host.pressEnter()
        override fun onSwitchKeyboard() = host.switchBackToPreviousKeyboard()
        override fun onHide() = host.hidePanel()
    })

    private val content = FrameLayout(context)
    private var mode = PanelMode.GRID

    /** Full-panel status message. Toasts from an IME are dropped on Android 13+, so we show our own. */
    private val message = TextView(context).apply {
        visibility = View.GONE
        gravity = Gravity.CENTER
        textSize = 16f
        setTextColor(ContextCompat.getColor(context, R.color.text_primary))
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))
        val pad = (24 * resources.displayMetrics.density).toInt()
        setPadding(pad, pad, pad, pad)
    }
    private var pendingMessageDone: Runnable? = null

    init {
        setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))
        content.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(editor, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        content.addView(textMode, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        editor.visibility = View.GONE
        textMode.visibility = View.GONE

        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        // Last child so bringToFront() covers everything.
        addView(message, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        // The IME window extends under the gesture/navigation bar. Keep the content above it;
        // the background still paints behind the bar (same as Gboard).
        ViewCompat.setOnApplyWindowInsetsListener(this) { _, insets ->
            applyNavigationInset(insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
            insets
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        ViewCompat.getRootWindowInsets(this)?.let {
            applyNavigationInset(it.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom)
        }
    }

    private fun applyNavigationInset(bottom: Int) {
        if (bottom != paddingBottom) {
            setPadding(0, 0, 0, bottom)
            requestLayout()
        }
    }

    /** Force our fixed height (plus the navigation-bar inset) regardless of what the IME frame asks for. */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val h = MeasureSpec.makeMeasureSpec(host.panelHeightPx(mode) + paddingBottom, MeasureSpec.EXACTLY)
        super.onMeasure(widthMeasureSpec, h)
    }

    fun showGrid() {
        setMode(PanelMode.GRID)
        editor.visibility = View.GONE
        editor.reset()
        textMode.visibility = View.GONE
        grid.visibility = View.VISIBLE
        grid.closeSearch()
        grid.refresh()
    }

    fun showEditor(template: MemeTemplate) {
        setMode(PanelMode.EDITOR)
        grid.visibility = View.GONE
        textMode.visibility = View.GONE
        editor.visibility = View.VISIBLE
        editor.setTemplate(template)
    }

    /** Plain keyboard for Memetype's own text fields (Sources sign-in). */
    fun showTextMode() {
        setMode(PanelMode.TEXT)
        grid.visibility = View.GONE
        grid.closeSearch()
        editor.visibility = View.GONE
        editor.reset()
        textMode.visibility = View.VISIBLE
    }

    private fun setMode(newMode: PanelMode) {
        if (newMode == mode) return
        mode = newMode
        requestLayout()
    }

    /**
     * Show [text] over the panel for [durationMs], then run [onDone] (e.g. switch keyboards).
     * Replaces Toast, which the system drops for IMEs without the notification permission.
     */
    fun showMessage(text: CharSequence, durationMs: Long = 1600L, onDone: () -> Unit = {}) {
        cancelMessage()
        message.text = text
        message.visibility = View.VISIBLE
        message.bringToFront()
        val done = Runnable {
            pendingMessageDone = null
            message.visibility = View.GONE
            onDone()
        }
        pendingMessageDone = done
        postDelayed(done, durationMs)
    }

    private fun cancelMessage() {
        pendingMessageDone?.let { removeCallbacks(it) }
        pendingMessageDone = null
        message.visibility = View.GONE
    }

    /** Called from onStartInputView: the focused field changed. */
    fun onTargetChanged(info: EditorInfo?) {
        editor.setTargetAcceptsImages(info?.let { MemeEditorView.acceptsImages(it) } ?: false)
    }

    /** Panel hidden: drop bitmaps, messages and pending work. */
    fun onHidden() {
        cancelMessage()
        setMode(PanelMode.GRID)
        editor.reset()
        grid.closeSearch()
    }

    fun onSendFinished() = editor.setSending(false)

    /** Release background threads. Called when the service replaces or destroys the view. */
    fun destroy() {
        cancelMessage()
        editor.destroy()
        grid.destroy()
    }
}
