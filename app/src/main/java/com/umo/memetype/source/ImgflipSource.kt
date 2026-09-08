package com.umo.memetype.source

import android.content.Context
import android.util.Log
import com.umo.memetype.meme.DefaultLayouts
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.source.net.Http
import org.json.JSONArray
import org.json.JSONObject

/**
 * Imgflip's public template list (`GET /get_memes`: the 100 most captioned templates, box counts
 * but no positions, so layouts are synthesised) plus, for accounts with API Premium, `search_memes`
 * across their whole catalogue. Listing needs no login; search sends username + password as the
 * API requires. Images are downloaded into the source cache on first use.
 * API: https://imgflip.com/api
 */
class ImgflipSource(context: Context) : MemeSource {

    override val id: String = ID
    override val displayName: String = "Imgflip"
    override val description: String = "100 most popular templates; sign in for full search (API Premium)"
    override val removable: Boolean = false
    override val remote: Boolean = true
    override val auth: AuthScheme = AuthScheme.UsernamePassword(
        helpUrl = "https://imgflip.com/api",
        optional = true,
        unlocks = "Search across all Imgflip templates (needs Imgflip API Premium)"
    )

    private val cache = RemoteCache(context, ID)

    override fun load(): List<MemeTemplate> {
        val index = if (cache.isIndexFresh(RemoteCache.DEFAULT_TTL_MS)) cache.readIndex() else null
        val json = index ?: fetchIndex() ?: cache.readIndex() ?: return emptyList()
        return parse(json.optJSONObject("data")?.optJSONArray("memes"))
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

    override fun search(query: String, credentials: Credentials): List<MemeTemplate>? {
        val user = credentials.username ?: return null
        val pass = credentials.password ?: return null
        val body = JSONObject(Http.postForm(SEARCH_URL, mapOf("username" to user, "password" to pass, "query" to query)))
        if (!body.optBoolean("success")) {
            Log.w(TAG, "search_memes: ${body.optString("error_message")}")
            return null
        }
        return parse(body.optJSONObject("data")?.optJSONArray("memes"))
    }

    override fun testCredentials(credentials: Credentials): String? {
        val user = credentials.username ?: return "Username is empty"
        val pass = credentials.password ?: return "Password is empty"
        val body = JSONObject(Http.postForm(SEARCH_URL, mapOf("username" to user, "password" to pass, "query" to "cat")))
        return if (body.optBoolean("success")) null else body.optString("error_message").ifBlank { "Imgflip rejected the login" }
    }

    private fun fetchIndex(): JSONObject? = try {
        val body = JSONObject(Http.get(LIST_URL))
        if (!body.optBoolean("success")) throw IllegalStateException(body.optString("error_message"))
        cache.writeIndex(body)
        body
    } catch (e: Exception) {
        Log.w(TAG, "get_memes failed", e)
        null
    }

    private fun parse(memes: JSONArray?): List<MemeTemplate> {
        if (memes == null) return emptyList()
        val out = ArrayList<MemeTemplate>(memes.length())
        for (i in 0 until memes.length()) {
            val m = memes.optJSONObject(i) ?: continue
            val memeId = m.optString("id").ifBlank { continue }
            val url = m.optString("url").ifBlank { continue }
            out += MemeTemplate(
                sourceId = ID,
                id = memeId,
                name = m.optString("name", memeId),
                image = ImageRef.Remote(url),
                boxes = DefaultLayouts.forCount(m.optInt("box_count", 2)),
                width = m.optInt("width", 0),
                height = m.optInt("height", 0),
                tags = listOf("imgflip", "popular"),
                source = "https://imgflip.com/memegenerator/$memeId",
                licence = null
            )
        }
        return out
    }

    companion object {
        const val ID = "imgflip"
        private const val TAG = "ImgflipSource"
        private const val LIST_URL = "https://api.imgflip.com/get_memes"
        private const val SEARCH_URL = "https://api.imgflip.com/search_memes"
    }
}
