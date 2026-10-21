package app.papertoslides.core.pdf

import app.papertoslides.core.model.TextLine
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** One glyph from a PDF text extractor, in page points, y down. */
data class Glyph(val x0: Double, val x1: Double, val top: Double, val bottom: Double, val baseline: Double, val size: Double, val text: String)

/**
 * Groups glyphs into text lines like PyMuPDF's `get_text("dict")` lines: same baseline, no big horizontal gap
 * (a column gutter splits a line). Output in page fractions, the `text_lines()` contract of the parser.
 */
object LineBuilder {
    fun build(glyphs: List<Glyph>, pageW: Double, pageH: Double): List<TextLine> {
        val gs = glyphs.filter { it.text.isNotEmpty() && it.size > 0 }
        if (gs.isEmpty()) return emptyList()
        // cluster by baseline
        val rows = mutableListOf<MutableList<Glyph>>()
        for (g in gs.sortedWith(compareBy({ it.baseline }, { it.x0 }))) {
            val row = rows.lastOrNull()
            if (row != null && abs(row[0].baseline - g.baseline) < 0.35 * max(row[0].size, g.size)) row += g else rows += mutableListOf(g)
        }
        val out = mutableListOf<TextLine>()
        for (row in rows) {
            row.sortBy { it.x0 }
            var seg = mutableListOf<Glyph>()
            fun flush() {
                val text = buildString {
                    var prev: Glyph? = null
                    for (g in seg) {
                        if (prev != null && g.x0 - prev.x1 > 0.15 * g.size && !endsWith(" ") && g.text != " ") append(' ')
                        append(g.text); prev = g
                    }
                }.replace(Regex("\\s+"), " ").trim()
                if (text.isNotEmpty()) {
                    val vis = seg.filter { it.text.isNotBlank() }
                    out += TextLine(
                        vis.minOf { it.x0 } / pageW, vis.minOf { it.top } / pageH,
                        vis.maxOf { it.x1 } / pageW, vis.maxOf { it.bottom } / pageH,
                        text, vis.maxOf { it.size },
                    )
                }
                seg = mutableListOf()
            }
            for (g in row) {
                val last = seg.lastOrNull { it.text.isNotBlank() }
                if (last != null && g.text.isNotBlank() && g.x0 - last.x1 > 2.0 * max(last.size, g.size)) flush()
                seg += g
            }
            flush()
        }
        return out.map { it.copy(x0 = it.x0.coerceIn(0.0, 1.0), y0 = it.y0.coerceIn(0.0, 1.0), x1 = min(1.0, it.x1), y1 = min(1.0, it.y1)) }
            .sortedWith(compareBy({ it.y0 }, { it.x0 }))
    }
}
