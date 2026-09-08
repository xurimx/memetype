package com.umo.memetype.meme

import android.content.Context
import android.graphics.Bitmap
import android.util.Log
import android.util.LruCache
import com.umo.memetype.source.LocalSource
import com.umo.memetype.source.MemeSource
import com.umo.memetype.source.SourceRegistry
import com.umo.memetype.store.AppPrefs
import java.io.InputStream
import java.util.concurrent.Executors

/**
 * All templates of all enabled sources, plus thumbnails and recents.
 *
 * Local sources (bundled pack, "Mine", installed packs) load synchronously on the caller's
 * thread. Remote sources ([MemeSource.remote]) load on their own thread: [all] returns what has
 * arrived so far and kicks off a fetch for anything missing; [onChanged] fires (background
 * thread) when a remote list lands so the grid can refresh. Reloads whenever `sources_version`
 * changes (the Sources screen bumps it after every install, import or toggle; same process, so
 * the preference is always current). Query methods do IO: call them off the main thread.
 */
class TemplateRepository(
    private val context: Context,
    private val prefs: AppPrefs = AppPrefs(context),
    private val registry: SourceRegistry = SourceRegistry(context, prefs)
) {

    /** Set by the grid; invoked on a background thread when remote templates become available. */
    @Volatile var onChanged: (() -> Unit)? = null

    private val lock = Any()
    private var enabledSources: List<MemeSource> = emptyList()
    private var localLoaded: List<MemeTemplate> = emptyList()
    private var loadedVersion = -1
    private val remoteLists = HashMap<String, List<MemeTemplate>>()
    private val remoteLoading = HashSet<String>()
    private val remoteAttemptAt = HashMap<String, Long>()
    private val remoteExecutor = Executors.newSingleThreadExecutor()

    private val thumbCache = object : LruCache<String, Bitmap>(
        (Runtime.getRuntime().maxMemory() / 1024 / 8).toInt()
    ) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount / 1024
    }

    fun all(): List<MemeTemplate> {
        val toLoad = ArrayList<MemeSource>()
        val result: List<MemeTemplate>
        synchronized(lock) {
            val version = prefs.sourcesVersion
            if (version != loadedVersion) {
                enabledSources = registry.enabled()
                localLoaded = enabledSources.filter { !it.remote }.flatMap { safeLoad(it) }
                val enabledIds = enabledSources.map { it.id }.toSet()
                remoteLists.keys.retainAll(enabledIds)
                loadedVersion = version
                thumbCache.evictAll()
            }
            val now = System.currentTimeMillis()
            enabledSources.filter { it.remote }.forEach { source ->
                val list = remoteLists[source.id]
                val retry = list != null && list.isEmpty() && now - (remoteAttemptAt[source.id] ?: 0L) > REMOTE_RETRY_MS
                if (source.id !in remoteLoading && (list == null || retry)) {
                    remoteLoading += source.id
                    remoteAttemptAt[source.id] = now
                    toLoad += source
                }
            }
            result = localLoaded + enabledSources.filter { it.remote }.flatMap { remoteLists[it.id] ?: emptyList() }
        }
        toLoad.forEach { startRemoteLoad(it) }
        return result
    }

    private fun startRemoteLoad(source: MemeSource) {
        if (remoteExecutor.isShutdown) return
        remoteExecutor.execute {
            val list = safeLoad(source)
            synchronized(lock) {
                remoteLoading -= source.id
                if (source.id in enabledSources.map { it.id }) remoteLists[source.id] = list
            }
            if (list.isNotEmpty()) onChanged?.invoke()
        }
    }

    private fun safeLoad(source: MemeSource): List<MemeTemplate> = try {
        source.load()
    } catch (e: Exception) {
        // One broken pack or a dead network must not take the others (or the IME) down.
        Log.e(TAG, "source ${source.id} failed to load", e)
        emptyList()
    }

    fun byKey(key: String): MemeTemplate? = all().firstOrNull { it.key == key }

    /**
     * Local filter over everything loaded; for a non-blank query in [Category.ALL], sources with
     * provider-side search (and credentials) add their hits after the local ones.
     */
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
        val local = base.filter { t ->
            t.name.contains(q, ignoreCase = true) || t.tags.any { it.contains(q, ignoreCase = true) }
        }
        if (category != Category.ALL) return local
        val seen = local.mapTo(HashSet()) { it.key }
        val remote = ArrayList<MemeTemplate>()
        synchronized(lock) { enabledSources.filter { it.remote } }.forEach { source ->
            val creds = registry.credentials.get(source.id)
            if (creds.isEmpty) return@forEach
            try {
                source.search(q, creds)?.forEach { if (seen.add(it.key)) remote += it }
            } catch (e: Exception) {
                Log.w(TAG, "remote search failed for ${source.id}", e)
            }
        }
        return local + remote
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

    /** Opens a template's image; remote refs are resolved (downloaded into the source cache) first. */
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
        const val REMOTE_RETRY_MS = 60_000L
    }
}
