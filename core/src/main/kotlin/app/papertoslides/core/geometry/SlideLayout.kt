package app.papertoslides.core.geometry

import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min

const val SLIDE_W = 13.333
const val SLIDE_H = 7.5
const val INSET = 0.15
const val MAX_PT = 36.0          // never blow a short question up beyond ~36 pt type
const val SMALL_TEXT_PT = 12.0   // below: flag "small_text"
const val ILLEGIBLE_PT = 7.0     // below: flag "too_small_to_read"
const val EMU_PER_IN = 914400.0

/** Rectangle in inches. */
@Serializable
data class RectIn(val x: Double, val y: Double, val w: Double, val h: Double)

/** The only two layouts (owner requirement R2): top half full width, or quadrant I (top right). */
@Serializable
enum class SlideLayout(val label: String, val centred: Boolean) {
    TOP("Top", centred = true),
    TOP_RIGHT("Top right", centred = false);

    /**
     * The question box inside the template's question [area]: TOP = top half (full width), TOP_RIGHT = quadrant I.
     * [expanded] (passages, long questions) lets the question take the full height: TOP -> the whole area,
     * TOP_RIGHT -> quadrants I + IV (the right half).
     */
    fun box(area: RectIn = RectIn(0.0, 0.0, SLIDE_W, SLIDE_H), expanded: Boolean = false): RectIn {
        val h = if (expanded) area.h - 2 * INSET else area.h / 2 - 2 * INSET
        return when (this) {
            TOP -> RectIn(area.x + INSET, area.y + INSET, area.w - 2 * INSET, h)
            TOP_RIGHT -> RectIn(area.x + area.w / 2 + INSET, area.y + INSET, area.w / 2 - 2 * INSET, h)
        }
    }
}

/** Where a picture goes on the slide, and the estimated font size it is shown at. */
data class Placement(val rect: RectIn, val displayPt: Double?)

object Geometry {
    /**
     * Legacy `add_snapshot`: scale to fit the box keeping aspect ratio, top-aligned; centred for TOP, left edge for
     * TOP_RIGHT. Short questions are capped at [MAX_PT]. Never crosses the centre lines.
     * @param srcWidthIn width of the picture on the original paper, @param glyphPt median glyph height in source points.
     */
    fun place(layout: SlideLayout, wPx: Int, hPx: Int, srcWidthIn: Double? = null, glyphPt: Double? = null): Placement =
        placeIn(layout.box(), layout.centred, wPx, hPx, srcWidthIn, glyphPt)

    /** Same as [place] for any box (template question area, expanded box). */
    fun placeIn(b: RectIn, centred: Boolean, wPx: Int, hPx: Int, srcWidthIn: Double? = null, glyphPt: Double? = null): Placement {
        val scale = min(b.w / max(1, wPx), b.h / max(1, hPx))
        var w = wPx * scale
        var h = hPx * scale
        var disp: Double? = null
        if (srcWidthIn != null && srcWidthIn > 0 && glyphPt != null && glyphPt > 0) {
            disp = glyphPt / 0.52 * (w / srcWidthIn)
            if (disp > MAX_PT) {
                val f = MAX_PT / disp
                w *= f; h *= f; disp = MAX_PT
            }
        }
        val left = if (centred) b.x + (b.w - w) / 2 else b.x
        return Placement(RectIn(left, b.y, w, h), disp?.let { Math.round(it * 10) / 10.0 })
    }

    /** Flags from the displayed font size (legacy `export`). */
    fun sizeFlags(displayPt: Double?): List<String> = when {
        displayPt == null -> emptyList()
        displayPt < ILLEGIBLE_PT -> listOf("too_small_to_read")
        displayPt < SMALL_TEXT_PT -> listOf("small_text")
        else -> emptyList()
    }

    /**
     * Pixels per *paper* inch so the picture has [outDpi] px per inch at the size it is shown on the slide
     * (legacy `target_dpi`). Depends on the layout, so the render cache key must include it.
     * @param parts (width, height) of each part on the paper, in inches.
     */
    fun targetDpi(layout: SlideLayout, parts: List<Pair<Double, Double>>, outDpi: Int = 250, cap: Int = 600, box: RectIn = layout.box()): Int {
        val b = box
        var w = 0.0; var h = 0.0
        for ((pw, ph) in parts) { w = max(w, pw); h += ph + 0.05 }
        if (w <= 0 || h <= 0) return outDpi
        val scale = min(b.w / w, b.h / h)
        return min(cap.toDouble(), max(outDpi.toDouble(), outDpi * scale)).toInt()
    }
}
