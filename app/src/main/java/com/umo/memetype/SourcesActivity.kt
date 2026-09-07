package com.umo.memetype

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.source.LocalSource
import com.umo.memetype.source.MemeSource
import com.umo.memetype.source.PackInstaller
import com.umo.memetype.source.SourceRegistry
import com.umo.memetype.store.AppPrefs
import com.umo.memetype.ui.Rows
import java.util.concurrent.Executors

/**
 * Manage template sources: enable/disable each, remove installed packs, import a pack (.zip)
 * or a single image (which opens [ImportImageActivity] to place the text boxes).
 * Opened from the panel sidebar and from Settings.
 */
class SourcesActivity : Activity() {

    private lateinit var registry: SourceRegistry
    private lateinit var sourcesList: LinearLayout
    private val io = Executors.newSingleThreadExecutor()
    private var refreshSerial = 0

    private class Row(val source: MemeSource, val count: Int, val enabled: Boolean, val local: List<MemeTemplate>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry = SourceRegistry(this, AppPrefs(this))

        val d = resources.displayMetrics.density
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, (8 * d).toInt(), 0, (24 * d).toInt())
        }
        list.addView(TextView(this).apply {
            text = getString(R.string.sources_title)
            textSize = 22f
            setPadding((16 * d).toInt(), (12 * d).toInt(), (16 * d).toInt(), (4 * d).toInt())
        })
        list.addView(Rows.buttonRow(this, getString(R.string.import_pack), getString(R.string.import_pack_sub)) { pickPack() }.root)
        list.addView(Rows.buttonRow(this, getString(R.string.import_image), getString(R.string.import_image_sub)) { pickImage() }.root)
        list.addView(Rows.divider(this))
        list.addView(Rows.header(this, getString(R.string.sources_installed)))
        sourcesList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(sourcesList)

        setContentView(ScrollView(this).apply {
            fitsSystemWindows = true
            clipToPadding = false
            addView(list)
        })
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        io.shutdownNow()
        super.onDestroy()
    }

    private fun refresh() {
        val serial = ++refreshSerial
        if (io.isShutdown) return
        io.execute {
            val rows = registry.discover().map { s ->
                val templates = try { s.load() } catch (e: Exception) { emptyList() }
                Row(s, templates.size, registry.isEnabled(s.id), if (s is LocalSource) templates else emptyList())
            }
            runOnUiThread { if (serial == refreshSerial && !isFinishing) render(rows) }
        }
    }

    private fun render(rows: List<Row>) {
        sourcesList.removeAllViews()
        rows.forEach { row ->
            val s = row.source
            val subtitle = listOf(
                resources.getQuantityString(R.plurals.templates_count, row.count, row.count),
                s.description
            ).filter { it.isNotBlank() }.joinToString(" · ")
            sourcesList.addView(Rows.switchRow(this, s.displayName, subtitle, row.enabled) { on ->
                if (!io.isShutdown) io.execute { registry.setEnabled(s.id, on) }
            }.root)
            if (s.removable) {
                sourcesList.addView(Rows.buttonRow(this, getString(R.string.remove_source, s.displayName), null) {
                    if (!io.isShutdown) io.execute {
                        registry.uninstallPack(s.id)
                        runOnUiThread { refresh() }
                    }
                }.root)
            }
            row.local.forEach { t ->
                sourcesList.addView(Rows.buttonRow(this, "    " + t.name, getString(R.string.edit_boxes_hint)) {
                    startActivity(ImportImageActivity.editIntent(this, t.id))
                }.root)
            }
            sourcesList.addView(Rows.divider(this))
        }
    }

    // ---- pickers -------------------------------------------------------------------

    private fun pickPack() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/zip", "application/x-zip-compressed", "application/octet-stream"))
        }
        startPicker(intent, REQ_PACK)
    }

    private fun pickImage() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "image/*"
        }
        startPicker(intent, REQ_IMAGE)
    }

    private fun startPicker(intent: Intent, code: Int) {
        try {
            startActivityForResult(intent, code)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(this, R.string.toast_no_picker, Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        when (requestCode) {
            REQ_PACK -> {
                if (io.isShutdown) return
                io.execute {
                    val result = registry.installPack(uri)
                    runOnUiThread {
                        if (isFinishing) return@runOnUiThread
                        val msg = when (result) {
                            is PackInstaller.Result.Installed -> getString(R.string.pack_installed, result.name, result.count)
                            is PackInstaller.Result.Invalid -> getString(R.string.pack_invalid, result.reason)
                        }
                        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
                        refresh()
                    }
                }
            }
            REQ_IMAGE -> startActivity(ImportImageActivity.importIntent(this, uri))
        }
    }

    private companion object {
        const val REQ_PACK = 11
        const val REQ_IMAGE = 12
    }
}
