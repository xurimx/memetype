package com.umo.memetype.source

import android.content.Context
import android.net.Uri
import android.util.Log
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.PackParser
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.File
import java.util.UUID
import java.util.zip.ZipInputStream

/**
 * Unpacks and validates a template-pack zip into filesDir/packs/<id>/.
 *
 * Accepted content: `pack.json` at the root (or inside a single top-level folder, which is
 * flattened) plus `.png/.jpg/.jpeg/.webp` images referenced by it. Rejected: paths with `..`,
 * absolute paths, other file types (hidden junk like `.DS_Store` is skipped), more than
 * [MAX_ENTRIES] files or [MAX_BYTES] total. A pack with the id of an installed one replaces it.
 * Backslash separators (PowerShell's Compress-Archive) are normalised.
 */
object PackInstaller {

    sealed class Result {
        data class Installed(val sourceId: String, val name: String, val count: Int) : Result()
        data class Invalid(val reason: String) : Result()
    }

    private const val TAG = "PackInstaller"
    private const val MAX_ENTRIES = 500
    private const val MAX_BYTES = 200L * 1024 * 1024
    private val ALLOWED = setOf("json", "png", "jpg", "jpeg", "webp")

    fun install(context: Context, zip: Uri, packsDir: File): Result {
        packsDir.mkdirs()
        val tmp = File(packsDir, ".tmp-" + UUID.randomUUID())
        try {
            val input = context.contentResolver.openInputStream(zip) ?: return Result.Invalid("cannot open the file")
            var entries = 0
            var total = 0L
            ZipInputStream(BufferedInputStream(input)).use { zin ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val e = zin.nextEntry ?: break
                    if (e.isDirectory) continue
                    val name = e.name.replace('\\', '/').trimStart('/')
                    val segments = name.split('/')
                    if (segments.any { it == ".." || it.isEmpty() }) return Result.Invalid("unsafe path: $name")
                    if (segments.any { it.startsWith(".") || it == "__MACOSX" }) continue
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (ext !in ALLOWED) return Result.Invalid("unsupported file: $name")
                    if (++entries > MAX_ENTRIES) return Result.Invalid("more than $MAX_ENTRIES files")
                    val out = File(tmp, name)
                    out.parentFile?.mkdirs()
                    out.outputStream().use { os ->
                        while (true) {
                            val n = zin.read(buf)
                            if (n < 0) break
                            total += n
                            if (total > MAX_BYTES) return Result.Invalid("larger than 200 MB")
                            os.write(buf, 0, n)
                        }
                    }
                }
            }
            if (entries == 0) return Result.Invalid("empty zip")

            // pack.json at the root, or inside the single top-level folder.
            var root = tmp
            if (!File(root, PackSource.PACK_FILE).isFile) {
                val children = root.listFiles()?.toList() ?: emptyList()
                val only = children.singleOrNull()
                root = if (only != null && only.isDirectory && File(only, PackSource.PACK_FILE).isFile) only
                else return Result.Invalid("${PackSource.PACK_FILE} not found")
            }

            val json = JSONObject(File(root, PackSource.PACK_FILE).readText())
            val packId = json.optString("id", "")
            if (packId.isBlank()) return Result.Invalid("pack has no id")
            val dirName = packId.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val sourceId = PackSource.ID_PREFIX + dirName
            val pack = PackParser.parse(json, sourceId) { ImageRef.LocalFile(File(root, it)) }
            if (pack.templates.isEmpty()) return Result.Invalid("pack has no templates")
            val rootPath = root.canonicalPath + File.separator
            pack.templates.forEach { t ->
                val f = (t.image as ImageRef.LocalFile).file
                if (!f.canonicalPath.startsWith(rootPath)) return Result.Invalid("unsafe image path for ${t.id}")
                if (!f.isFile) return Result.Invalid("missing image for ${t.id}")
            }

            val dest = File(packsDir, dirName)
            dest.deleteRecursively()
            if (!root.renameTo(dest)) {
                root.copyRecursively(dest, overwrite = true)
            }
            return Result.Installed(sourceId, pack.name, pack.templates.size)
        } catch (e: Exception) {
            Log.w(TAG, "install failed", e)
            return Result.Invalid(e.message ?: e.javaClass.simpleName)
        } finally {
            tmp.deleteRecursively()
        }
    }

    fun uninstall(packsDir: File, sourceId: String): Boolean {
        val dirName = sourceId.removePrefix(PackSource.ID_PREFIX)
        if (dirName.isBlank() || dirName.contains('/') || dirName.contains('\\')) return false
        return File(packsDir, dirName).deleteRecursively()
    }
}
