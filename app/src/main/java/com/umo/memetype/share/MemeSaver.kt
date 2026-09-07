package com.umo.memetype.share

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.RequiresApi
import com.umo.memetype.store.AppPrefs
import java.io.File
import java.io.FileNotFoundException

/**
 * Copies a rendered PNG to the user's device. The only place that knows about save
 * destinations and Android version differences:
 *
 * 1. [Target.FOLDER] — the folder the user picked in Settings (Storage Access Framework tree
 *    with a persisted write grant). If the grant was revoked or the folder deleted the save
 *    fails and reports it; there is no silent fallback (Settings shows "choose again").
 * 2. [Target.MEDIA_STORE] — Android 10+: Pictures/Memetype through MediaStore, no permission.
 * 3. [Target.LEGACY_PICTURES] — Android 9 with WRITE_EXTERNAL_STORAGE granted from Settings.
 * 4. [Target.NONE] — Android 9 without the permission and no folder chosen.
 *
 * All methods except [target]/[label] do IO and must run off the main thread.
 */
object MemeSaver {

    enum class Target { FOLDER, MEDIA_STORE, LEGACY_PICTURES, NONE }

    sealed class Result {
        data class Saved(val uri: Uri, val label: String) : Result()
        object Unavailable : Result()
        data class Failed(val reason: String) : Result()
    }

    const val DEFAULT_FOLDER = "Pictures/Memetype"
    private const val TAG = "MemeSaver"
    private const val MIME_PNG = "image/png"

    fun target(context: Context, prefs: AppPrefs): Target = when {
        prefs.saveTreeUri != null -> Target.FOLDER
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> Target.MEDIA_STORE
        hasLegacyPermission(context) -> Target.LEGACY_PICTURES
        else -> Target.NONE
    }

    /** Human-readable destination ("Download/Memes", "Pictures/Memetype"), or null when saving is impossible. */
    fun label(context: Context, prefs: AppPrefs): String? = when (target(context, prefs)) {
        Target.FOLDER -> prefs.saveTreeUri?.let { folderLabel(Uri.parse(it)) }
        Target.MEDIA_STORE, Target.LEGACY_PICTURES -> DEFAULT_FOLDER
        Target.NONE -> null
    }

    /** True while the persisted write grant for the chosen tree is still held. */
    fun hasFolderGrant(context: Context, treeUri: Uri): Boolean =
        context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isWritePermission }

    /** Grant still held and the folder itself still exists (a deleted tree keeps its grant). Quick provider query. */
    fun folderAvailable(context: Context, treeUri: Uri): Boolean {
        if (!hasFolderGrant(context, treeUri)) return false
        return try {
            val doc = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() } ?: false
        } catch (e: Exception) {
            false
        }
    }

    fun hasLegacyPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** "primary:Download/Memes" → "Download/Memes"; a bare volume root keeps its id. */
    fun folderLabel(treeUri: Uri): String {
        val id = try { DocumentsContract.getTreeDocumentId(treeUri) } catch (e: Exception) { return treeUri.toString() }
        val path = id.substringAfter(':', "")
        return if (path.isBlank()) id.trimEnd(':') else path
    }

    /** Background thread. Copies [png] to the resolved destination under [displayName]. */
    fun save(context: Context, prefs: AppPrefs, png: File, displayName: String): Result {
        return try {
            when (target(context, prefs)) {
                Target.FOLDER -> saveToFolder(context, Uri.parse(prefs.saveTreeUri!!), png, displayName)
                Target.MEDIA_STORE ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) saveToMediaStore(context, png, displayName)
                    else Result.Unavailable // unreachable: target() only picks MEDIA_STORE on Q+; keeps lint's NewApi check local
                Target.LEGACY_PICTURES -> saveToLegacyPictures(context, png, displayName)
                Target.NONE -> Result.Unavailable
            }
        } catch (e: Exception) {
            Log.w(TAG, "save failed", e)
            Result.Failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun saveToFolder(context: Context, tree: Uri, png: File, displayName: String): Result {
        if (!hasFolderGrant(context, tree)) return Result.Failed("folder access lost")
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val doc = try {
            DocumentsContract.createDocument(resolver, parent, MIME_PNG, displayName)
        } catch (e: FileNotFoundException) {
            null
        } catch (e: SecurityException) {
            null
        } ?: return Result.Failed("folder unavailable")
        try {
            resolver.openOutputStream(doc)?.use { out -> png.inputStream().use { it.copyTo(out) } }
                ?: return Result.Failed("cannot open document")
        } catch (e: Exception) {
            try { DocumentsContract.deleteDocument(resolver, doc) } catch (ignored: Exception) { }
            throw e
        }
        return Result.Saved(doc, folderLabel(tree))
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun saveToMediaStore(context: Context, png: File, displayName: String): Result {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, DEFAULT_FOLDER)
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return Result.Failed("MediaStore insert failed")
        try {
            resolver.openOutputStream(uri)?.use { out -> png.inputStream().use { it.copyTo(out) } }
                ?: throw FileNotFoundException("openOutputStream returned null")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (e: Exception) {
            resolver.delete(uri, null, null)
            throw e
        }
        return Result.Saved(uri, DEFAULT_FOLDER)
    }

    @Suppress("DEPRECATION") // Only reached on API 28, where this is the supported path.
    private fun saveToLegacyPictures(context: Context, png: File, displayName: String): Result {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Memetype")
        if (!dir.isDirectory && !dir.mkdirs()) return Result.Failed("cannot create $dir")
        val out = File(dir, displayName)
        png.copyTo(out, overwrite = true)
        MediaScannerConnection.scanFile(context, arrayOf(out.absolutePath), arrayOf(MIME_PNG), null)
        return Result.Saved(Uri.fromFile(out), DEFAULT_FOLDER)
    }
}
