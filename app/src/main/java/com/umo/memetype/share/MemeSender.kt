package com.umo.memetype.share

import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.core.content.FileProvider
import androidx.core.view.inputmethod.EditorInfoCompat
import androidx.core.view.inputmethod.InputConnectionCompat
import androidx.core.view.inputmethod.InputContentInfoCompat
import com.umo.memetype.meme.MemeRenderer
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.RenderOptions
import com.umo.memetype.meme.TemplateRepository
import com.umo.memetype.meme.TextBox
import java.io.File

/**
 * Renders the meme to a PNG and pushes it into the target app:
 * commitContent when the field advertises image support, clipboard otherwise.
 * User feedback is left to the caller: toasts from an IME are dropped on Android 13+
 * unless the app holds the notification permission, so the panel shows messages itself.
 */
class MemeSender(
    private val context: Context,
    private val repository: TemplateRepository
) {

    enum class Result { COMMITTED, COPIED_TO_CLIPBOARD }

    /** A rendered meme: the PNG in cacheDir/memes plus its shareable FileProvider URI. */
    class Rendered(val file: File, val uri: Uri)

    private val renderer = MemeRenderer(context)

    /** Background-safe: renders the PNG and returns it with its content URI (null on failure). */
    fun render(template: MemeTemplate, boxes: List<TextBox>, options: RenderOptions): Rendered? {
        return try {
            val base = repository.loadBitmap(template, MemeRenderer.MAX_OUTPUT_WIDTH) ?: return null
            val file = try {
                renderer.renderToFile(base, boxes, options)
            } finally {
                base.recycle()
            }
            Rendered(file, FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file))
        } catch (e: Exception) {
            Log.e(TAG, "render failed", e)
            null
        }
    }

    /** Main thread: commitContent if the field accepts images, else copy the URI to the clipboard. */
    fun deliver(uri: Uri, editorInfo: EditorInfo?, inputConnection: InputConnection?): Result {
        if (editorInfo != null && inputConnection != null && targetAcceptsImage(editorInfo)) {
            if (commitContent(inputConnection, editorInfo, uri)) return Result.COMMITTED
        }
        copyToClipboard(uri)
        return Result.COPIED_TO_CLIPBOARD
    }

    /** True if the focused field declares it accepts PNG (or any image). */
    fun targetAcceptsImage(editorInfo: EditorInfo): Boolean {
        val mimes = EditorInfoCompat.getContentMimeTypes(editorInfo)
        return mimes.any { ClipDescription.compareMimeTypes(MIME_PNG, it) }
    }

    private fun commitContent(ic: InputConnection, editorInfo: EditorInfo, uri: Uri): Boolean {
        val info = InputContentInfoCompat(
            uri,
            ClipDescription("meme", arrayOf(MIME_PNG)),
            null
        )
        return try {
            InputConnectionCompat.commitContent(
                ic, editorInfo, info,
                InputConnectionCompat.INPUT_CONTENT_GRANT_READ_URI_PERMISSION,
                null
            )
        } catch (e: Exception) {
            Log.w(TAG, "commitContent threw", e)
            false
        }
    }

    private fun copyToClipboard(uri: Uri) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newUri(context.contentResolver, "meme", uri))
    }

    private companion object {
        const val TAG = "MemeSender"
        const val MIME_PNG = "image/png"
    }
}
