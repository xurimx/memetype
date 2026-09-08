package com.umo.memetype

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.inputmethodservice.InputMethodService
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.RenderOptions
import com.umo.memetype.meme.TemplateRepository
import com.umo.memetype.meme.TextBox
import com.umo.memetype.meme.Watermark
import com.umo.memetype.share.MemeSaver
import com.umo.memetype.share.MemeSender
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.store.HistoryStore
import com.umo.memetype.ui.KeyboardPanelView
import com.umo.memetype.ui.PanelMode
import com.umo.memetype.ui.TextModeView
import android.view.KeyEvent
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * The IME. Owns one [KeyboardPanelView] (grid <-> editor) sized to a fixed
 * fraction of the screen so the chat app stays visible above it.
 */
class MemeInputMethodService : InputMethodService(), KeyboardPanelView.Host {

    private var panel: KeyboardPanelView? = null
    private lateinit var prefs: AppPrefs
    private lateinit var repository: TemplateRepository
    private lateinit var sender: MemeSender
    private lateinit var history: HistoryStore
    private lateinit var io: ExecutorService
    private val main = Handler(Looper.getMainLooper())
    private var warnedSaveUnavailable = false

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        repository = TemplateRepository(this, prefs)
        sender = MemeSender(this, repository)
        history = HistoryStore(this)
        io = Executors.newSingleThreadExecutor()
    }

    override fun onCreateInputView(): View {
        panel?.destroy()
        warnedSaveUnavailable = false
        return KeyboardPanelView(this, this, repository).also { panel = it }
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Fresh field (or re-shown): the template grid — unless the field is one of Memetype's own
        // (Sources sign-in), where the panel becomes a plain keyboard instead.
        if (!restarting) {
            if (info?.packageName == packageName) panel?.showTextMode() else panel?.showGrid()
        }
        panel?.onTargetChanged(info)
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        panel?.onHidden()
    }

    /** Never go fullscreen (the default in landscape): the whole point is staying in the chat. */
    override fun onEvaluateFullscreenMode(): Boolean = false

    /**
     * Always show the panel, even when the system thinks a hardware keyboard is attached
     * (emulators always report one; a Bluetooth keyboard on a phone would otherwise hide us).
     * The panel is a picker, not a text keyboard, so a physical keyboard is no substitute.
     */
    override fun onEvaluateInputViewShown(): Boolean {
        super.onEvaluateInputViewShown() // keeps the settings observer of the framework registered
        return true
    }

    override fun onDestroy() {
        panel?.destroy()
        panel = null
        io.shutdownNow()
        super.onDestroy()
    }

    // ---- KeyboardPanelView.Host -------------------------------------------------

    override fun panelHeightPx(mode: PanelMode): Int {
        val dm = resources.displayMetrics
        if (mode == PanelMode.TEXT) return (TextModeView.HEIGHT_DP * dm.density).toInt()
        val isLandscape = dm.widthPixels > dm.heightPixels
        // Portrait: a fraction of the screen height (the editor needs room for the canvas).
        // Landscape: the same share of the (short) height for both modes. Growing the panel
        // in landscape makes host apps close the IME session (verified with Contacts: 80% left
        // too little room for the focused field), so grid and editor share one height there.
        val fraction = when (mode) {
            PanelMode.GRID -> if (isLandscape) LANDSCAPE_FRACTION_GRID else PANEL_FRACTION_GRID
            PanelMode.EDITOR -> if (isLandscape) LANDSCAPE_FRACTION_EDITOR else PANEL_FRACTION_EDITOR
            PanelMode.TEXT -> 0f // handled above
        }
        val basis = if (isLandscape) dm.heightPixels else maxOf(dm.heightPixels, dm.widthPixels)
        return (basis * fraction).toInt()
    }

    override fun sendMeme(template: MemeTemplate, boxes: List<TextBox>) {
        // Render off the main thread (decode + PNG encode + disk write), deliver on it
        // because the InputConnection must be used from the main thread.
        val options = renderOptions()
        val saveToo = prefs.saveSentMemes
        io.execute {
            val rendered = sender.render(template, boxes, options)
            val saved = if (rendered != null && saveToo) {
                MemeSaver.save(this, prefs, rendered.file, rendered.file.name)
            } else null
            main.post {
                val p = panel ?: return@post
                p.onSendFinished()
                if (rendered == null) {
                    p.showMessage(getString(R.string.toast_render_failed))
                    return@post
                }
                val result = sender.deliver(rendered.uri, currentInputEditorInfo, currentInputConnection)
                repository.markRecent(template.key)
                remember(template, boxes, saved)
                val saveNote = saveMessage(saved)
                val finish = {
                    p.showGrid()
                    switchBackToPreviousKeyboard()
                }
                when {
                    // Toasts from an IME are dropped on Android 13+ (no notification permission),
                    // so say it in the panel, then hand back to the previous keyboard.
                    result == MemeSender.Result.COPIED_TO_CLIPBOARD ->
                        p.showMessage(listOfNotNull(getString(R.string.toast_copied), saveNote).joinToString("\n\n"), onDone = finish)
                    saveNote != null -> p.showMessage(saveNote, onDone = finish)
                    else -> finish()
                }
            }
        }
    }

    override fun saveMeme(template: MemeTemplate, boxes: List<TextBox>) {
        val options = renderOptions()
        io.execute {
            val rendered = sender.render(template, boxes, options)
            val saved = rendered?.let { MemeSaver.save(this, prefs, it.file, it.file.name) }
            main.post {
                val p = panel ?: return@post
                p.onSendFinished()
                if (rendered == null) {
                    p.showMessage(getString(R.string.toast_render_failed))
                    return@post
                }
                repository.markRecent(template.key)
                remember(template, boxes, saved)
                p.showMessage(
                    when (saved) {
                        is MemeSaver.Result.Saved -> getString(R.string.toast_saved, saved.label)
                        else -> saveMessage(saved, always = true) ?: getString(R.string.toast_save_failed)
                    },
                    durationMs = 1200L
                )
            }
        }
    }

    /** Append to the template's caption history (off the main thread) when the setting allows it. */
    private fun remember(template: MemeTemplate, boxes: List<TextBox>, saved: MemeSaver.Result?) {
        if (!prefs.historyEnabled || io.isShutdown) return
        val savedUri = (saved as? MemeSaver.Result.Saved)?.uri?.toString()
        io.execute {
            try { history.append(template.key, boxes, savedUri) } catch (e: Exception) { /* best effort */ }
        }
    }

    /**
     * Message for a save that happened alongside Send: failures always, "unavailable" only once
     * per panel (the fix lives in Settings; nagging on every send helps nobody). Success is silent.
     */
    private fun saveMessage(result: MemeSaver.Result?, always: Boolean = false): String? = when (result) {
        is MemeSaver.Result.Failed -> getString(R.string.toast_save_failed)
        MemeSaver.Result.Unavailable -> {
            if (always || !warnedSaveUnavailable) {
                warnedSaveUnavailable = true
                getString(R.string.toast_save_unavailable)
            } else null
        }
        else -> null
    }

    override fun switchBackToPreviousKeyboard() {
        // API 28+. Returns false if there is no "previous" IME (e.g. we were picked
        // directly from settings); then try "next", and finally show the system picker.
        if (!switchToPreviousInputMethod() && !switchToNextInputMethod(false)) {
            (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
        }
    }

    override fun openSettings() = launch(Intent(this, SettingsActivity::class.java))

    override fun openSources() = launch(Intent(this, SourcesActivity::class.java))

    override fun openUrl(url: String) {
        if (url.isBlank()) {
            panel?.showMessage(getString(R.string.link_not_set))
            return
        }
        try {
            launch(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            panel?.showMessage(getString(R.string.toast_no_browser))
        }
    }

    override fun openDonate() = launch(Intent(this, DonateActivity::class.java))

    // ---- plain-text mode --------------------------------------------------------

    override fun typeText(text: String) {
        currentInputConnection?.commitText(text, 1)
    }

    override fun deleteBackward() {
        val ic = currentInputConnection ?: return
        val selected = ic.getSelectedText(0)
        if (!selected.isNullOrEmpty()) ic.commitText("", 1) else ic.deleteSurroundingText(1, 0)
    }

    override fun pressEnter() {
        val info = currentInputEditorInfo
        val action = info?.imeOptions?.and(EditorInfo.IME_MASK_ACTION) ?: EditorInfo.IME_ACTION_NONE
        val noEnterAction = info?.imeOptions?.and(EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0
        if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED && !noEnterAction) {
            currentInputConnection?.performEditorAction(action)
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    override fun hidePanel() = requestHideSelf(0)

    /** Activities started from a service need their own task. */
    private fun launch(intent: Intent) {
        startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Watermark on or off follows the user setting; the editor preview reads the same flag. */
    private fun renderOptions(): RenderOptions =
        RenderOptions(if (prefs.watermarkEnabled) Watermark(getString(R.string.watermark_text)) else null)

    companion object {
        const val PANEL_FRACTION_GRID = 0.45f
        const val PANEL_FRACTION_EDITOR = 0.65f
        const val LANDSCAPE_FRACTION_GRID = 0.70f
        const val LANDSCAPE_FRACTION_EDITOR = 0.70f
    }
}
