package app.papertoslides.core.compose

import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.TextLine
import kotlin.math.max
import kotlin.math.min

/** One part of a question as cropped from its page (light theme pixels) with the page's text lines inside it. */
class PartInput(val img: Raster, val ppi: Double, val box: Box, val lines: List<TextLine>, val wIn: Double, val hIn: Double)

/**
 * Crops + text lines -> [QuestionContent]: figures and tables cut out as pictures, the rest as structured text.
 * Lines must come from the PDF text layer or OCR of the same page; without them the caller uses a picture.
 */
object Extractor {
    private val LIST = Regex("""^\s*(list\s*[-–]?\s*(i|1)\b|column\s*[-–]?\s*(i|1|a)\b|सूची)""", RegexOption.IGNORE_CASE)

    /** A text row or a figure, with the part it came from and its pixel rectangle there. */
    private class Piece(val part: Int, val rect: FigureFinder.Rect, val row: Row?, val figure: Block.Figure?)

    fun extract(parts: List<PartInput>, expectedNumber: Int?, passage: Boolean = false): QuestionContent {
        val pieces = mutableListOf<Piece>()
        val heightsPt = mutableListOf<Double>()
        var tableSeen = false
        for ((pi, p) in parts.withIndex()) {
            val img = p.img; val b = p.box
            fun px(l: TextLine) = FigureFinder.Rect(
                ((l.x0 - b.x0) / b.w * img.w).toInt(), ((l.y0 - b.y0) / b.h * img.h).toInt(),
                ((l.x1 - b.x0) / b.w * img.w).toInt(), ((l.y1 - b.y0) / b.h * img.h).toInt(),
            )
            val lines = p.lines.filter { l ->
                val cx = (l.x0 + l.x1) / 2; val cy = (l.y0 + l.y1) / 2
                cx in b.x0..b.x1 && cy in b.y0..b.y1
            }.sortedWith(compareBy({ it.y0 }, { it.x0 }))
            val rects = lines.map { px(it) }
            val hs = rects.map { it.h }.sorted()
            val lh = if (hs.isEmpty()) (p.ppi * 11 / 72 * 1.3).toInt() else hs[hs.size / 2]
            lines.forEach { heightsPt += (it.y1 - it.y0) * p.hIn * 72 / 1.3 }
            var found = FigureFinder.find(img, rects, lh)
            // match-the-following lists keep their two-column layout: one picture from "List-I" to the options
            val listAt = lines.indexOfFirst { LIST.containsMatchIn(it.text) }
            if (listAt >= 0) {
                tableSeen = true
                val optStart = (listAt + 1 until lines.size).firstOrNull { k -> OPTION_START.containsMatchIn(lines[k].text) }
                val r = FigureFinder.Rect(0, max(0, rects[listAt].t - lh / 3), img.w, if (optStart != null) max(0, rects[optStart].t - lh / 3) else img.h)
                found.figures.filter { it.intersects(r) }.forEach { r.union(it) }
                val figs = found.figures.filter { !it.intersects(r) } + r
                val absorbed = found.absorbed + rects.indices.filter { k -> (rects[k].t + rects[k].b) / 2 in r.t..r.b }
                found = FigureFinder.Result(figs, absorbed)
            }
            // figures side by side on one row stay one picture (a row of diagrams, figure options)
            val rowFigs = mutableListOf<FigureFinder.Rect>()
            for (fr in found.figures.sortedBy { it.t }) {
                val same = rowFigs.firstOrNull { o -> min(o.b, fr.b) - max(o.t, fr.t) > 0.5 * min(o.h, fr.h) }
                if (same != null) same.union(fr) else rowFigs += FigureFinder.Rect(fr.l, fr.t, fr.r, fr.b)
            }
            val optionFigs = rowFigs.filter { fr ->
                found.absorbed.count { k -> MARK_ONE.matches(lines[k].text) && fr.contains((rects[k].l + rects[k].r) / 2, (rects[k].t + rects[k].b) / 2) } >= 2
            }.toSet()
            found = FigureFinder.Result(rowFigs, found.absorbed)
            for ((k, l) in lines.withIndex()) {
                if (k in found.absorbed) continue
                val r = rects[k]
                pieces += Piece(pi, r, Row(RichText.of(l.runsOrPlain()), r.l.toDouble(), r.t.toDouble() + pi * 1e6, r.b.toDouble() + pi * 1e6, l.conf), null)
            }
            for (fr in found.figures) pieces += Piece(pi, fr, null, figure(img.crop(fr.l, fr.t, fr.r, fr.b), p.ppi,
                when { fr in optionFigs -> "options"; listAt >= 0 -> "table"; else -> "figure" }))
        }
        pieces.sortWith(compareBy({ it.part }, { it.rect.t }, { it.rect.l }))

        // flow of segments: consecutive text rows (same visual row joined: inline options) and figures
        val segments = mutableListOf<MutableList<Piece>>() // a text segment, or a single figure piece
        for (pc in pieces) {
            if (pc.figure != null) { segments += mutableListOf(pc); continue }
            val seg = segments.lastOrNull()?.takeIf { it.first().row != null } ?: mutableListOf<Piece>().also { segments += it }
            seg += pc
        }
        fun rowsOf(seg: List<Piece>): List<Row> {
            val rows = mutableListOf<Row>()
            for (pc in seg.sortedWith(compareBy({ it.row!!.y0 }, { it.row!!.x0 }))) {
                val r = pc.row!!
                val last = rows.lastOrNull()
                if (last != null && min(last.y1, r.y1) - max(last.y0, r.y0) > 0.5 * min(last.y1 - last.y0, r.y1 - r.y0)) {
                    val (a, c) = if (r.x0 >= last.x0) last to r else r to last
                    rows[rows.size - 1] = Row(a.text + RichText.plain("   ") + c.text, min(a.x0, c.x0), min(a.y0, c.y0), max(a.y1, c.y1), min(a.conf, c.conf))
                } else rows += r
            }
            return rows
        }

        val srcPt = heightsPt.sorted().let { if (it.isEmpty()) 11.0 else it[it.size / 2] }.coerceIn(6.0, 30.0)
        val allRows = segments.filter { it.first().row != null }.flatMap { rowsOf(it) }
        val unsafe = TextSafety.reasons(allRows).toMutableList()
        if (allRows.isEmpty()) unsafe += "no text found"

        // figure options: option markers without text, next to figures -> one picture with the markers kept
        val textSegs = segments.indices.filter { segments[it].first().row != null }
        val lastText = textSegs.lastOrNull()
        val figSegs = segments.indices.filter { segments[it].first().figure != null }
        val consumed0 = HashSet<Int>()
        var optionsFigure: Block.Figure? = segments.lastOrNull { it.first().figure?.what == "options" }?.first()?.figure
        segments.indices.filter { segments[it].first().figure?.what == "options" }.forEach { consumed0 += it }
        val markerRows = allRows.filter { MARKS_ROW.matches(it.text.chars) }
        val consumed = HashSet<Int>(consumed0)
        if (optionsFigure == null && markerRows.isNotEmpty() && figSegs.isNotEmpty()) {
            val markPieces = pieces.filter { it.row != null && markerRows.any { m -> m.y0 <= it.row.y0 && it.row.y1 <= m.y1 } }
            val part = markPieces.first().part
            val figNear = figSegs.filter { segments[it].first().part == part }.map { segments[it].first() }
                .filter { f -> markPieces.any { m -> kotlin.math.abs(f.rect.b - m.rect.t) < 4 * m.rect.h || kotlin.math.abs(m.rect.b - f.rect.t) < 4 * m.rect.h || f.rect.t in m.rect.t..m.rect.b } }
            if (figNear.isNotEmpty()) {
                val r = FigureFinder.Rect(figNear[0].rect.l, figNear[0].rect.t, figNear[0].rect.r, figNear[0].rect.b)
                (figNear + markPieces).forEach { r.union(it.rect) }
                val p = parts[part]
                optionsFigure = figure(p.img.crop(max(0, r.l - 4), max(0, r.t - 4), min(p.img.w, r.r + 4), min(p.img.h, r.b + 4)), p.ppi, "options")
                segments.indices.filter { segments[it].first() in figNear }.forEach { consumed += it }
            }
        }

        val blocks = mutableListOf<Block>()
        var label: String? = null
        var options = emptyList<OptionText>()
        val kind = if (passage) ParaKind.PASSAGE else ParaKind.STEM
        for ((si, seg) in segments.withIndex()) {
            if (si in consumed) continue
            val fig = seg.first().figure
            if (fig != null) { blocks += fig; continue }
            var rows = rowsOf(seg).filter { optionsFigure == null || it !in markerRows }
            if (rows.isEmpty()) continue
            if (label == null && !passage) {
                val (l, rest) = QuestionText.stripLabel(rows[0].text, expectedNumber)
                label = l ?: ""
                rows = listOf(rows[0].copy(text = rest)) + rows.drop(1)
            }
            if (si == lastText && !passage && optionsFigure == null) {
                val parsed = QuestionText.parse(listOf(Row(RichText.plain("0."), 0.0, -1.0, -0.5)) + rows, 0)
                blocks += parsed.stem
                options = parsed.options
            } else {
                blocks += QuestionText.paragraphs(rows, kind).map { if (passage) it.copy(kind = ParaKind.PASSAGE) else it }
            }
        }
        if (tableSeen && blocks.none { it is Block.Figure }) unsafe += "table not found"
        return QuestionContent(label?.ifEmpty { null } ?: expectedNumber?.let { "$it." } ?: "", blocks, options, optionsFigure, srcPt, unsafe)
    }

    private val OPTION_START = Regex("""^\s*(\([aA1]\)|[aA1]\)|\[[aA]\])""")
    private val MARK_ONE = Regex("""^\s*(\(?[a-eA-E1-5]\)|\[[a-eA-E]\])\s*$""")
    private val MARKS_ROW = Regex("""^\s*((\(?[a-eA-E1-5]\)|\[[a-eA-E]\])\s*){2,5}$""")

    private fun figure(img: Raster, ppi: Double, what: String): Block.Figure {
        val t = Imaging.tighten(img).first
        return Block.Figure(t, t.w / ppi, t.h / ppi, what)
    }

    private fun stackFig(a: Block.Figure, b: Block.Figure): Block.Figure {
        val ppi = a.img.w / a.widthIn
        val img = Imaging.stack(listOf(a.img, b.img), gap = 8)
        return Block.Figure(img, img.w / ppi, img.h / ppi, "options")
    }

    /** Gemini text (stem + options strings) when there are no line boxes. */
    fun fromGemini(label: String, stem: String, options: List<String>, passage: String?): QuestionContent {
        val blocks = mutableListOf<Block>()
        passage?.split('\n')?.filter { it.isNotBlank() }?.forEach { blocks += Block.Para(RichText.plain(it.trim()), ParaKind.PASSAGE) }
        stem.split('\n').filter { it.isNotBlank() }.forEach { blocks += Block.Para(RichText.plain(it.trim()), ParaKind.STEM) }
        val opts = options.mapIndexed { i, o -> OptionText("(${'a' + i})", RichText.plain(o.trim())) }
        val unsafe = mutableListOf<String>()
        if (blocks.isEmpty()) unsafe += "no text found"
        return QuestionContent(label, blocks, opts, null, 11.0, unsafe)
    }
}
