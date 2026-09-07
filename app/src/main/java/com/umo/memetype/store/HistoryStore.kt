package com.umo.memetype.store

import android.content.Context
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.TextBox
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Per-template history of memes the user sent or saved: box geometry plus texts, newest first,
 * capped at [MAX_PER_TEMPLATE]. One JSON file per template key under filesDir/history:
 * `{"version":1,"key":"bundled/drake","entries":[{"ts":…,"saved":"content://…","boxes":[spec + "value"]}]}`.
 *
 * Whole-file rewrites through [JsonStore] (tmp + rename), so a concurrent reader in the same
 * process (the editor) always sees a complete file. Callers gate on `AppPrefs.historyEnabled`.
 */
class HistoryStore(context: Context) {

    data class Entry(val ts: Long, val boxes: List<TextBox>, val savedUri: String?)

    private val dir = File(context.filesDir, "history")

    fun list(key: String): List<Entry> = read(key)

    fun count(key: String): Int = read(key).size

    /**
     * Prepends an entry unless every text is blank or it repeats the newest entry
     * (same texts and geometry). Returns true when something was written.
     */
    fun append(key: String, boxes: List<TextBox>, savedUri: String?): Boolean {
        if (boxes.all { it.text.isBlank() }) return false
        val current = read(key)
        val newest = current.firstOrNull()
        if (newest != null && newest.boxes == boxes) {
            // Same meme again: only remember the saved location if it gained one.
            if (newest.savedUri == null && savedUri != null) {
                write(key, listOf(newest.copy(savedUri = savedUri)) + current.drop(1))
                return true
            }
            return false
        }
        val entry = Entry(System.currentTimeMillis(), boxes.map { it.copy() }, savedUri)
        write(key, (listOf(entry) + current).take(MAX_PER_TEMPLATE))
        return true
    }

    fun remove(key: String, ts: Long) {
        val remaining = read(key).filter { it.ts != ts }
        if (remaining.isEmpty()) JsonStore.delete(file(key)) else write(key, remaining)
    }

    fun clear(key: String) = JsonStore.delete(file(key))

    fun clearAll() {
        dir.listFiles()?.forEach { it.delete() }
        dir.delete()
    }

    // ---- io ---------------------------------------------------------------------

    private fun read(key: String): List<Entry> {
        val json = JsonStore.read(file(key)) ?: return emptyList()
        val arr = json.optJSONArray("entries") ?: return emptyList()
        val out = ArrayList<Entry>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val boxes = TextBox.listFromJson(o.optJSONArray("boxes"))
            if (boxes.isEmpty()) continue
            out += Entry(o.optLong("ts"), boxes, o.optString("saved", "").ifBlank { null })
        }
        return out
    }

    private fun write(key: String, entries: List<Entry>) {
        val arr = JSONArray()
        entries.forEach { e ->
            arr.put(
                JSONObject()
                    .put("ts", e.ts)
                    .put("saved", e.savedUri ?: JSONObject.NULL)
                    .put("boxes", TextBox.listToJson(e.boxes))
            )
        }
        JsonStore.write(file(key), JSONObject().put("version", 1).put("key", key).put("entries", arr))
    }

    private fun file(key: String) = File(dir, MemeTemplate.safeFileName(key) + ".json")

    companion object {
        const val MAX_PER_TEMPLATE = 30
    }
}
