package com.umo.memetype.source

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.umo.memetype.meme.Bitmaps
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TextBoxSpec
import com.umo.memetype.store.AppPrefs
import java.io.File

/**
 * Knows every [MemeSource] on the device and which are enabled. Every mutation bumps
 * `sources_version` in the shared preferences, which the IME's TemplateRepository watches to
 * reload (the IME and the activities live in one process). All methods do IO except [isEnabled].
 */
class SourceRegistry(private val context: Context, private val prefs: AppPrefs) {

    val local = LocalSource(context)
    private val bundled = PackSource.bundled(context)
    private val packsDir = File(context.filesDir, "packs")

    /** Bundled, Mine, then installed packs in name order. */
    fun discover(): List<MemeSource> {
        val packs = packsDir.listFiles()
            ?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, PackSource.PACK_FILE).isFile }
            ?.sortedBy { it.name }
            ?.map { PackSource.installed(it) }
            ?: emptyList()
        return listOf<MemeSource>(bundled, local) + packs
    }

    /** Enabled sources, in the user's order (unknown ids keep discovery order). */
    fun enabled(): List<MemeSource> {
        val disabled = prefs.sourcesDisabled
        val order = prefs.sourcesOrder
        return discover()
            .filter { it.id !in disabled }
            .sortedBy { order.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }
    }

    fun byId(id: String): MemeSource? = discover().firstOrNull { it.id == id }

    fun isEnabled(id: String): Boolean = id !in prefs.sourcesDisabled

    fun setEnabled(id: String, enabled: Boolean) {
        prefs.sourcesDisabled = if (enabled) prefs.sourcesDisabled - id else prefs.sourcesDisabled + id
        bump()
    }

    fun installPack(zip: Uri): PackInstaller.Result {
        val result = PackInstaller.install(context, zip, packsDir)
        if (result is PackInstaller.Result.Installed) {
            prefs.sourcesDisabled = prefs.sourcesDisabled - result.sourceId
            bump()
        }
        return result
    }

    fun uninstallPack(sourceId: String) {
        PackInstaller.uninstall(packsDir, sourceId)
        prefs.sourcesDisabled = prefs.sourcesDisabled - sourceId
        bump()
    }

    /**
     * Copies the picked image into the app (re-encoded as PNG, at most [MAX_IMPORT_WIDTH] wide)
     * and registers it under "Mine" with [boxes]. Null when the image cannot be decoded.
     */
    fun importImage(src: Uri, boxes: List<TextBoxSpec>): MemeTemplate? {
        val decoded = Bitmaps.decodeScaled({ context.contentResolver.openInputStream(src) }, MAX_IMPORT_WIDTH)
            ?: return null
        val bmp = if (decoded.width > MAX_IMPORT_WIDTH) {
            val h = (decoded.height.toLong() * MAX_IMPORT_WIDTH / decoded.width).toInt().coerceAtLeast(1)
            Bitmap.createScaledBitmap(decoded, MAX_IMPORT_WIDTH, h, true).also { if (it !== decoded) decoded.recycle() }
        } else decoded
        val file = local.newImageFile()
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val template = local.add(displayName(src), file, bmp.width, bmp.height, boxes)
            bump()
            return template
        } catch (e: Exception) {
            Log.w(TAG, "import failed", e)
            file.delete()
            return null
        } finally {
            bmp.recycle()
        }
    }

    fun updateLocalBoxes(templateId: String, boxes: List<TextBoxSpec>) {
        local.updateBoxes(templateId, boxes)
        bump()
    }

    fun removeLocal(templateId: String) {
        local.remove(templateId)
        bump()
    }

    private fun displayName(uri: Uri): String {
        val fromProvider = try {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        } catch (e: Exception) {
            null
        }
        val raw = fromProvider ?: uri.lastPathSegment ?: ""
        return raw.substringAfterLast('/').substringBeforeLast('.').replace('_', ' ').trim().ifBlank { DEFAULT_NAME }
    }

    private fun bump() {
        prefs.sourcesVersion = prefs.sourcesVersion + 1
    }

    companion object {
        private const val TAG = "SourceRegistry"
        const val MAX_IMPORT_WIDTH = 1600
        private const val DEFAULT_NAME = "Imported image"
    }
}
