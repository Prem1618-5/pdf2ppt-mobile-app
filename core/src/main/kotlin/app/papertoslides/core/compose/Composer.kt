package app.papertoslides.core.compose

import app.papertoslides.core.geometry.RectIn
import app.papertoslides.core.imaging.Raster
import kotlin.math.max
import kotlin.math.min

data class PRun(val text: String, val rise: Int = 0, val bold: Boolean = false, val italic: Boolean = false, val color: String)

data class PPara(
    val runs: List<PRun>,
    val sizePt: Double,
    val marLIn: Double = 0.0,
    val indentIn: Double = 0.0,
    val tabsIn: List<Double> = emptyList(),
    val spaceBeforePt: Double = 0.0,
)

sealed interface Shape {
    val rect: RectIn
    data class TextBox(override val rect: RectIn, val paras: List<PPara>) : Shape
    data class Pic(override val rect: RectIn, val img: Raster, val alt: String) : Shape
}

/** What goes inside the invisible question container on one slide. */
data class Composition(
    val shapes: List<Shape>,
    /** Font size of the question text (pt), or the estimated displayed size for pictures. */
    val fontPt: Double?,
    val expanded: Boolean,
    /** The box the content had to stay in (template question box, maybe expanded). */
    val box: RectIn,
    val isText: Boolean,
)

/**
 * Lays question content out as native text inside the layout box: the largest font size from [maxPt] down to
 * [minPt] at which everything fits; options in 4 columns, a 2 x 2 grid or one per line; figures scaled like the
 * text and capped; the expanded box (full height) is tried when the normal box is too small.
 */
class Composer(
    private val colours: app.papertoslides.core.template.ThemeColours,
    private val maxPt: Double = 28.0,
    private val minPt: Double = 16.0,
) {
    private val safety = 1.04        // width safety margin vs. the real font engine (checked by rendering in tests)
    private val lineH = 1.2          // line height / font size
    private val paraGapPt = 0.35     // paragraph spacing in em
    private val figGapIn = 0.08

    /**
     * Order of preference: a comfortable font (>= [comfortPt]) in the normal box, then a comfortable font in the
     * expanded box, then smaller fonts (down to [minPt]) in the normal box, then in the expanded box.
     */
    fun compose(q: QuestionContent?, passage: QuestionContent?, box: RectIn, expanded: RectIn?, note: String? = null, forceExpanded: Boolean = false): Composition? {
        val boxes = buildList { if (!forceExpanded) add(box to false); if (expanded != null) add(expanded to true) }
        val comfort = max(minPt, min(comfortPt, maxPt))
        for ((lo, hi) in listOf(comfort to maxPt, minPt to comfort - 1)) {
            for ((b, ex) in boxes) {
                var s = hi
                while (s >= lo - 1e-9) {
                    layout(q, passage, b, s, note)?.let { return Composition(it, s, ex, b, true) }
                    s -= 1.0
                }
            }
        }
        return null
    }

    private val comfortPt = 20.0

    // ------------------------------------------------------------------ layout at one size
    private sealed interface Flow {
        data class Text(val paras: List<PPara>) : Flow
        data class Fig(val f: Block.Figure, val scale: Double) : Flow
    }

    private fun layout(q: QuestionContent?, passage: QuestionContent?, box: RectIn, s: Double, note: String?): List<Shape>? {
        val w = box.w
        val flow = mutableListOf<Flow>()
        val text = mutableListOf<PPara>()
        fun flushText() { if (text.isNotEmpty()) { flow += Flow.Text(text.toList()); text.clear() } }
        if (note != null) text += PPara(listOf(PRun(note, italic = true, color = colours.muted)), s * 0.7)
        val srcPt = q?.srcPt ?: passage?.srcPt ?: 11.0
        fun addBlocks(blocks: List<Block>, labelRun: PRun?) {
            var label = labelRun
            for (b in blocks) when (b) {
                is Block.Para -> {
                    val runs = b.text.runs().map { PRun(it.text, it.rise, color = colours.text) }
                    val indent = if (b.kind == ParaKind.STATEMENT) 0.35 * s / 18 else 0.0
                    text += PPara(listOfNotNull(label) + runs, s, marLIn = indent, spaceBeforePt = if (text.isEmpty()) 0.0 else s * paraGapPt)
                    label = null
                }
                is Block.Figure -> { flushText(); flow += Flow.Fig(b, s / srcPt) }
            }
            if (label != null) text += PPara(listOf(label), s)
        }
        if (passage != null) {
            addBlocks(passage.blocks, null)
            flushText()
        }
        if (q != null) {
            val labelRun = q.label.takeIf { it.isNotBlank() }?.let { PRun("$it ", bold = true, color = colours.accent) }
            addBlocks(q.blocks, labelRun)
            if (q.options.isNotEmpty()) text += optionParas(q.options, w, s)
            if (q.optionsFigure != null) { flushText(); flow += Flow.Fig(q.optionsFigure, s / srcPt) }
            q.twin?.let { t ->
                addBlocks(t.blocks, null)
                if (t.options.isNotEmpty()) text += optionParas(t.options, w, s)
                if (t.optionsFigure != null) { flushText(); flow += Flow.Fig(t.optionsFigure, s / srcPt) }
            }
        }
        flushText()

        // measure: text heights are fixed at this size; figures share what is left (each at most 55 % of the box)
        val textH = flow.filterIsInstance<Flow.Text>().sumOf { heightOf(it.paras, w) }
        val gaps = (flow.size - 1).coerceAtLeast(0) * figGapIn
        val figs = flow.filterIsInstance<Flow.Fig>()
        var y = box.y
        val shapes = mutableListOf<Shape>()
        val figRoom = box.h - textH - gaps
        if (figRoom < 0) return null
        val wanted = figs.map { fitFig(it.f, it.scale, w, box.h * 0.6) }
        val wantedH = wanted.sumOf { it.second }
        val shrink = if (wantedH > figRoom) figRoom / wantedH else 1.0
        // a figure shrunk to less than 60 % of its natural slide size is unreadable: try a smaller font instead
        if (figs.isNotEmpty() && shrink < 0.6) return null
        // never shrink a figure below its size on the paper (or 1.2 in): it would not be readable from the back
        if (figs.indices.any { k -> wanted[k].second * shrink < min(figs[k].f.heightIn, 1.2) - 1e-6 }) return null
        var fi = 0
        for (f in flow) when (f) {
            is Flow.Text -> {
                val h = heightOf(f.paras, w)
                shapes += Shape.TextBox(RectIn(box.x, y, w, h), f.paras)
                y += h + figGapIn
            }
            is Flow.Fig -> {
                val (fw, fh) = wanted[fi++].let { it.first * shrink to it.second * shrink }
                shapes += Shape.Pic(RectIn(box.x, y, fw, fh), f.f.img, f.f.what)
                y += fh + figGapIn
            }
        }
        if (y - figGapIn > box.y + box.h + 1e-6) return null
        return shapes
    }

    /** Figure size on the slide: magnified like the text, at most the box width and [maxH]. */
    private fun fitFig(f: Block.Figure, scale: Double, maxW: Double, maxH: Double): Pair<Double, Double> {
        var w = f.widthIn * scale; var h = f.heightIn * scale
        val k = min(1.0, min(maxW / w, maxH / h))
        w *= k; h *= k
        return w to h
    }

    // ------------------------------------------------------------------ options
    private fun optionParas(opts: List<OptionText>, w: Double, s: Double): List<PPara> {
        val texts = opts.map { o -> o.marker to o.text.runs() }
        val widths = texts.map { (m, runs) -> Metrics.widthIn("$m ", s) + runs.sumOf { r -> Metrics.widthIn(r.text, if (r.rise != 0) s * 0.65 else s) } }.map { it * safety }
        val gap = 0.25
        val cols = when {
            opts.size <= 4 && widths.all { it <= w / opts.size - gap } -> opts.size
            widths.all { it <= w / 2 - gap } -> 2
            else -> 1
        }
        val first = s * paraGapPt
        if (cols == 1) {
            return texts.mapIndexed { i, (m, runs) ->
                val lab = Metrics.widthIn("$m ", s) * safety
                PPara(listOf(PRun("$m ", color = colours.text)) + runs.map { PRun(it.text, it.rise, color = colours.text) }, s,
                    marLIn = lab, indentIn = -lab, spaceBeforePt = if (i == 0) first else s * 0.15)
            }
        }
        val tabs = (1 until cols).map { k -> w * k / cols }
        return texts.chunked(cols).mapIndexed { r, row ->
            val runs = mutableListOf<PRun>()
            row.forEachIndexed { k, (m, rs) ->
                if (k > 0) runs += PRun("\t", color = colours.text)
                runs += PRun("$m ", color = colours.text)
                rs.forEach { runs += PRun(it.text, it.rise, color = colours.text) }
            }
            PPara(runs, s, tabsIn = tabs, spaceBeforePt = if (r == 0) first else s * 0.15)
        }
    }

    // ------------------------------------------------------------------ measuring
    fun heightOf(paras: List<PPara>, w: Double): Double = paras.sumOf { p ->
        lines(p, w) * p.sizePt * lineH / 72.0 + p.spaceBeforePt / 72.0
    }

    /** Greedy word wrap with the estimated widths, in box coordinates (first line at marL + indent, others at marL). */
    fun lines(p: PPara, w: Double): Int {
        var lines = 1
        var x = p.marLIn + p.indentIn
        var lineStart = true
        for (run in p.runs) {
            if (run.text == "\t") { x = p.tabsIn.firstOrNull { it > x + 1e-6 } ?: (x + 0.5); continue }
            val size = if (run.rise != 0) p.sizePt * 0.65 else p.sizePt
            val words = run.text.split(' ')
            for ((i, word) in words.withIndex()) {
                val piece = if (i < words.size - 1) "$word " else word
                if (piece.isEmpty()) continue
                val ww = Metrics.widthIn(piece, size, run.bold) * safety
                if (x + ww > w && !lineStart) { lines++; x = p.marLIn }
                x += ww
                lineStart = false
            }
        }
        return lines
    }
}
