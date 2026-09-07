package com.umo.memetype

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.umo.memetype.meme.Bitmaps
import com.umo.memetype.meme.DefaultLayouts
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeRenderer
import com.umo.memetype.meme.RenderOptions
import com.umo.memetype.meme.TextBox
import com.umo.memetype.meme.TextBoxSpec
import com.umo.memetype.source.SourceRegistry
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.ui.MemeCanvasView
import java.util.concurrent.Executors

/**
 * Place the caption boxes on an imported image (new import) or change them on an existing
 * "Mine" template. Reuses [MemeCanvasView]; each box shows its hint so the layout is visible.
 * No EditText anywhere: while Memetype is the selected IME it would summon the meme panel.
 */
class ImportImageActivity : Activity(), MemeCanvasView.Listener {

    private lateinit var registry: SourceRegistry
    private lateinit var canvas: MemeCanvasView
    private lateinit var deleteBoxButton: Button
    private lateinit var saveButton: Button
    private val renderer by lazy { MemeRenderer(this) }
    private val io = Executors.newSingleThreadExecutor()

    private var sourceUri: Uri? = null
    private var localId: String? = null
    private var base: Bitmap? = null
    private var baked: Bitmap? = null
    private var bakeSerial = 0
    private val boxes = mutableListOf<TextBox>()
    private var selected = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = SourceRegistry(this, AppPrefs(this))
        localId = intent.getStringExtra(EXTRA_LOCAL_ID)
        sourceUri = intent.data
        if (localId == null && sourceUri == null) { finish(); return }
        val editing = localId != null

        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            fitsSystemWindows = true
            setBackgroundColor(ContextCompat.getColor(context, R.color.panel_bg))
        }
        root.addView(TextView(this).apply {
            text = getString(if (editing) R.string.edit_template_title else R.string.import_title)
            textSize = 20f
            setTextColor(ContextCompat.getColor(context, R.color.text_primary))
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        })
        root.addView(TextView(this).apply {
            text = getString(R.string.import_instructions)
            textSize = 13f
            setTextColor(ContextCompat.getColor(context, R.color.text_secondary))
            setPadding((16 * d).toInt(), 0, (16 * d).toInt(), (8 * d).toInt())
        })
        canvas = MemeCanvasView(this).apply {
            renderer = this@ImportImageActivity.renderer
            listener = this@ImportImageActivity
            setBackgroundColor(ContextCompat.getColor(context, R.color.panel_surface))
        }
        root.addView(canvas, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply {
            val m = (8 * d).toInt(); setMargins(m, 0, m, 0)
        })

        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding((8 * d).toInt(), (6 * d).toInt(), (8 * d).toInt(), (6 * d).toInt())
        }
        bar.addView(Button(this).apply { text = getString(R.string.add_box); setOnClickListener { addBox() } })
        deleteBoxButton = Button(this).apply { text = getString(R.string.delete_box); setOnClickListener { deleteBox() } }
        bar.addView(deleteBoxButton)
        bar.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f))
        if (editing) {
            bar.addView(Button(this).apply { text = getString(R.string.delete_template); setOnClickListener { deleteTemplate() } })
        }
        saveButton = Button(this).apply {
            text = getString(R.string.import_save)
            isEnabled = false
            setOnClickListener { save() }
        }
        bar.addView(saveButton)
        root.addView(bar)
        setContentView(root)

        load()
    }

    override fun onDestroy() {
        io.shutdownNow()
        canvas.setBaked(null)
        baked?.recycle(); baked = null
        base?.recycle(); base = null
        super.onDestroy()
    }

    // ---- loading / baking ---------------------------------------------------------

    private fun load() {
        val id = localId
        val uri = sourceUri
        io.execute {
            var specs: List<TextBoxSpec> = DefaultLayouts.classic()
            val bmp: Bitmap? = if (id != null) {
                val t = try { registry.local.load().firstOrNull { it.id == id } } catch (e: Exception) { null }
                val file = (t?.image as? ImageRef.LocalFile)?.file
                if (t != null) specs = t.boxes
                file?.let { f -> Bitmaps.decodeScaled({ f.inputStream() }, PREVIEW_MAX_WIDTH) }
            } else if (uri != null) {
                Bitmaps.decodeScaled({ contentResolver.openInputStream(uri) }, PREVIEW_MAX_WIDTH)
            } else null
            runOnUiThread {
                if (isFinishing) { bmp?.recycle(); return@runOnUiThread }
                if (bmp == null) {
                    Toast.makeText(this, R.string.import_failed, Toast.LENGTH_SHORT).show()
                    finish()
                    return@runOnUiThread
                }
                base = bmp
                boxes.clear()
                boxes += specs.mapIndexed { i, s -> TextBox(s, labelFor(s, i)) }
                selected = 0
                saveButton.isEnabled = true
                updateButtons()
                canvas.setBoxes(boxes, selected)
                rebake()
            }
        }
    }

    private fun labelFor(spec: TextBoxSpec, index: Int) =
        spec.hint.ifBlank { getString(R.string.box_hint_n, index + 1) }

    private fun rebake() {
        val b = base ?: return
        val serial = ++bakeSerial
        val snapshot = boxes.toList()
        val skip = selected
        if (io.isShutdown) return
        io.execute {
            val out = try { if (b.isRecycled) null else renderer.render(b, snapshot, RenderOptions(), skipIndex = skip) } catch (e: Exception) { null }
            runOnUiThread {
                if (serial != bakeSerial || out == null || isFinishing) { out?.recycle(); return@runOnUiThread }
                val old = baked
                baked = out
                canvas.setBaked(out)
                old?.recycle()
            }
        }
    }

    // ---- MemeCanvasView.Listener --------------------------------------------------

    override fun onBoxTapped(index: Int) {
        if (index !in boxes.indices || index == selected) return
        selected = index
        canvas.setBoxes(boxes, selected)
        rebake()
    }

    override fun onBoxChanged(index: Int, spec: TextBoxSpec, gestureEnded: Boolean) {
        if (index !in boxes.indices) return
        boxes[index] = boxes[index].copy(spec = spec)
        canvas.setBoxes(boxes, selected)
    }

    // ---- actions ------------------------------------------------------------------

    private fun addBox() {
        if (base == null) return
        val spec = DefaultLayouts.newBox(boxes.size)
        boxes += TextBox(spec, labelFor(spec, boxes.size))
        selected = boxes.size - 1
        updateButtons()
        canvas.setBoxes(boxes, selected)
        rebake()
    }

    private fun deleteBox() {
        if (boxes.size <= 1 || selected !in boxes.indices) return
        boxes.removeAt(selected)
        selected = selected.coerceAtMost(boxes.size - 1)
        updateButtons()
        canvas.setBoxes(boxes, selected)
        rebake()
    }

    private fun updateButtons() {
        deleteBoxButton.isEnabled = boxes.size > 1
    }

    private fun save() {
        val specs = boxes.mapIndexed { i, b -> b.spec.copy(hint = b.spec.hint.ifBlank { getString(R.string.box_hint_n, i + 1) }) }
        saveButton.isEnabled = false
        val id = localId
        val uri = sourceUri
        io.execute {
            val ok = try {
                if (id != null) { registry.updateLocalBoxes(id, specs); true }
                else uri != null && registry.importImage(uri, specs) != null
            } catch (e: Exception) { false }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, if (ok) R.string.template_saved else R.string.import_failed, Toast.LENGTH_SHORT).show()
                if (ok) finish() else saveButton.isEnabled = true
            }
        }
    }

    private fun deleteTemplate() {
        val id = localId ?: return
        io.execute {
            try { registry.removeLocal(id) } catch (e: Exception) { }
            runOnUiThread {
                if (isFinishing) return@runOnUiThread
                Toast.makeText(this, R.string.template_deleted, Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }

    companion object {
        private const val EXTRA_LOCAL_ID = "local_id"
        private const val PREVIEW_MAX_WIDTH = 1080

        fun importIntent(context: Context, image: Uri): Intent =
            Intent(context, ImportImageActivity::class.java)
                .setData(image)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)

        fun editIntent(context: Context, localTemplateId: String): Intent =
            Intent(context, ImportImageActivity::class.java).putExtra(EXTRA_LOCAL_ID, localTemplateId)
    }
}
