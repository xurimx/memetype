package com.umo.memetype.meme

import android.net.Uri
import java.io.File

/** Where a template image lives. Remote refs are resolved (downloaded) by their source. */
sealed class ImageRef {
    data class Asset(val path: String) : ImageRef()
    data class LocalFile(val file: File) : ImageRef()
    /** Import-time only; never persisted (content URIs expire with the picker grant). */
    data class Content(val uri: Uri) : ImageRef()
    data class Remote(val url: String) : ImageRef()
}

/**
 * One meme template from one source. [boxes] is the default caption layout and is never empty:
 * packs without a layout get [DefaultLayouts.classic] or [DefaultLayouts.forCount].
 */
data class MemeTemplate(
    val sourceId: String,
    val id: String,
    val name: String,
    val image: ImageRef,
    val boxes: List<TextBoxSpec>,
    /** 0 = unknown until decoded. */
    val width: Int = 0,
    val height: Int = 0,
    val tags: List<String> = emptyList(),
    /** Attribution URL (memegen "source"). */
    val source: String? = null,
    val licence: String? = null
) {
    /** Globally unique id; recents, layout overrides and history key on it. */
    val key: String get() = "$sourceId/$id"

    companion object {
        const val BUNDLED_SOURCE = "bundled"

        /** File-system safe name for a template key (keys contain '/' and may contain ':'). */
        fun safeFileName(key: String): String =
            key.replace(Regex("[^A-Za-z0-9._-]"), "_") + "-" + key.hashCode().toUInt().toString(16)
    }
}
