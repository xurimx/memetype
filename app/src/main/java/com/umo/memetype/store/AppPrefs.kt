package com.umo.memetype.store

import android.content.Context
import android.content.SharedPreferences
import com.umo.memetype.meme.MemeTemplate

/**
 * All user preferences and small state, in the one SharedPreferences file the app has always
 * used. The IME and the activities run in the same process, so reads are always current.
 */
class AppPrefs(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Most recent first. Legacy entries (plain ids from before sources existed) map to the bundled source. */
    val recentKeys: List<String>
        get() = (prefs.getString(KEY_RECENT, "") ?: "")
            .split(',')
            .filter { it.isNotBlank() }
            .map { if (it.contains('/')) it else "${MemeTemplate.BUNDLED_SOURCE}/$it" }

    fun markRecent(key: String) {
        val list = (listOf(key) + recentKeys.filter { it != key }).take(MAX_RECENT)
        prefs.edit().putString(KEY_RECENT, list.joinToString(",")).apply()
    }

    var saveSentMemes: Boolean
        get() = prefs.getBoolean(KEY_SAVE_SENT, true)
        set(value) { prefs.edit().putBoolean(KEY_SAVE_SENT, value).apply() }

    /** Tree URI of the user-chosen save folder; null = default destination. */
    var saveTreeUri: String?
        get() = prefs.getString(KEY_SAVE_TREE, null)
        set(value) { prefs.edit().putString(KEY_SAVE_TREE, value).apply() }

    var watermarkEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATERMARK, true)
        set(value) { prefs.edit().putBoolean(KEY_WATERMARK, value).apply() }

    var historyEnabled: Boolean
        get() = prefs.getBoolean(KEY_HISTORY, true)
        set(value) { prefs.edit().putBoolean(KEY_HISTORY, value).apply() }

    var sourcesDisabled: Set<String>
        get() = prefs.getStringSet(KEY_SOURCES_DISABLED, emptySet()) ?: emptySet()
        set(value) { prefs.edit().putStringSet(KEY_SOURCES_DISABLED, HashSet(value)).apply() }

    var sourcesOrder: List<String>
        get() = (prefs.getString(KEY_SOURCES_ORDER, "") ?: "").split(',').filter { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_SOURCES_ORDER, value.joinToString(",")).apply() }

    /** Bumped on every source mutation; the repository reloads when it changes. */
    var sourcesVersion: Int
        get() = prefs.getInt(KEY_SOURCES_VERSION, 0)
        set(value) { prefs.edit().putInt(KEY_SOURCES_VERSION, value).apply() }

    var packRepos: List<String>
        get() = (prefs.getString(KEY_PACK_REPOS, "") ?: "").split(',').filter { it.isNotBlank() }
        set(value) { prefs.edit().putString(KEY_PACK_REPOS, value.joinToString(",")).apply() }

    companion object {
        const val FILE = "meme_kb"
        const val MAX_RECENT = 12
        private const val KEY_RECENT = "recent_ids"
        private const val KEY_SAVE_SENT = "save_sent_memes"
        private const val KEY_SAVE_TREE = "save_tree_uri"
        private const val KEY_WATERMARK = "watermark_enabled"
        private const val KEY_HISTORY = "history_enabled"
        private const val KEY_SOURCES_DISABLED = "sources_disabled"
        private const val KEY_SOURCES_ORDER = "sources_order"
        private const val KEY_SOURCES_VERSION = "sources_version"
        private const val KEY_PACK_REPOS = "pack_repos"
    }
}
