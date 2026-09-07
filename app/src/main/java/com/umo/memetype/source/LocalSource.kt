package com.umo.memetype.source

import android.content.Context
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.PackParser
import com.umo.memetype.meme.TextBoxSpec
import com.umo.memetype.store.JsonStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * "Mine": images the user imported, with the text-box layout they drew. The index is a pack v1
 * document (filesDir/imported/index.json) so the same parser and format apply; images sit next
 * to it as `<uuid>.png`. All methods do IO.
 */
class LocalSource(context: Context) : MemeSource {

    override val id: String = ID
    override val displayName: String = "Mine"
    override val description: String = "Images you imported"
    override val removable: Boolean = false

    private val dir = File(context.filesDir, "imported")
    private val index = File(dir, "index.json")

    override fun load(): List<MemeTemplate> {
        val json = JsonStore.read(index) ?: return emptyList()
        return PackParser.parse(json, ID) { ImageRef.LocalFile(File(dir, it)) }.templates
    }

    /** A fresh, unused file for a new image (the caller writes the PNG, then calls [add]). */
    fun newImageFile(): File {
        dir.mkdirs()
        return File(dir, UUID.randomUUID().toString() + ".png")
    }

    fun add(name: String, imageFile: File, width: Int, height: Int, boxes: List<TextBoxSpec>): MemeTemplate {
        val templateId = imageFile.nameWithoutExtension
        val entry = JSONObject()
            .put("id", templateId)
            .put("name", name)
            .put("file", imageFile.name)
            .put("width", width)
            .put("height", height)
            .put("text", TextBoxSpec.listToJson(boxes))
        val json = readOrEmpty()
        json.getJSONArray("templates").put(entry)
        JsonStore.write(index, json)
        return MemeTemplate(
            sourceId = ID, id = templateId, name = name,
            image = ImageRef.LocalFile(imageFile), boxes = boxes, width = width, height = height
        )
    }

    fun updateBoxes(templateId: String, boxes: List<TextBoxSpec>) {
        val json = readOrEmpty()
        val arr = json.getJSONArray("templates")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == templateId) {
                o.put("text", TextBoxSpec.listToJson(boxes))
                JsonStore.write(index, json)
                return
            }
        }
    }

    fun remove(templateId: String) {
        val json = readOrEmpty()
        val arr = json.getJSONArray("templates")
        val kept = JSONArray()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optString("id") == templateId) {
                File(dir, o.optString("file")).delete()
            } else {
                kept.put(o)
            }
        }
        json.put("templates", kept)
        if (kept.length() == 0) JsonStore.delete(index) else JsonStore.write(index, json)
    }

    private fun readOrEmpty(): JSONObject = JsonStore.read(index) ?: JSONObject()
        .put("format", PackParser.FORMAT)
        .put("version", PackParser.VERSION)
        .put("id", ID)
        .put("name", displayName)
        .put("templates", JSONArray())

    companion object {
        const val ID = "local"
    }
}
