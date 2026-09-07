package com.umo.memetype.meme

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Parses a "memekb-pack" v1 document (the bundled templates.json, an installed pack, or the
 * imported-images index). Structural errors throw [JSONException]; callers log and skip the pack.
 */
object PackParser {
    const val FORMAT = "memekb-pack"
    const val VERSION = 1

    class Pack(
        val id: String,
        val name: String,
        val author: String?,
        val licence: String?,
        val templates: List<MemeTemplate>
    )

    fun parse(json: JSONObject, sourceId: String, imageRef: (String) -> ImageRef): Pack {
        val format = json.optString("format", FORMAT)
        if (format != FORMAT) throw JSONException("not a $FORMAT document: $format")
        val version = json.optInt("version", VERSION)
        if (version != VERSION) throw JSONException("unsupported pack version $version")
        val arr = json.getJSONArray("templates")
        val templates = ArrayList<MemeTemplate>(arr.length())
        for (i in 0 until arr.length()) {
            templates.add(parseTemplate(arr.getJSONObject(i), sourceId, imageRef))
        }
        return Pack(
            id = json.optString("id", sourceId),
            name = json.optString("name", sourceId),
            author = json.stringOrNull("author"),
            licence = json.stringOrNull("licence") ?: json.stringOrNull("license"),
            templates = templates
        )
    }

    private fun parseTemplate(o: JSONObject, sourceId: String, imageRef: (String) -> ImageRef): MemeTemplate {
        val id = o.getString("id")
        val file = o.getString("file")
        val examples = o.optJSONArray("example")
        val texts = o.optJSONArray("text")
        val boxes: List<TextBoxSpec> = when {
            texts != null && texts.length() > 0 -> List(texts.length()) { i ->
                val example = examples?.optString(i, "") ?: ""
                TextBoxSpec.fromJson(texts.getJSONObject(i), fallbackHint = example.ifBlank { "Text ${i + 1}" })
            }
            o.has("boxes") -> DefaultLayouts.forCount(o.getInt("boxes"))
            else -> DefaultLayouts.classic()
        }
        val tags = stringList(o.optJSONArray("keywords") ?: o.optJSONArray("tags"))
        return MemeTemplate(
            sourceId = sourceId,
            id = id,
            name = o.optString("name", id),
            image = imageRef(file),
            boxes = boxes,
            width = o.optInt("width", 0),
            height = o.optInt("height", 0),
            tags = tags,
            source = o.stringOrNull("source"),
            licence = o.stringOrNull("licence") ?: o.stringOrNull("license")
        )
    }

    private fun stringList(arr: JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return List(arr.length()) { arr.optString(it, "") }.filter { it.isNotBlank() }
    }

    private fun JSONObject.stringOrNull(key: String): String? =
        if (has(key) && !isNull(key)) getString(key) else null
}
