package com.umo.memetype.ui

import android.content.ClipDescription
import android.content.Context
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.inputmethod.EditorInfoCompat
import com.umo.memetype.R
import com.umo.memetype.meme.DefaultLayouts
import com.umo.memetype.meme.MemeRenderer
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.RenderOptions
import com.umo.memetype.meme.TemplateRepository
import com.umo.memetype.meme.TextBox
import com.umo.memetype.meme.TextBoxSpec
import com.umo.memetype.meme.Watermark
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.store.HistoryStore
import com.umo.memetype.store.LayoutStore
import java.util.concurrent.Executors

/**
 * Screen 2: the interactive canvas (move/resize text boxes), one chip per box, the toolbar
 * (back, add, delete, reset, send) and the mini QWERTY.
 *
 * Rendering is split in two layers: the *baked* bitmap holds the template plus every box
 * except the selected one (plus the watermark) and is re-rendered on the io thread only when
 * the set of boxes changes; the selected box is drawn live by [MemeCanvasView] so typing,
 * moving and resizing never wait for a render.
 *
 * Root is a FrameLayout so the inflated layout (portrait or layout-land) decides orientation.
 */
class MemeEditorView(
    context: Context,
    private val repository: TemplateRepository,
    private val callbacks: Callbacks
) : FrameLayout(context) {

    interface Callbacks {
        fun onBack()
        fun onSend(template: MemeTemplate, boxes: List<TextBox>)
        fun onSave(template: MemeTemplate, boxes: List<TextBox>)
    }

    private val canvas: MemeCanvasView
    private val chips: LinearLayout
    private val chipsScroll: View
    private val sendButton: View
    private val saveButton: View
    private val deleteButton: View
    private val historyButton: View
    private val historyBadge: TextView
    private val historyContainer: FrameLayout
    private val historyStrip: HistoryStripView
    private val sendHint: View
    private val keyboard: MiniKeyboardView

    private val renderer = MemeRenderer(context)
    private val prefs = AppPrefs(context)
    private val layoutStore = LayoutStore(context)
    private val historyStore = HistoryStore(context)
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    private var template: MemeTemplate? = null
    private val boxes = mutableListOf<TextBox>()
    private var selected = 0
    private var baseBitmap: Bitmap? = null          // downscaled template for the preview
    private var bakedBitmap: Bitmap? = null
    private var bakeSerial = 0
    private var layoutSavePending: Runnable? = null
    private val chipViews = mutableListOf<TextView>()
    private var historyEntries: List<HistoryStore.Entry> = emptyList()
    private var historyThumbBase: Bitmap? = null    // repository-cached; never recycled here
    private var historyVisible = false

    init {
        LayoutInflater.from(context).inflate(R.layout.view_meme_editor, this, true)
        canvas = findViewById(R.id.canvas)
        chips = findViewById(R.id.box_chips)
        chipsScroll = findViewById(R.id.box_chips_scroll)
        sendButton = findViewById(R.id.btn_send)
        saveButton = findViewById(R.id.btn_save)
        deleteButton = findViewById(R.id.btn_delete_box)
        historyButton = findViewById(R.id.btn_history)
        historyBadge = findViewById(R.id.history_badge)
        historyContainer = findViewById(R.id.history_strip)
        sendHint = findViewById(R.id.send_hint)
        keyboard = findViewById(R.id.keyboard)
        historyStrip = HistoryStripView(context)
        historyContainer.addView(
            historyStrip, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        historyButton.setOnClickListener { showHistory(!historyVisible) }

        canvas.renderer = renderer
        canvas.listener = object : MemeCanvasView.Listener {
            override fun onBoxTapped(index: Int) = select(index)
            override fun onBoxChanged(index: Int, spec: TextBoxSpec, gestureEnded: Boolean) {
                if (index !in boxes.indices) return
                boxes[index] = boxes[index].copy(spec = spec)
                canvas.setBoxes(boxes, selected)
                if (gestureEnded) scheduleLayoutSave()
            }
        }

        findViewById<View>(R.id.btn_back).setOnClickListener { callbacks.onBack() }
        findViewById<View>(R.id.btn_add_box).setOnClickListener { addBox() }
        findViewById<View>(R.id.btn_reset_layout).setOnClickListener { resetLayout() }
        deleteButton.setOnClickListener { deleteBox() }
        sendButton.setOnClickListener { send() }
        saveButton.setOnClickListener { save() }

        keyboard.listener = object : MiniKeyboardView.Listener {
            override fun onText(text: String) = editText { it + text }
            override fun onBackspace() = editText { it.dropLast(1) }
            override fun onEnter() {
                // Enter moves to the next box; on the last one it sends.
                if (selected < boxes.size - 1) select(selected + 1) else send()
            }
        }
    }

    // ---- public -----------------------------------------------------------------

    fun setTemplate(t: MemeTemplate) {
        reset()
        template = t
        val serial = ++bakeSerial
        if (io.isShutdown) return
        val wantHistory = prefs.historyEnabled
        io.execute {
            val specs = try { layoutStore.load(t.key) } catch (e: Exception) { null } ?: t.boxes
            val bmp = repository.loadBitmap(t, PREVIEW_MAX_WIDTH)
            val entries = if (wantHistory) try { historyStore.list(t.key) } catch (e: Exception) { emptyList() } else emptyList()
            val thumbBase = if (entries.isNotEmpty()) repository.loadThumbnail(t, HistoryStripView.THUMB_BASE_PX) else null
            main.post {
                if (serial != bakeSerial) { bmp?.recycle(); return@post }
                val old = baseBitmap
                baseBitmap = bmp
                // Safe: any bake using `old` was enqueued before this task on the same
                // single-thread executor, so it has already finished.
                old?.recycle()
                boxes.clear()
                boxes += specs.map { TextBox(it) }
                if (boxes.isEmpty()) boxes += TextBox(DefaultLayouts.newBox(0))
                selected = 0
                rebuildChips()
                updateToolbar()
                canvas.setBoxes(boxes, selected)
                rebake()
                historyEntries = entries
                historyThumbBase = thumbBase
                updateHistoryBadge()
                // Requirement: picking a template again offers its previous memes right away.
                showHistory(entries.isNotEmpty())
            }
        }
    }

    // ---- history ------------------------------------------------------------------

    private fun showHistory(visible: Boolean) {
        historyVisible = visible
        historyContainer.visibility = if (visible) View.VISIBLE else View.GONE
        chipsScroll.visibility = if (visible) View.GONE else View.VISIBLE
        historyButton.isSelected = visible
        val t = template
        if (visible && t != null) {
            historyStrip.bind(
                t.key, historyEntries, historyThumbBase, renderer, io,
                onPick = { applyHistory(it) },
                onDelete = { deleteHistory(it) }
            )
        }
    }

    private fun updateHistoryBadge() {
        val n = historyEntries.size
        historyBadge.text = if (n > 99) "99+" else n.toString()
        historyBadge.visibility = if (n > 0) View.VISIBLE else View.GONE
    }

    /** Restore a previous meme: geometry and texts. Not persisted as the layout unless the user then moves a box. */
    private fun applyHistory(entry: HistoryStore.Entry) {
        if (template == null) return
        cancelLayoutSave()
        boxes.clear()
        boxes += entry.boxes.map { it.copy() }
        selected = 0
        rebuildChips()
        updateToolbar()
        canvas.setBoxes(boxes, selected)
        rebake()
        showHistory(false)
    }

    private fun deleteHistory(entry: HistoryStore.Entry) {
        val t = template ?: return
        historyEntries = historyEntries.filter { it.ts != entry.ts }
        updateHistoryBadge()
        if (!io.isShutdown) io.execute { try { historyStore.remove(t.key, entry.ts) } catch (e: Exception) { } }
        if (historyEntries.isEmpty()) showHistory(false) else showHistory(true)
    }

    fun setSending(sending: Boolean) {
        sendButton.isEnabled = !sending
        sendButton.alpha = if (sending) 0.5f else 1f
        saveButton.isEnabled = !sending
        saveButton.alpha = if (sending) 0.5f else 1f
    }

    fun destroy() {
        saveLayoutNow()
        io.shutdownNow()
    }

    fun setTargetAcceptsImages(accepts: Boolean) {
        sendHint.visibility = if (accepts) View.GONE else View.VISIBLE
    }

    /** Clear text, boxes and bitmaps (called when leaving the editor or hiding the panel). */
    fun reset() {
        saveLayoutNow()
        bakeSerial++
        boxes.clear()
        selected = 0
        chips.removeAllViews()
        chipViews.clear()
        setSending(false)
        canvas.setBaked(null)
        canvas.setBoxes(emptyList(), -1)
        historyStrip.clear()
        historyEntries = emptyList()
        historyThumbBase = null
        updateHistoryBadge()
        showHistory(false)
        bakedBitmap?.recycle(); bakedBitmap = null
        // Do not recycle baseBitmap here: a bake may still hold it on the io thread.
        // It is released when the next template replaces it.
        template = null
    }

    // ---- selection / editing ------------------------------------------------------

    private fun select(index: Int) {
        if (index !in boxes.indices || index == selected) return
        selected = index
        chipViews.forEachIndexed { i, v -> v.isSelected = i == index }
        canvas.setBoxes(boxes, selected)
        rebake()
    }

    private inline fun editText(transform: (String) -> String) {
        if (selected !in boxes.indices) return
        val current = boxes[selected]
        val updated = current.copy(text = transform(current.text))
        boxes[selected] = updated
        chipViews.getOrNull(selected)?.text = chipLabel(selected, updated)
        canvas.setBoxes(boxes, selected)   // live layer only; no bake needed
    }

    private fun addBox() {
        if (template == null) return
        boxes += TextBox(DefaultLayouts.newBox(boxes.size))
        selected = boxes.size - 1
        rebuildChips()
        updateToolbar()
        canvas.setBoxes(boxes, selected)
        rebake()
        scheduleLayoutSave()
    }

    private fun deleteBox() {
        if (boxes.size <= 1 || selected !in boxes.indices) return
        boxes.removeAt(selected)
        selected = selected.coerceAtMost(boxes.size - 1)
        rebuildChips()
        updateToolbar()
        canvas.setBoxes(boxes, selected)
        rebake()
        scheduleLayoutSave()
    }

    /** Back to the template's own layout; texts are kept by index. */
    private fun resetLayout() {
        val t = template ?: return
        cancelLayoutSave()
        val texts = boxes.map { it.text }
        boxes.clear()
        boxes += t.boxes.mapIndexed { i, spec -> TextBox(spec, texts.getOrElse(i) { "" }) }
        if (boxes.isEmpty()) boxes += TextBox(DefaultLayouts.newBox(0))
        selected = selected.coerceIn(0, boxes.size - 1)
        rebuildChips()
        updateToolbar()
        canvas.setBoxes(boxes, selected)
        rebake()
        if (!io.isShutdown) io.execute { layoutStore.delete(t.key) }
    }

    // ---- layout persistence -------------------------------------------------------

    private fun scheduleLayoutSave() {
        cancelLayoutSave()
        val r = Runnable { layoutSavePending = null; saveLayout() }
        layoutSavePending = r
        main.postDelayed(r, LAYOUT_SAVE_DEBOUNCE_MS)
    }

    private fun cancelLayoutSave() {
        layoutSavePending?.let { main.removeCallbacks(it) }
        layoutSavePending = null
    }

    /** Flush a pending debounced save immediately (leaving the editor, hiding the panel). */
    private fun saveLayoutNow() {
        if (layoutSavePending == null) return
        cancelLayoutSave()
        saveLayout()
    }

    private fun saveLayout() {
        val t = template ?: return
        val specs = boxes.map { it.spec }
        if (specs.isEmpty() || io.isShutdown) return
        io.execute { try { layoutStore.save(t.key, specs) } catch (e: Exception) { /* best effort */ } }
    }

    // ---- rendering --------------------------------------------------------------

    private fun renderOptions(): RenderOptions =
        RenderOptions(if (prefs.watermarkEnabled) Watermark(context.getString(R.string.watermark_text)) else null)

    /** Re-render everything except the selected box into the baked layer. */
    private fun rebake() {
        val base = baseBitmap ?: return
        val serial = ++bakeSerial
        val snapshot = boxes.toList()
        val skip = selected
        val options = renderOptions()
        if (io.isShutdown) return
        io.execute {
            val out = try {
                if (base.isRecycled) null else renderer.render(base, snapshot, options, skipIndex = skip)
            } catch (e: Exception) { null }
            main.post {
                if (serial != bakeSerial || out == null) { out?.recycle(); return@post }
                val old = bakedBitmap
                bakedBitmap = out
                canvas.setBaked(out)
                old?.recycle()
            }
        }
    }

    // ---- chips / toolbar ----------------------------------------------------------

    private fun rebuildChips() {
        chips.removeAllViews()
        chipViews.clear()
        val d = resources.displayMetrics.density
        boxes.forEachIndexed { i, box ->
            val chip = TextView(context).apply {
                text = chipLabel(i, box)
                setTextColor(ContextCompat.getColor(context, R.color.text_primary))
                textSize = 12f
                maxLines = 1
                setPadding((12 * d).toInt(), (5 * d).toInt(), (12 * d).toInt(), (5 * d).toInt())
                background = ContextCompat.getDrawable(context, R.drawable.bg_chip)
                isSelected = i == selected
                setOnClickListener { select(i) }
            }
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.marginEnd = (6 * d).toInt()
            chips.addView(chip, lp)
            chipViews += chip
        }
    }

    private fun chipLabel(index: Int, box: TextBox): String {
        val body = box.text.ifBlank {
            box.spec.hint.ifBlank { context.getString(R.string.box_hint_n, index + 1) }
        }
        val shown = if (body.length > MAX_CHIP_CHARS) body.take(MAX_CHIP_CHARS - 1) + "…" else body
        return "${index + 1}  $shown"
    }

    private fun updateToolbar() {
        val canDelete = boxes.size > 1
        deleteButton.isEnabled = canDelete
        deleteButton.alpha = if (canDelete) 1f else 0.4f
    }

    private fun send() {
        val t = template ?: return
        if (!sendButton.isEnabled) return
        setSending(true)
        callbacks.onSend(t, boxes.toList())
    }

    /** Save to the device and stay in the editor. */
    private fun save() {
        val t = template ?: return
        if (!saveButton.isEnabled) return
        setSending(true)
        callbacks.onSave(t, boxes.toList())
    }

    companion object {
        private const val PREVIEW_MAX_WIDTH = 720
        private const val LAYOUT_SAVE_DEBOUNCE_MS = 500L
        private const val MAX_CHIP_CHARS = 16

        /** True if the focused field accepts PNG (or image/<any>). */
        fun acceptsImages(info: EditorInfo): Boolean =
            EditorInfoCompat.getContentMimeTypes(info)
                .any { ClipDescription.compareMimeTypes("image/png", it) }
    }
}
