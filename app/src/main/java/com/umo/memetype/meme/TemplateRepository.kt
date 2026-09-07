package com.umo.memetype.meme

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.umo.memetype.source.LocalSource
import com.umo.memetype.source.SourceRegistry
import com.umo.memetype.store.AppPrefs
import java.io.InputStream

/**
 * All templates of all enabled sources, plus thumbnails and recents. Reloads whenever
 * `sources_version` changes (the Sources screen bumps it after every install, import or toggle;
 * same process, so the preference is always current). Query methods do IO on first use and
 * after a change: call them off the main thread.
 */
class TemplateRepository(
    private val context: Context,
    private val prefs: AppPrefs = AppPrefs(context),
    private val registry: SourceRegistry = SourceRegistry(context, prefs)
) {

    private val lock = Any()
    private var loaded: List<MemeTemplate> = emptyList()
    private var loadedVersion = -1

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun all(): List<MemeTemplate> = synchronized(lock) {
        val version = prefs.sourcesVersion
        if (version != loadedVersion) {
            loaded = registry.enabled().flatMap { source ->
                try {
                    source.load()
                } catch (e: Exception) {
                    // One broken pack must not take the others (or the IME) down.
                    Log.e(TAG, "source ${source.id} failed to load", e)
                    emptyList()
                }
            }
            loadedVersion = version
            thumbCache.evictAll()
        }
        loaded
    }

    fun byKey(key: String): MemeTemplate? = all().firstOrNull { it.key == key }

    fun search(query: String, category: Category): List<MemeTemplate> {
        val everything = all()
        val base = when (category) {
            Category.ALL -> everything
            Category.RECENT -> prefs.recentKeys.mapNotNull { k -> everything.firstOrNull { it.key == k } }
            Category.MINE -> everything.filter { it.sourceId == LocalSource.ID }
            Category.REACTION -> everything.filter { "reaction" in it.tags }
            Category.ANIMALS -> everything.filter { "animals" in it.tags }
        }
        val q = query.trim()
        if (q.isEmpty()) return base
        return base.filter { t ->
            t.name.contains(q, ignoreCase = true) || t.tags.any { it.contains(q, ignoreCase = true) }
        }
    }

    // ---- recents ----------------------------------------------------------------

    fun markRecent(key: String) = prefs.markRecent(key)

    // ---- bitmaps ----------------------------------------------------------------

    /** Small bitmap for the grid; cached in memory and shared, so callers must not recycle it. Call off the main thread. */
    fun loadThumbnail(template: MemeTemplate, targetPx: Int): Bitmap? {
        val key = "${template.key}@$targetPx"
        thumbCache.get(key)?.let { return it }
        val bmp = Bitmaps.decodeScaled({ open(template) }, targetPx) ?: return null
        thumbCache.put(key, bmp)
        return bmp
    }

    /** Full-size bitmap for rendering, downsampled so width < 2 x maxWidth. Caller owns and recycles it. */
    fun loadBitmap(template: MemeTemplate, maxWidth: Int): Bitmap? =
        Bitmaps.decodeScaled({ open(template) }, maxWidth)

    /** Opens a template's image; remote refs are resolved by their source first. */
    fun open(template: MemeTemplate): InputStream? {
        val ref = when (val image = template.image) {
            is ImageRef.Remote -> registry.byId(template.sourceId)?.resolveImage(template) ?: return null
            else -> image
        }
        return open(ref)
    }

    /** The single I/O chokepoint for local refs. */
    fun open(ref: ImageRef): InputStream? = try {
        when (ref) {
            is ImageRef.Asset -> context.assets.open(ref.path)
            is ImageRef.LocalFile -> ref.file.inputStream()
            is ImageRef.Content -> context.contentResolver.openInputStream(ref.uri)
            is ImageRef.Remote -> null
        }
    } catch (e: Exception) {
        null
    }

    enum class Category { ALL, RECENT, MINE, REACTION, ANIMALS }

    private companion object {
        const val TAG = "TemplateRepository"
    }
}
