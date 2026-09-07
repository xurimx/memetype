package com.umo.memetype.store

import android.util.Log
import org.json.JSONObject
import java.io.File

/** Whole-document JSON files written atomically (temp file, then rename). Corrupt files read as null. */
object JsonStore {
    private const val TAG = "JsonStore"

    fun read(file: File): JSONObject? {
        if (!file.isFile) return null
        return try {
            JSONObject(file.readText())
        } catch (e: Exception) {
            Log.w(TAG, "unreadable ${file.name}", e)
            null
        }
    }

    fun write(file: File, json: JSONObject) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(json.toString())
        if (!tmp.renameTo(file)) {
            file.delete()
            tmp.renameTo(file)
        }
    }

    fun delete(file: File) {
        file.delete()
    }
}
