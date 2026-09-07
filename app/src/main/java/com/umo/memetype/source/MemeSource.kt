package com.umo.memetype.source

import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate

/**
 * A provider of meme templates. Built-in implementations only (packs, imported images; remote
 * sources later) — no code is ever loaded from outside the APK, so template packs stay data.
 *
 * Ids: `bundled`, `local`, `pack:<dir>`; later `imgflip`, `memegen`. A template's key is
 * `"$sourceId/$templateId"`, so ids must be stable across app updates.
 */
interface MemeSource {
    val id: String
    /** Shown in the Sources screen; may only be final after [load] (packs carry their own name). */
    val displayName: String
    val description: String
    /** True for sources the user can uninstall (installed packs). */
    val removable: Boolean

    /** Background thread. Returns every template of this source; may throw on corrupt data. */
    fun load(): List<MemeTemplate>

    /** Turns a [ImageRef.Remote] into something openable (download to cache). Local sources return the ref itself. */
    fun resolveImage(template: MemeTemplate): ImageRef? = template.image
}
