package com.umo.memetype.source.net

import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * The only HTTP client in the app: small blocking helpers on HttpURLConnection for the remote
 * template sources. Always call from a background thread. Errors surface as [IOException].
 */
object Http {

    const val USER_AGENT = "Memetype/0.3 (Android)"
    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 20_000

    fun get(url: String, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, headers)
        try {
            conn.requestMethod = "GET"
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** `application/x-www-form-urlencoded` POST (what the Imgflip API expects). */
    fun postForm(url: String, fields: Map<String, String>, headers: Map<String, String> = emptyMap()): String {
        val conn = open(url, headers)
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=utf-8")
            val body = fields.entries.joinToString("&") { (k, v) ->
                URLEncoder.encode(k, "UTF-8") + "=" + URLEncoder.encode(v, "UTF-8")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            return readBody(conn)
        } finally {
            conn.disconnect()
        }
    }

    /** Streams [url] into [dest] through a temp file, so a failed download never leaves a partial file behind. */
    fun download(url: String, dest: File) {
        dest.parentFile?.mkdirs()
        val tmp = File(dest.path + ".part")
        val conn = open(url, emptyMap())
        try {
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (!tmp.renameTo(dest)) {
                dest.delete()
                if (!tmp.renameTo(dest)) throw IOException("cannot move ${tmp.name} into place")
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        } finally {
            conn.disconnect()
        }
    }

    private fun open(url: String, headers: Map<String, String>): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = CONNECT_TIMEOUT_MS
        conn.readTimeout = READ_TIMEOUT_MS
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.setRequestProperty("Accept", "application/json, image/*;q=0.8, */*;q=0.5")
        headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
        return conn
    }

    /** Reads the body for 2xx and also for error codes (APIs put their message there). */
    private fun readBody(conn: HttpURLConnection): String {
        val code = conn.responseCode
        val stream = if (code in 200..299) conn.inputStream else conn.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
        if (code !in 200..299 && text.isBlank()) throw IOException("HTTP $code")
        return text
    }
}
