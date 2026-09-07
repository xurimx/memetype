package com.umo.memetype.meme

import android.graphics.Color
import org.json.JSONArray
import org.json.JSONObject

enum class Align { START, CENTER, END }
enum class VAlign { TOP, CENTER, BOTTOM }

/**
 * Where and how one caption sits on a template. Geometry is normalised to the image
 * (0..1, top-left anchor) so one spec serves the small preview and the 800 px output.
 * JSON field names follow memegen.link's template config (anchor_x, scale_x, style, ...);
 * our additions are valign, outline, max_lines, max_size and hint.
 */
data class TextBoxSpec(
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val align: Align = Align.CENTER,
    val vAlign: VAlign = VAlign.CENTER,
    val uppercase: Boolean = true,
    val color: Int = Color.WHITE,
    /** Color.TRANSPARENT means no outline. */
    val outlineColor: Int = Color.BLACK,
    /** Key for [MemeRenderer.typefaceFor]: thick (Anton), sans, condensed, serif, mono. */
    val font: String = FONT_THICK,
    /** Degrees, clockwise, about the box centre. */
    val angle: Float = 0f,
    /** 0 = only the box height limits the text. */
    val maxLines: Int = 0,
    /** Largest text size as a fraction of the image height. */
    val maxTextSize: Float = DEFAULT_MAX_TEXT_SIZE,
    /** Placeholder shown for an empty box ("Top text"). */
    val hint: String = ""
) {
    /** Keeps the box inside the image and never smaller than [MIN_SIZE]. */
    fun clamped(): TextBoxSpec {
        val cw = w.coerceIn(MIN_SIZE, 1f)
        val ch = h.coerceIn(MIN_SIZE, 1f)
        return copy(w = cw, h = ch, x = x.coerceIn(0f, 1f - cw), y = y.coerceIn(0f, 1f - ch))
    }

    fun toJson(): JSONObject = JSONObject()
        .put("anchor_x", x.toDouble())
        .put("anchor_y", y.toDouble())
        .put("scale_x", w.toDouble())
        .put("scale_y", h.toDouble())
        .put("align", align.name.lowercase())
        .put("valign", vAlign.name.lowercase())
        .put("style", if (uppercase) "upper" else "none")
        .put("color", colorToJson(color))
        .put("outline", colorToJson(outlineColor))
        .put("font", font)
        .put("angle", angle.toDouble())
        .put("max_lines", maxLines)
        .put("max_size", maxTextSize.toDouble())
        .put("hint", hint)

    companion object {
        const val MIN_SIZE = 0.08f
        const val DEFAULT_MAX_TEXT_SIZE = 1f / 9f
        const val FONT_THICK = "thick"

        fun fromJson(o: JSONObject, fallbackHint: String = ""): TextBoxSpec = TextBoxSpec(
            x = o.optDouble("anchor_x", 0.0).toFloat(),
            y = o.optDouble("anchor_y", 0.0).toFloat(),
            w = o.optDouble("scale_x", 1.0).toFloat(),
            h = o.optDouble("scale_y", 0.25).toFloat(),
            align = parseAlign(o.optString("align", "center")),
            vAlign = parseVAlign(o.optString("valign", "center")),
            uppercase = o.optString("style", "upper").equals("upper", ignoreCase = true),
            color = parseColor(o.optString("color", ""), Color.WHITE),
            outlineColor = parseColor(o.optString("outline", ""), Color.BLACK),
            font = o.optString("font", FONT_THICK).ifBlank { FONT_THICK },
            angle = o.optDouble("angle", 0.0).toFloat(),
            maxLines = o.optInt("max_lines", 0),
            maxTextSize = o.optDouble("max_size", DEFAULT_MAX_TEXT_SIZE.toDouble()).toFloat(),
            hint = o.optString("hint", fallbackHint)
        ).clamped()

        fun listFromJson(arr: JSONArray?): List<TextBoxSpec> {
            if (arr == null) return emptyList()
            return List(arr.length()) { i -> fromJson(arr.getJSONObject(i), "Text ${i + 1}") }
        }

        fun listToJson(specs: List<TextBoxSpec>): JSONArray {
            val arr = JSONArray()
            specs.forEach { arr.put(it.toJson()) }
            return arr
        }

        /** Accepts #RRGGBB / #AARRGGBB, the Android colour names, and "none"; unknown values fall back to [default]. */
        fun parseColor(s: String, default: Int): Int {
            if (s.isBlank()) return default
            if (s.equals("none", ignoreCase = true) || s.equals("transparent", ignoreCase = true)) return Color.TRANSPARENT
            return try {
                Color.parseColor(s)
            } catch (e: IllegalArgumentException) {
                default
            }
        }

        private fun colorToJson(c: Int): String =
            if (c == Color.TRANSPARENT) "none" else String.format("#%08X", c)

        private fun parseAlign(s: String): Align = when (s.lowercase()) {
            "start", "left" -> Align.START
            "end", "right", "stop" -> Align.END
            else -> Align.CENTER
        }

        private fun parseVAlign(s: String): VAlign = when (s.lowercase()) {
            "top" -> VAlign.TOP
            "bottom" -> VAlign.BOTTOM
            else -> VAlign.CENTER
        }
    }
}

/** A text box with its current caption: what the renderer draws and what history stores. */
data class TextBox(val spec: TextBoxSpec, val text: String = "") {
    fun toJson(): JSONObject = spec.toJson().put("value", text)

    companion object {
        fun fromJson(o: JSONObject): TextBox = TextBox(TextBoxSpec.fromJson(o), o.optString("value", ""))

        fun listFromJson(arr: JSONArray?): List<TextBox> {
            if (arr == null) return emptyList()
            return List(arr.length()) { i -> fromJson(arr.getJSONObject(i)) }
        }

        fun listToJson(boxes: List<TextBox>): JSONArray {
            val arr = JSONArray()
            boxes.forEach { arr.put(it.toJson()) }
            return arr
        }
    }
}

/** Layouts used when a template does not define its own text boxes. */
object DefaultLayouts {
    const val TOP_HINT = "Top text"
    const val BOTTOM_HINT = "Bottom text"

    /** The classic meme: exactly the geometry the first renderer used (4% margins, top and bottom blocks). */
    fun classic(): List<TextBoxSpec> = listOf(
        TextBoxSpec(0.04f, 0.04f, 0.92f, 0.28f, vAlign = VAlign.TOP, hint = TOP_HINT),
        TextBoxSpec(0.04f, 0.68f, 0.92f, 0.28f, vAlign = VAlign.BOTTOM, hint = BOTTOM_HINT)
    )

    /** For sources that only know how many captions a template has (Imgflip box_count). */
    fun forCount(n: Int): List<TextBoxSpec> = when {
        n <= 1 -> listOf(TextBoxSpec(0.04f, 0.68f, 0.92f, 0.28f, vAlign = VAlign.BOTTOM, hint = "Text"))
        n == 2 -> classic()
        else -> List(n) { i ->
            val rowH = 1f / n
            TextBoxSpec(0.04f, i * rowH + 0.02f, 0.92f, rowH - 0.04f, hint = "Text ${i + 1}").clamped()
        }
    }

    /** A box the user adds by hand: centred, easy to grab. */
    fun newBox(index: Int): TextBoxSpec = TextBoxSpec(0.1f, 0.4f, 0.8f, 0.2f, hint = "Text ${index + 1}")
}
