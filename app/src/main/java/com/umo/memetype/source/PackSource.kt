package com.umo.memetype.source

import android.content.Context
import com.umo.memetype.meme.ImageRef
import com.umo.memetype.meme.MemeTemplate
import com.umo.memetype.meme.PackParser
import org.json.JSONObject
import java.io.File

/**
 * A "memekb-pack" v1 document plus its images: the bundled pack in assets or an installed pack
 * under filesDir/packs/<dir>/. Name and description come from the pack itself once loaded.
 */
class PackSource(
    override val id: String,
    private val fallbackName: String,
    override val removable: Boolean,
    private val json: () -> JSONObject,
    private val imageRef: (String) -> ImageRef
) : MemeSource {

    @Volatile private var pack: PackParser.Pack? = null

    override val displayName: String get() = pack?.name ?: fallbackName

    override val description: String
        get() = pack?.let { p -> listOfNotNull(p.author, p.licence).joinToString(" · ") } ?: ""

    override fun load(): List<MemeTemplate> {
        val p = PackParser.parse(json(), id, imageRef)
        pack = p
        return p.templates
    }

    companion object {
        const val PACK_FILE = "pack.json"
        const val ID_PREFIX = "pack:"

        /** The templates shipped in the APK (assets/templates/templates.json). */
        fun bundled(context: Context): PackSource = PackSource(
            id = MemeTemplate.BUNDLED_SOURCE,
            fallbackName = "Bundled",
            removable = false,
            json = { JSONObject(context.assets.open("templates/templates.json").bufferedReader().use { it.readText() }) },
            imageRef = { ImageRef.Asset(it) }
        )

        /** A pack unpacked by [PackInstaller] into [dir]; image paths in pack.json are relative to it. */
        fun installed(dir: File): PackSource = PackSource(
            id = ID_PREFIX + dir.name,
            fallbackName = dir.name,
            removable = true,
            json = { JSONObject(File(dir, PACK_FILE).readText()) },
            imageRef = { ImageRef.LocalFile(File(dir, it)) }
        )
    }
}
