package com.umo.memetype.source

import android.content.Context
import android.util.Log
import com.umo.memetype.meme.DefaultLayouts
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TextBoxSpec
import com.umo.memetype.source.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * memegen.link (MIT): ~210 templates from `GET /templates/`. The API carries names, keywords,
 * examples and line counts; the text-box geometry lives in the project's `config.yml` files,
 * which the build script converts into `assets/memegen_layouts.json` (same fields as our pack
 * format). Templates without a converted layout fall back to a stacked layout by line count.
 * No authentication. Blank images are served scaled by the API.
 */
class MemegenSource(private val context: Context) : MemeSource {

    override val id: String = ID
    override val displayName: String = "memegen.link"
    override val description: String = "About 200 templates with real text layouts (open source)"
    override val removable: Boolean = false
    override val remote: Boolean = true

    private val cache = RemoteCache(context, ID)
    private val layouts: JSONObject by lazy { loadLayouts() }

    override fun load(): List<MemeTemplate> {
        val index = if (cache.isIndexFresh(RemoteCache.DEFAULT_TTL_MS)) cache.readIndex() else null
        val json = index ?: fetchIndex() ?: cache.readIndex() ?: return emptyList()
        return parse(json.optJSONArray("templates"))
    }

    override fun resolveImage(template: MemeTemplate): ImageRef? {
        val remote = template.image as? ImageRef.Remote ?: return template.image
        return try {
            ImageRef.LocalFile(cache.image(template.id, remote.url))
        } catch (e: Exception) {
            Log.w(TAG, "image download failed for ${template.id}", e)
            null
        }
    }

    private fun fetchIndex(): JSONObject? = try {
        // The endpoint returns a bare array; wrap it so the cache stamp has somewhere to live.
        val body = JSONObject().put("templates", JSONArray(Http.get(LIST_URL)))
        cache.writeIndex(body)
        body
    } catch (e: Exception) {
        Log.w(TAG, "templates fetch failed", e)
        null
    }

    private fun parse(arr: JSONArray?): List<MemeTemplate> {
        if (arr == null) return emptyList()
        val out = ArrayList<MemeTemplate>(arr.length())
        for (i in 0 until arr.length()) {
            val t = arr.optJSONObject(i) ?: continue
            val templateId = t.optString("id").ifBlank { continue }
            val layout = layouts.optJSONObject(templateId)
            val examples = layout?.optJSONArray("example") ?: t.optJSONArray("example")
            val text = layout?.optJSONArray("text")
            val boxes: List<TextBoxSpec> = if (text != null && text.length() > 0) {
                List(text.length()) { n ->
                    val hint = examples?.optString(n, "")?.ifBlank { "Text ${n + 1}" } ?: "Text ${n + 1}"
                    TextBoxSpec.fromJson(text.getJSONObject(n), fallbackHint = hint)
                }
            } else {
                DefaultLayouts.forCount(t.optInt("lines", 2).coerceAtLeast(1))
            }
            val keywords = t.optJSONArray("keywords")
            val tags = ArrayList<String>()
            if (keywords != null) for (k in 0 until keywords.length()) keywords.optString(k).takeIf { it.isNotBlank() }?.let { tags += it }
            tags += "memegen"
            out += MemeTemplate(
                sourceId = ID,
                id = templateId,
                name = t.optString("name", templateId),
                image = ImageRef.Remote("$IMAGE_BASE$templateId.png?width=$IMAGE_WIDTH"),
                boxes = boxes,
                tags = tags,
                source = t.optString("source").ifBlank { null },
                licence = null
            )
        }
        return out
    }

    private fun loadLayouts(): JSONObject = try {
        JSONObject(context.assets.open(LAYOUTS_ASSET).bufferedReader().use { it.readText() })
    } catch (e: Exception) {
        Log.w(TAG, "no $LAYOUTS_ASSET; using line counts", e)
        JSONObject()
    }

    companion object {
        const val ID = "memegen"
        private const val TAG = "MemegenSource"
        private const val LIST_URL = "https://api.memegen.link/templates/"
        private const val IMAGE_BASE = "https://api.memegen.link/images/"
        private const val IMAGE_WIDTH = 800
        const val LAYOUTS_ASSET = "memegen_layouts.json"
    }
}
