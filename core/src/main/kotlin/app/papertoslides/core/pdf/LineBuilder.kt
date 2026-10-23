package app.papertoslides.core.pdf

import app.papertoslides.core.model.TextLine
import app.papertoslides.core.model.TextRun
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One glyph from a PDF text extractor, in page points, y down. */
data class Glyph(val x0: Double, val x1: Double, val top: Double, val bottom: Double, val baseline: Double, val size: Double, val text: String)

/**
 * Groups glyphs into text lines like PyMuPDF's `get_text("dict")` lines: same baseline, no big horizontal gap
 * (a column gutter splits a line). Small raised / lowered glyphs (x², H₂O) are attached to their line as
 * superscript / subscript runs instead of becoming lines of their own. Output in page fractions.
 */
object LineBuilder {
    private class Row(val main: MutableList<Glyph>) {
        val baseline get() = main[0].baseline
        val size get() = main.maxOf { it.size }
        val top get() = main.minOf { it.top }
        val bottom get() = main.maxOf { it.bottom }
        val x0 get() = main.minOf { it.x0 }
        val x1 get() = main.maxOf { it.x1 }
        val extra = mutableListOf<Pair<Glyph, Int>>() // small glyph + rise
    }

    fun build(glyphs: List<Glyph>, pageW: Double, pageH: Double): List<TextLine> {
        val gs = glyphs.filter { it.text.isNotEmpty() && it.size > 0 }
        if (gs.isEmpty()) return emptyList()
        val sizes = gs.filter { it.text.isNotBlank() }.map { it.size }.sorted()
        val median = if (sizes.isEmpty()) gs[0].size else sizes[sizes.size / 2]
        val (mainG, small) = gs.partition { it.size >= 0.75 * median }
        // cluster main glyphs by baseline
        val rows = mutableListOf<Row>()
        for (g in mainG.sortedWith(compareBy({ it.baseline }, { it.x0 }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(row.baseline - g.baseline) < 0.35 * max(row.main[0].size, g.size)) row.main += g else rows += Row(mutableListOf(g))
        }
        // small glyphs: superscript / subscript of the row they sit in, else rows of their own
        val loose = mutableListOf<Glyph>()
        for (g in small) {
            val cy = (g.top + g.bottom) / 2
            val row = rows.filter { r -> cy >= r.top - 0.25 * r.size && cy <= r.bottom + 0.25 * r.size && g.x0 >= r.x0 - 2 * r.size && g.x1 <= r.x1 + 4 * r.size }
                .minByOrNull { abs(it.baseline - g.baseline) }
            if (row == null) { loose += g; continue }
            val d = row.baseline - g.baseline
            val rise = when { d > 0.15 * row.size -> 1; d < -0.1 * row.size -> -1; else -> 0 }
            row.extra += g to rise
        }
        for (g in loose.sortedWith(compareBy({ it.baseline }, { it.x0 }))) {
            val row = rows.firstOrNull { r -> r.extra.isEmpty() && abs(r.baseline - g.baseline) < 0.35 * max(r.main[0].size, g.size) && r.main.all { it.size < 0.75 * median } }
            if (row != null) row.main += g else rows += Row(mutableListOf(g))
        }
        val out = mutableListOf<TextLine>()
        for (row in rows) {
            val all = (row.main.map { it to 0 } + row.extra).sortedBy { it.first.x0 }
            var seg = mutableListOf<Pair<Glyph, Int>>()
            fun flush() {
                val runs = mutableListOf<TextRun>()
                val sb = StringBuilder()
                var prev: Glyph? = null
                for ((g, rise) in seg) {
                    var t = g.text
                    if (prev != null && g.x0 - prev.x1 > 0.15 * max(g.size, prev.size) && !sb.endsWith(" ") && t != " " && rise == 0) t = " $t"
                    if (runs.isNotEmpty() && runs.last().rise == rise) runs[runs.size - 1] = TextRun(runs.last().text + t, rise) else runs += TextRun(t, rise)
                    sb.append(t)
                    prev = g
                }
                val text = sb.toString().replace(WS, " ").trim()
                if (text.isNotEmpty()) {
                    val vis = seg.map { it.first }.filter { it.text.isNotBlank() }
                    val mainVis = seg.filter { it.second == 0 }.map { it.first }.filter { it.text.isNotBlank() }.ifEmpty { vis }
                    val cleanRuns = normaliseRuns(runs)
                    out += TextLine(
                        vis.minOf { it.x0 } / pageW, mainVis.minOf { it.top } / pageH,
                        vis.maxOf { it.x1 } / pageW, mainVis.maxOf { it.bottom } / pageH,
                        text, mainVis.maxOf { it.size },
                        runs = if (cleanRuns.size == 1 && cleanRuns[0].rise == 0) emptyList() else cleanRuns,
                    )
                }
                seg = mutableListOf()
            }
            for (p in all) {
                val last = seg.lastOrNull { it.first.text.isNotBlank() }?.first
                val g = p.first
                if (last != null && g.text.isNotBlank() && g.x0 - last.x1 > 2.0 * maxOf(last.size, g.size, row.size * 0.5)) flush()
                seg += p
            }
            flush()
        }
        return out.map { it.copy(x0 = it.x0.coerceIn(0.0, 1.0), y0 = it.y0.coerceIn(0.0, 1.0), x1 = min(1.0, it.x1), y1 = min(1.0, it.y1)) }
            .sortedWith(compareBy({ it.y0 }, { it.x0 }))
    }

    /** Collapse whitespace like the plain text, keeping super/subscript boundaries. */
    private fun normaliseRuns(runs: List<TextRun>): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (r in runs) {
            var t = r.text.replace(WS, " ")
            if (out.isEmpty()) t = t.trimStart()
            if (t.isEmpty()) continue
            if (out.isNotEmpty() && out.last().text.endsWith(" ") && t.startsWith(" ")) t = t.trimStart()
            if (out.isNotEmpty() && out.last().rise == r.rise) out[out.size - 1] = TextRun(out.last().text + t, r.rise) else out += TextRun(t, r.rise)
        }
        if (out.isNotEmpty()) out[out.size - 1] = out.last().copy(text = out.last().text.trimEnd())
        return out.filter { it.text.isNotEmpty() }
    }

    private val WS = Regex("\\s+")
}
