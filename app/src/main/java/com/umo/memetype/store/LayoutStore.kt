package com.umo.memetype.store

import android.content.Context
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TextBoxSpec
import org.json.JSONObject
import java.io.File

/**
 * Per-template box layout overrides (positions and sizes the user changed in the editor),
 * one JSON file per template key under filesDir/layouts. Texts are never stored here.
 */
class LayoutStore(context: Context) {

    private val dir = File(context.filesDir, "layouts")

    fun load(key: String): List<TextBoxSpec>? {
        val json = JsonStore.read(file(key)) ?: return null
        return TextBoxSpec.listFromJson(json.optJSONArray("boxes")).ifEmpty { null }
    }

    fun save(key: String, specs: List<TextBoxSpec>) {
        val json = JSONObject()
            .put("version", 1)
            .put("key", key)
            .put("boxes", TextBoxSpec.listToJson(specs))
        JsonStore.write(file(key), json)
    }

    fun delete(key: String) = JsonStore.delete(file(key))

    private fun file(key: String) = File(dir, MemeTemplate.safeFileName(key) + ".json")
}
