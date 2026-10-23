package app.papertoslides.core.compose

import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.model.TextRun

/** A character with its vertical position (0 normal, 1 superscript, -1 subscript). */
data class RichText(val chars: String, val rise: IntArray) {
    init { require(chars.length == rise.size) }
    val length get() = chars.length
    fun sub(a: Int, b: Int) = RichText(chars.substring(a, b), rise.copyOfRange(a, b))
    fun trim(): RichText {
        var a = 0; var b = chars.length
        while (a < b && chars[a].isWhitespace()) a++
        while (b > a && chars[b - 1].isWhitespace()) b--
        return sub(a, b)
    }
    operator fun plus(o: RichText) = RichText(chars + o.chars, rise + o.rise)
    fun runs(): List<TextRun> {
        val out = mutableListOf<TextRun>()
        for (i in chars.indices) {
            val r = if (chars[i] == ' ') (out.lastOrNull()?.rise ?: 0) else rise[i]
            if (out.isNotEmpty() && out.last().rise == r) out[out.size - 1] = TextRun(out.last().text + chars[i], r) else out += TextRun(chars[i].toString(), r)
        }
        return out
    }
    override fun equals(other: Any?) = other is RichText && chars == other.chars && rise.contentEquals(other.rise)
    override fun hashCode() = chars.hashCode()
    override fun toString() = chars

    companion object {
        val EMPTY = RichText("", IntArray(0))
        fun plain(s: String) = RichText(s, IntArray(s.length))
        fun of(runs: List<TextRun>): RichText {
            val sb = StringBuilder(); val r = ArrayList<Int>()
            for (run in runs) { sb.append(run.text); repeat(run.text.length) { r += run.rise } }
            return RichText(sb.toString(), r.toIntArray())
        }
    }
}

enum class ParaKind { STEM, STATEMENT, PASSAGE, NOTE }

sealed interface Block {
    data class Para(val text: RichText, val kind: ParaKind) : Block
    /** A figure / table / diagram cut from the paper, light theme pixels. */
    data class Figure(val img: Raster, val widthIn: Double, val heightIn: Double, val what: String = "figure") : Block
}

data class OptionText(val marker: String, val text: RichText)

/** Everything a slide needs to show one question as native text. */
data class QuestionContent(
    val label: String,
    val blocks: List<Block>,
    val options: List<OptionText>,
    /** Options that are pictures (figure options), shown as one image. */
    val optionsFigure: Block.Figure? = null,
    /** Font size of the paper's body text (pt), for scaling figures like the text. */
    val srcPt: Double = 11.0,
    /** Why the text should not be trusted (empty = safe). */
    val unsafe: List<String> = emptyList(),
    /** The same question in the other language (papers that print each question twice, language "both"). */
    val twin: QuestionContent? = null,
) {
    val safe get() = unsafe.isEmpty()
    val isEmpty get() = blocks.none { it is Block.Para && it.text.trim().length > 0 } && options.isEmpty() && blocks.none { it is Block.Figure }
}

/** One visual row of the question (text lines on the same baseline joined), with its position in the crop. */
data class Row(val text: RichText, val x0: Double, val y0: Double, val y1: Double, val conf: Double = 1.0)

/**
 * Text structure from rows (port-free logic, unit-tested): question label, paragraphs (statements,
 * assertion/reason), and the options = the LAST complete run of markers (a)(b)(c)(d) / (A).. / (1).. of one style.
 */
object QuestionText {
    private val LABEL = Regex("""^\s*(?:Q(?:ue(?:stion)?)?\s*\.?\s*(?:No\.?)?\s*)?(\d{1,3})\s*[.):\]]?\s*""", RegexOption.IGNORE_CASE)
    private val STATEMENT = Regex("""^\s*(\d{1,2}[.)]\s|\(\d{1,2}\)\s|\(?[ivx]{1,4}\)\s|\(?[ivx]{1,4}\.\s|[A-E][.)]\s|assertion\b|reason\b|statement\s*[-:]?\s*(\d|[ivx]+|[A-Z])?\b|कथन|अभिकथन|कारण)""", RegexOption.IGNORE_CASE)
    // option markers: style key + letter
    private val MARKER = Regex("""(?<![\p{L}\p{N}])(?:\(([a-eA-E1-5])\)|\[([a-eA-E])\]|([a-eA-E1-5])\)|([a-eA-E])\.(?=\s))""")

    data class Parsed(val label: String?, val stem: List<Block.Para>, val options: List<OptionText>)

    fun stripLabel(first: RichText, expected: Int?): Pair<String?, RichText> {
        val m = LABEL.find(first.chars) ?: return null to first
        if (m.range.first != 0) return null to first
        val n = m.groupValues[1].toIntOrNull()
        if (expected != null && n != expected) return null to first
        return m.value.trim() to first.sub(m.range.last + 1, first.length)
    }

    /** Rows (top to bottom) -> paragraphs; new paragraph at statement/option markers or a vertical gap. */
    fun paragraphs(rows: List<Row>, kind: ParaKind = ParaKind.STEM): List<Block.Para> {
        if (rows.isEmpty()) return emptyList()
        val heights = rows.map { it.y1 - it.y0 }.sorted()
        val lh = heights[heights.size / 2].coerceAtLeast(1e-6)
        val out = mutableListOf<Pair<RichText, ParaKind>>()
        var prev: Row? = null
        for (r in rows) {
            val t = r.text.trim()
            if (t.length == 0) continue
            val statement = STATEMENT.containsMatchIn(t.chars)
            val option = MARKER.find(t.chars)?.range?.first == 0
            val gap = prev != null && r.y0 - prev.y1 > 0.9 * lh
            val outdent = prev != null && r.x0 < prev.x0 - 0.5 * lh // back to the margin after indented statements
            if (out.isEmpty() || statement || option || gap || outdent) {
                out += t to (if (statement && kind == ParaKind.STEM) ParaKind.STATEMENT else kind)
            } else {
                val (last, k) = out.last()
                val joined = if (last.chars.endsWith("-") && last.length > 1 && last.chars[last.length - 2].isLetter() && t.chars.firstOrNull()?.isLowerCase() == true)
                    last.sub(0, last.length - 1) + t else last + RichText.plain(" ") + t
                out[out.size - 1] = joined to k
            }
            prev = r
        }
        return out.map { Block.Para(it.first, it.second) }
    }

    /** Splits options off the end of the text. Returns the stem text (before the first option marker) and the options. */
    fun options(text: RichText): Pair<RichText, List<OptionText>> {
        data class M(val start: Int, val end: Int, val style: Int, val idx: Int, val raw: String)
        val ms = MARKER.findAll(text.chars).mapNotNull { m ->
            val g = (1..4).firstOrNull { m.groups[it] != null } ?: return@mapNotNull null
            val c = m.groups[g]!!.value[0]
            val (style, idx) = when {
                c.isDigit() -> (g * 10 + 3) to (c - '1')
                c.isUpperCase() -> (g * 10 + 2) to (c - 'A')
                else -> (g * 10 + 1) to (c - 'a')
            }
            M(m.range.first, m.range.last + 1, style, idx, m.value)
        }.toList()
        // the last start of a run a, b, c(, d(, e)) in order, same style
        for (i in ms.indices.reversed()) {
            if (ms[i].idx != 0) continue
            val seq = mutableListOf(ms[i])
            for (j in i + 1 until ms.size) if (ms[j].style == ms[i].style && ms[j].idx == seq.size) seq += ms[j]
            if (seq.size >= 3 && (seq.size >= 4 || ms[i].style % 10 != 3)) {
                val opts = seq.mapIndexed { k, m ->
                    val end = if (k + 1 < seq.size) seq[k + 1].start else text.length
                    OptionText(m.raw.trim(), text.sub(m.end, end).trim())
                }
                return text.sub(0, seq[0].start).trim() to opts
            }
        }
        return text to emptyList()
    }

    fun parse(rows: List<Row>, expectedNumber: Int?): Parsed {
        if (rows.isEmpty()) return Parsed(null, emptyList(), emptyList())
        val (label, firstText) = stripLabel(rows[0].text, expectedNumber)
        val rs = listOf(rows[0].copy(text = firstText)) + rows.drop(1)
        val paras = paragraphs(rs)
        // options are searched across the joined text, so inline and multi-row options are both found
        val sep = RichText.plain("\n")
        var all = RichText.EMPTY
        paras.forEachIndexed { i, p -> all = if (i == 0) p.text else all + sep + p.text }
        val (stemText, options) = options(all)
        val stem = stemText.chars.split('\n').let { parts ->
            var at = 0
            parts.mapIndexed { i, s ->
                val rt = stemText.sub(at, at + s.length); at += s.length + 1
                Block.Para(rt.trim(), paras.getOrNull(i)?.kind ?: ParaKind.STEM)
            }
        }.filter { it.text.length > 0 }
        return Parsed(label, stem, options.map { it.copy(text = RichText(it.text.chars.replace('\n', ' '), it.text.rise)) })
    }
}

/** When text from the paper must not be shown as text (it would be wrong on the slide). */
object TextSafety {
    private val LATIN_EXT = Regex("[À-ɏɐ-ʯ]")
    private val DEVA = Regex("[ऀ-ॿ]")
    // a word starting with a vowel sign / virama, or a lone consonant (not the real words \u0935, \u0928): broken shaping
    private val DEVA_BROKEN = Regex("(?<![\\u0900-\\u097F])[\\u093E-\\u094D\\u0962\\u0963]")
    private val DEVA_LONE = Regex("(?<![\\u0900-\\u097F])[\\u0915-\\u0927\\u092A-\\u0934\\u0936-\\u0939](?![\\u0900-\\u097F])")
    private val BAD = Regex("[-�]|\\(cid:\\d+\\)")
    private val MATHY = Regex("[0-9=+\\-−×÷/^√∑∫]")
    private val MARK = Regex("""^\s*(\(?[a-eA-E1-5]\)|\(?[a-eA-E]\.)""")

    fun reasons(rows: List<Row>, minConf: Double = 0.75): List<String> {
        val out = mutableListOf<String>()
        val text = rows.joinToString("\n") { it.text.chars }
        if (BAD.containsMatchIn(text)) out += "unreadable characters in the text layer"
        if (rows.any { DEVA.containsMatchIn(it.text.chars) && LATIN_EXT.containsMatchIn(it.text.chars) } ||
            DEVA_BROKEN.containsMatchIn(text) || DEVA_LONE.findAll(text).count() >= 1) out += "garbled Hindi text layer"
        val conf = rows.map { it.conf }
        if (conf.isNotEmpty() && conf.average() < minConf) out += "low OCR confidence"
        // stacked maths (fractions, limits): very short rows of digits/operators that are not option rows
        val stacked = rows.count { r ->
            val t = r.text.chars.trim()
            t.isNotEmpty() && t.count { !it.isWhitespace() } <= 6 && MATHY.containsMatchIn(t) && !MARK.containsMatchIn(t)
        }
        if (stacked > 0) out += "maths laid out on several lines"
        return out
    }
}
