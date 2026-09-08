package com.umo.memetype.source

import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate

/** How a source authenticates, if at all. Credentials are typed once in the Sources screen and kept in `source_auth` prefs. */
sealed class AuthScheme {
    object None : AuthScheme()

    /** A single secret (Giphy / Tenor style). [label] names it in the UI, [helpUrl] is where to get one. */
    data class ApiKey(val label: String, val helpUrl: String) : AuthScheme()

    /**
     * Account login sent with each authenticated request (Imgflip style). [optional] sources work
     * without it; [unlocks] tells the user what signing in adds.
     */
    data class UsernamePassword(val helpUrl: String, val optional: Boolean, val unlocks: String) : AuthScheme()
}

data class Credentials(val username: String? = null, val password: String? = null, val apiKey: String? = null) {
    val isEmpty: Boolean get() = username == null && password == null && apiKey == null
}

sealed class AuthStatus {
    object NotNeeded : AuthStatus()
    object Missing : AuthStatus()
    data class Ready(val label: String) : AuthStatus()
}

/**
 * A provider of meme templates. Built-in implementations only (packs, imported images, the
 * Imgflip and memegen.link APIs) — no code is ever loaded from outside the APK, so template
 * packs stay data and the network sources are fixed at build time.
 *
 * Ids: `bundled`, `local`, `pack:<dir>`, `imgflip`, `memegen`. A template's key is
 * `"$sourceId/$templateId"`, so ids must be stable across app updates.
 */
interface MemeSource {
    val id: String
    /** Shown in the Sources screen; may only be final after [load] (packs carry their own name). */
    val displayName: String
    val description: String
    /** True for sources the user can uninstall (installed packs). */
    val removable: Boolean

    /** Network source: the repository loads it asynchronously and caches its index. */
    val remote: Boolean get() = false

    val auth: AuthScheme get() = AuthScheme.None

    /** Background thread. Returns every template of this source; may throw on corrupt data or network failure. */
    fun load(): List<MemeTemplate>

    /** Turns a [ImageRef.Remote] into something openable (download to cache). Local sources return the ref itself. */
    fun resolveImage(template: MemeTemplate): ImageRef? = template.image

    /**
     * Provider-side search, for sources whose full catalogue is not in [load]. Null = not supported
     * (or credentials missing); the repository then filters the loaded templates locally.
     */
    fun search(query: String, credentials: Credentials): List<MemeTemplate>? = null

    /** Background thread. Null when the credentials work, else the provider's own error text. */
    fun testCredentials(credentials: Credentials): String? = null
}
