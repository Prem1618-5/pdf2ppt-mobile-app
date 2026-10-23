package app.papertoslides.core.compose

import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.imaging.Raster
import kotlin.math.max
import kotlin.math.min

/**
 * Figures, diagrams and tables inside a question crop = ink that is not text. Text line boxes (from the PDF text
 * layer or OCR) are blanked, the remaining ink is split into connected components, big ones are kept and merged
 * with their neighbours. Text lines inside a figure (labels A B C D, table cells) become part of the figure.
 */
object FigureFinder {
    data class Rect(var l: Int, var t: Int, var r: Int, var b: Int) {
        val w get() = r - l
        val h get() = b - t
        fun grow(d: Int) = Rect(l - d, t - d, r + d, b + d)
        fun intersects(o: Rect) = l < o.r && o.l < r && t < o.b && o.t < b
        fun contains(x: Int, y: Int) = x in l..r && y in t..b
        fun union(o: Rect) { l = min(l, o.l); t = min(t, o.t); r = max(r, o.r); b = max(b, o.b) }
    }

    data class Result(val figures: List<Rect>, val absorbed: Set<Int>)

    /**
     * @param text text line rectangles in crop pixels; @param lh median text line height in pixels.
     */
    fun find(img: Raster, text: List<Rect>, lh: Int): Result {
        val f = max(1.0, max(img.w, img.h) / 1400.0)
        val sm = if (f > 1.0) img.resize((img.w / f).toInt().coerceAtLeast(1), (img.h / f).toInt().coerceAtLeast(1)) else img
        val w = sm.w; val h = sm.h
        val ink = Imaging.adaptiveInk(sm.gray(), w, h, 35, 18)
        // also dark solid areas (filled shapes) the adaptive threshold hollows out
        val g = sm.gray()
        for (i in g.indices) if ((g[i].toInt() and 255) < 140) ink.on[i] = true
        val slh = max(2, (lh / f).toInt())
        val pad = max(1, (0.2 * slh).toInt())
        for (r in text) {
            for (y in max(0, (r.t / f).toInt() - pad) until min(h, (r.b / f).toInt() + pad))
                for (x in max(0, (r.l / f).toInt() - pad) until min(w, (r.r / f).toInt() + pad)) ink.on[y * w + x] = false
        }
        // connected components
        val label = IntArray(w * h)
        val stack = IntArray(w * h)
        val comps = mutableListOf<Pair<Rect, Int>>()
        var next = 1
        for (s in 0 until w * h) {
            if (!ink.on[s] || label[s] != 0) continue
            var sp = 0; stack[sp++] = s; label[s] = next
            val r = Rect(w, h, 0, 0); var n = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                n++; r.l = min(r.l, x); r.t = min(r.t, y); r.r = max(r.r, x + 1); r.b = max(r.b, y + 1)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (ink.on[q] && label[q] == 0) { label[q] = next; stack[sp++] = q }
                }
            }
            next++
            comps += r to n
        }
        // keep 2-D shapes (not underlines, not specks)
        val keep = comps.filter { (r, n) -> r.w >= 0.8 * slh && r.h >= 0.8 * slh && n >= 12 }.map { it.first }.toMutableList()
        // merge neighbours
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in keep.indices) for (j in i + 1 until keep.size) {
                if (keep[i].grow((0.6 * slh).toInt()).intersects(keep[j])) { keep[i].union(keep[j]); keep.removeAt(j); merged = true; break@loop }
            }
        }
        val figs = keep.filter { it.w * it.h >= (1.2 * slh) * (1.2 * slh) }.map { Rect((it.l * f).toInt(), (it.t * f).toInt(), (it.r * f).toInt(), (it.b * f).toInt()) }.toMutableList()
        // text inside / touching a figure belongs to it (labels, table cells)
        val absorbed = HashSet<Int>()
        var changed = true
        while (changed) {
            changed = false
            for ((k, r) in text.withIndex()) {
                if (k in absorbed) continue
                val cx = (r.l + r.r) / 2; val cy = (r.t + r.b) / 2
                val fig = figs.firstOrNull { it.grow(lh).contains(cx, cy) && r.w < 4 * lh } ?: figs.firstOrNull { it.grow((0.3 * lh).toInt()).contains(cx, cy) } ?: continue
                absorbed += k; fig.union(r); changed = true
            }
        }
        return Result(figs.map { it.grow(max(2, lh / 6)).let { r -> Rect(max(0, r.l), max(0, r.t), min(img.w, r.r), min(img.h, r.b)) } }, absorbed)
    }
}
