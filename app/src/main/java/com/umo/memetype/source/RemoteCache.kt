package com.umo.memetype.source

import android.content.Context
import com.umo.memetype.source.net.Http
import com.umo.memetype.store.JsonStore
import org.json.JSONObject
import java.io.File

/**
 * Per-source disk cache under cacheDir/sources/<sourceId>/: the template index
 * (`index.json` with a `fetched_at` stamp) and downloaded images (`images/<templateId>.<ext>`).
 * Android may clear cacheDir at any time; everything here is re-fetchable.
 */
class RemoteCache(context: Context, sourceId: String) {

    private val dir = File(context.cacheDir, "sources/$sourceId")
    private val indexFile = File(dir, "index.json")
    private val imagesDir = File(dir, "images")

    /** The cached index, or null when missing / unreadable. */
    fun readIndex(): JSONObject? = JsonStore.read(indexFile)

    fun isIndexFresh(ttlMs: Long): Boolean {
        val fetchedAt = readIndex()?.optLong(KEY_FETCHED_AT, 0L) ?: return false
        return fetchedAt > 0 && System.currentTimeMillis() - fetchedAt < ttlMs
    }

    /** Stores [body] (the provider's JSON, wrapped so the stamp travels with it). */
    fun writeIndex(body: JSONObject) {
        JsonStore.write(indexFile, body.put(KEY_FETCHED_AT, System.currentTimeMillis()))
    }

    fun imageFile(templateId: String, url: String): File {
        val ext = url.substringAfterLast('.', "jpg").substringBefore('?').lowercase().take(5)
            .ifBlank { "jpg" }
        val safe = templateId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return File(imagesDir, "$safe.$ext")
    }

    /** Returns the local copy of [url], downloading it on first use. Background thread. */
    fun image(templateId: String, url: String): File {
        val file = imageFile(templateId, url)
        if (file.isFile && file.length() > 0) return file
        Http.download(url, file)
        return file
    }

    fun clear() {
        dir.deleteRecursively()
    }

    companion object {
        const val KEY_FETCHED_AT = "fetched_at"
        const val DEFAULT_TTL_MS = 24L * 60 * 60 * 1000
    }
}
