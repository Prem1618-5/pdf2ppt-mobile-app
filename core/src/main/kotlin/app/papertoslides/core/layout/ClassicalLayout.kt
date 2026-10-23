package app.papertoslides.core.layout

import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.RawFragment
import app.papertoslides.core.model.Roles
import app.papertoslides.core.model.TextLine
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * No-LLM layout parser (port of legacy `layout_classical.py`, text-layer part).
 *
 * Input: text lines of each page (PDF text layer = tier T0, or on-device OCR = tier T1).
 * Question anchors are lines that start with a number, sit at the left edge of a column and
 * continue the numbering sequence. Output has the same shape as the Gemini layout.
 *
 * Changes vs legacy (context §10): stricter-but-correct answer-key detection, and
 * OCR-tolerant anchors (`l.`/`I.` -> 1) accepted only where the sequence expects that number.
 */
class ClassicalLayout(private val ocr: Boolean = false) {
    private var repeated: Set<String> = emptySet()
    private var repeatedFigs: Set<List<Double>> = emptySet()
    var expectedNext: Int? = null
    private var hadQuestion = false
    private val modelName = if (ocr) "classical-ocr" else "classical-text"

    /** Text repeating on many pages (running headers, footers, subject bands) is clutter. */
    fun prime(linesByPage: List<List<TextLine>>, figsByPage: List<List<Box>> = emptyList()) {
        val n = max(1, linesByPage.size)
        val c = HashMap<String, Int>()
        for (lines in linesByPage) for (t in lines.map { norm(it.text) }.toSet()) c[t] = (c[t] ?: 0) + 1
        repeated = c.filter { (t, k) ->
            k >= max(3.0, 0.3 * n) && t.length < 60 && t.replace(NOT_LETTER, "").length >= 4
        }.keys
        val fc = HashMap<List<Double>, Int>()
        for (figs in figsByPage) for (f in figs.map { roundBox(it) }.toSet()) fc[f] = (fc[f] ?: 0) + 1
        repeatedFigs = fc.filter { it.value >= max(3.0, 0.3 * n) }.keys
    }

    /** Called when another tier handled a page, so the numbering sequence continues. */
    fun observe(layout: PageLayout) {
        val nums = layout.fragments.filter { it.role == Roles.QUESTION }.mapNotNull { it.number }
        if (nums.isNotEmpty()) {
            expectedNext = nums.last() + 1
            hadQuestion = true
        }
    }

    // ------------------------------------------------------------------ anchors
    /** Question number at the start of [text], or null. OCR mode maps l/I/| -> 1 and O -> 0 only if that gives the expected number. */
    fun anchorNumber(text: String): Int? {
        ANCHOR.find(text)?.let { return it.groupValues[1].toInt() }
        if (!ocr) return null
        val m = OCR_ANCHOR.find(text) ?: return null
        val n = m.groupValues[1].map { ch ->
            when (ch) { 'l', 'I', '|', 'i' -> '1'; 'O', 'o' -> '0'; else -> ch }
        }.joinToString("").toIntOrNull() ?: return null
        return if (n == expectedNext) n else null
    }

    // ------------------------------------------------------------------ text layer
    private fun columns(lines: List<TextLine>): List<Pair<Double, Double>> {
        val bins = IntArray(400)
        for (l in lines) {
            if (l.x1 - l.x0 > 0.6) continue
            val a = (l.x0 * 399).toInt().coerceIn(0, 399)
            val b = ((l.x1 * 399).toInt() + 1).coerceIn(0, 400)
            for (i in a until b) bins[i]++
        }
        val segs = mutableListOf<DoubleArray>()
        var start = -1
        for (i in 0..400) {
            val o = i < 400 && bins[i] > 0
            if (o && start < 0) start = i
            else if (!o && start >= 0) { segs += doubleArrayOf(start / 399.0, i / 399.0); start = -1 }
        }
        if (segs.isEmpty()) return listOf(0.0 to 1.0)
        // merge gaps < 1.5 % and narrow segments (table cells such as Sr.No / Marks)
        val merged = mutableListOf(segs[0])
        for (s in segs.drop(1)) {
            if (s[0] - merged.last()[1] < 0.015) merged.last()[1] = s[1] else merged += s
        }
        var changed = true
        while (changed && merged.size > 1) {
            changed = false
            for (i in merged.indices) {
                val s = merged[i]
                if (s[1] - s[0] < 0.15) {
                    val j = if (i > 0 && (i == merged.size - 1 || s[0] - merged[i - 1][1] < merged[i + 1][0] - s[1])) i - 1 else i + 1
                    val a = min(i, j); val b = max(i, j)
                    merged[a] = doubleArrayOf(min(merged[a][0], merged[b][0]), max(merged[a][1], merged[b][1]))
                    merged.removeAt(b)
                    changed = true
                    break
                }
            }
        }
        // a real column carries a fair share of the text; thin numeric strips do not
        fun mass(sg: DoubleArray) = lines.filter { (it.x0 + it.x1) / 2 in sg[0]..sg[1] }.sumOf { it.text.length }
        val total = merged.sumOf { mass(it) }.takeIf { it > 0 } ?: 1
        while (merged.size > 1) {
            val i = merged.indices.firstOrNull { mass(merged[it]) < 0.12 * total } ?: break
            val j = if (i > 0) i - 1 else i + 1
            val a = min(i, j); val b = max(i, j)
            merged[a] = doubleArrayOf(min(merged[a][0], merged[b][0]), max(merged[a][1], merged[b][1]))
            merged.removeAt(b)
        }
        return merged.map { max(0.0, it[0] - 0.01) to min(1.0, it[1] + 0.01) }
    }

    /** A "column" with no question numbers beside one that has them is a figure/option block: merge it left. */
    private fun mergeNumberless(cols: List<Pair<Double, Double>>, content: List<TextLine>): List<Pair<Double, Double>> {
        if (cols.size < 2) return cols
        fun hasNumbers(c: Pair<Double, Double>): Boolean {
            val ls = content.filter { (it.x0 + it.x1) / 2 in c.first..c.second }
            if (ls.isEmpty()) return false
            val left = ls.minOf { it.x0 }
            return ls.any { ANCHOR.containsMatchIn(it.text) && it.x0 <= left + 0.05 }
        }
        val flags = cols.map { hasNumbers(it) }
        if (flags.none { it }) return cols
        val out = mutableListOf(cols[0].first to cols[0].second)
        for (k in 1 until cols.size) {
            if (flags[k]) out += cols[k] else out[out.size - 1] = out.last().first to max(out.last().second, cols[k].second)
        }
        return out
    }

    private fun isClutter(l: TextLine): Boolean {
        if (norm(l.text) in repeated) return true
        val n = ANCHOR.find(l.text)?.groupValues?.get(1)?.toInt()
        val exp = expectedNext
        if (n != null && exp != null && n - exp in 0..6 && l.x0 < 0.5) return false // next question number, not a page number
        if ((l.y0 < 0.05 || l.y1 > 0.95) && (CLUTTER.containsMatchIn(l.text) || PAGE_NO.matches(l.text))) return true
        if (MARKS.matches(l.text) && l.x0 > 0.6) return true // marks / negative-marks cells
        return CLUTTER.containsMatchIn(l.text) && l.text.length < 40
    }

    fun analyseTextPage(lines: List<TextLine>, figures: List<Box> = emptyList()): PageLayout {
        if (looksLikeAnswerKey(lines)) {
            return PageLayout(pageKind = PageLayout.KIND_ANSWER_KEY, fragments = emptyList(), model = modelName)
        }
        val figs = figures.filter { roundBox(it) !in repeatedFigs }
        val content = lines.filter { !isClutter(it) }
        val cols = mergeNumberless(columns(content), content)
        val frags = mutableListOf<RawFragment>()
        for ((cx0, cx1) in cols) {
            val colLines = content.filter { (it.x0 + it.x1) / 2 in cx0..cx1 }.sortedBy { it.y0 }
            val colFigs = figs.filter { (it.x0 + it.x1) / 2 in cx0..cx1 }
            if (colLines.isEmpty()) continue
            val left = colLines.minOf { it.x0 }
            val anchors = mutableListOf<Pair<TextLine, Int>>()
            val qidCols = mutableListOf<Pair<Double, Double>>()
            for (l in colLines) {
                if (l.x0 > left + 0.05 || INSTRUCTION_WORDS.containsMatchIn(l.text)) continue
                val n = anchorNumber(l.text) ?: continue
                if (anchors.isNotEmpty() && abs(anchors.last().first.y0 - l.y0) < 0.004) {
                    qidCols += l.x0 to l.x1 // second number cell on the row: QID column
                    continue
                }
                val exp = expectedNext
                // a restart to 1 or a jump must sit exactly at the number margin: numbered statements inside a
                // question ("1. The Sun is a star.") are indented and are not new questions
                val strict = exp != null && n != exp
                if (strict && l.x0 > left + (if (ocr) 0.022 else 0.016)) continue
                if (exp == null || n == exp || n - exp in 1..3 || n == 1) {
                    anchors += l to n
                    expectedNext = n + 1
                } else if (n == exp - 1 && anchors.isNotEmpty() && anchors.last().second == n &&
                    DEVANAGARI.containsMatchIn(l.text) != DEVANAGARI.containsMatchIn(anchors.last().first.text)) {
                    anchors += l to n // bilingual twin: the same number printed again in the other script
                }
            }
            val starts = anchors.map { it.first.y0 }
            val bounds = starts + 1.01
            val topLines = colLines.filter { starts.isEmpty() || it.y1 <= starts[0] + 0.002 }
            val topPassage = topLines.indexOfFirst { DIRECTIONS.containsMatchIn(it.text) }
            if (topPassage >= 0) {
                val before = topLines.subList(0, topPassage)
                if (before.isNotEmpty() && (frags.isNotEmpty() || hadQuestion)) {
                    frags += frag(Roles.CONTINUATION, null, cx0, before.minOf { it.y0 }, cx1, before.maxOf { it.y1 }, before, colFigs, cont = true)
                }
                frags += passage(topLines.subList(topPassage, topLines.size), cx0, cx1, colFigs)
            } else if (topLines.isNotEmpty() && (frags.isNotEmpty() || hadQuestion)) {
                val y0 = topLines.minOf { it.y0 }
                val y1 = topLines.maxOf { it.y1 }
                val t = topLines[0]
                val loneBand = topLines.size == 1 && abs((t.x0 + t.x1) / 2 - 0.5) < 0.1 && t.x1 - t.x0 < 0.35
                if (!loneBand && !SOLUTION.containsMatchIn(t.text)) {
                    frags += frag(Roles.CONTINUATION, null, cx0, y0, cx1, y1, topLines, colFigs, cont = true)
                }
            }
            for ((i, a) in anchors.withIndex()) {
                val (l, n) = a
                val ys = bounds[i]; val ye = bounds[i + 1]
                var body = colLines.filter { it.y0 >= ys - 0.002 && it.y0 < ye - 0.002 }
                // stop at an interleaved solution block
                val cut = body.indices.firstOrNull { it > 0 && SOLUTION.containsMatchIn(body[it].text) }
                if (cut != null) body = body.subList(0, cut)
                // a "Directions ... read the passage" block after this question belongs to the next questions
                val pcut = body.indices.firstOrNull { it > 0 && DIRECTIONS.containsMatchIn(body[it].text) }
                var passageLines: List<TextLine> = emptyList()
                if (pcut != null) { passageLines = body.subList(pcut, body.size); body = body.subList(0, pcut) }
                var y1 = body.maxOf { it.y1 }
                for (f in colFigs) if (f.y0 >= ys && f.y0 < ye) y1 = max(y1, f.y1)
                var fr = frag(Roles.QUESTION, n.toString(), cx0, l.y0, cx1, y1, body, colFigs, number = n)
                if (qidCols.isNotEmpty()) { // hide the duplicate question-ID column, keep the serial number
                    val qx0 = qidCols.minOf { it.first }; val qx1 = qidCols.maxOf { it.second }
                    fr = fr.copy(erase = listOf(listOf(qx0 - 0.004, l.y0 - 0.01, qx1 + 0.004, y1 + 0.01)))
                }
                frags += fr
                if (passageLines.isNotEmpty()) frags += passage(passageLines, cx0, cx1, colFigs)
            }
        }
        hadQuestion = hadQuestion || frags.any { it.role == Roles.QUESTION }
        if (frags.isNotEmpty() && frags.last().role == Roles.QUESTION) {
            frags[frags.size - 1] = frags.last().copy(continuesOnNext = true) // unknown: the next page decides
        }
        for (l in lines) if (l !in content) { // bands, headers, footers: boundaries for crop padding
            frags += RawFragment(
                role = Roles.CLUTTER,
                box = listOf((l.y0 * 1000).toInt(), (l.x0 * 1000).toInt(), ceil(l.y1 * 1000).toInt(), ceil(l.x1 * 1000).toInt()),
                confidence = 1.0,
            )
        }
        val ordered = frags.mapIndexed { i, f -> f.copy(order = i) }
        return PageLayout(
            pageKind = if (ordered.isNotEmpty()) PageLayout.KIND_QUESTIONS else PageLayout.KIND_BLANK,
            watermarkPresent = repeatedFigs.isNotEmpty() || repeated.any { "watermark" in it || "sample" in it },
            fragments = ordered,
            model = modelName,
        )
    }

    /** A shared passage (comprehension / data set); label = the question range it serves ("6-10"), if printed. */
    private fun passage(lines: List<TextLine>, cx0: Double, cx1: Double, figs: List<Box>): RawFragment {
        val head = lines.first().text
        val range = RANGE.find(head)?.let { m -> "${m.groupValues[1]}-${m.groupValues[2]}" }
        val f = frag(Roles.SHARED_PASSAGE, range, cx0, lines.minOf { it.y0 }, cx1, lines.maxOf { it.y1 }, lines, figs)
        return f.copy(continuesOnNext = true)
    }

    private fun frag(
        role: String, label: String?, x0In: Double, y0: Double, x1In: Double, y1: Double,
        lines: List<TextLine>, figs: List<Box>, number: Int? = null, cont: Boolean = false,
    ): RawFragment {
        val text = lines.joinToString(" ") { it.text }
        var x0 = x0In; var x1 = x1In
        // horizontal extent from the real content (marks cells are clutter, not content)
        val ext = lines.map { it.x0 to it.x1 } + figs.filter { it.y0 >= y0 - 0.002 && it.y0 <= y1 }.map { it.x0 to it.x1 }
        if (ext.isNotEmpty()) {
            x0 = max(x0, ext.minOf { it.first } - 0.006)
            x1 = min(x1, ext.maxOf { it.second } + 0.008)
        }
        val hi = DEVANAGARI.containsMatchIn(text)
        val en = LATIN3.containsMatchIn(text)
        return RawFragment(
            role = role, label = label, number = number, continuesPrevious = cont,
            box = listOf((max(0.0, y0 - 0.004) * 1000).toInt(), (x0 * 1000).toInt(), (min(1.0, y1 + 0.004) * 1000).toInt(), (x1 * 1000).toInt()),
            lang = if (hi && en) "bilingual" else if (hi) "hi" else "en",
            hasFigure = figs.any { it.y0 in y0..y1 },
            stem = text.take(400),
            confidence = if (ocr) 0.7 else 0.8,
        )
    }

    companion object {
        val ANCHOR = Regex("""^\s*(?:Q(?:ue(?:stion)?)?\s*\.?\s*(?:No\.?)?\s*)?(\d{1,3})\s*(?:[.):\]]|\s|$)""", RegexOption.IGNORE_CASE)
        private val OCR_ANCHOR = Regex("""^\s*(?:Q(?:ue(?:stion)?)?\s*\.?\s*(?:No\.?)?\s*)?([\dlIi|Oo]{1,3})\s*(?:[.):\]]|\s|$)""")
        val CLUTTER = Regex("""(www\.|https?://|\.com\b|\.in\b|page\s*\d+|p\.?\s*t\.?\s*o\b)""", RegexOption.IGNORE_CASE)
        val MARKS = Regex("""^\s*[-+]?\d+\.\d+\s*$""")
        val ANSWER_KEY = Regex("""answer\s*key|answers?\s*:|^\s*key\s*$|उत्तर\s*कुंजी""", RegexOption.IGNORE_CASE)
        private val KEY_LINE = Regex("""^\s*\d{1,3}\s*[.\-:)]?\s*\(?[A-Da-d1-4]\)?\s*$""")
        private val KEY_TOKEN = Regex("""^\d{1,3}\s*[.\-:)]?\s*\(?[A-Da-d1-4]\)?$""")
        val PAGE_NO = Regex("""^\s*[-\[(]?\s*\d+\s*[-\])]?\s*$""")
        val INSTRUCTION_WORDS = Regex("""\b(tick|marks?|negative|candidates?|answer sheet|booklet|carry|instructions?|duration|allowed)\b""", RegexOption.IGNORE_CASE)
        /** Directions / comprehension headers that start a passage shared by the following questions. */
        val DIRECTIONS = Regex("""^\s*(directions?\b|read the (following |given )?(passage|paragraph|text|poem|extract|information|data)|passage\s*[-–:(\d]|comprehension\b|(study|consider|read) the following (information|data|table|graph|chart|passage)|निर्देश|गद्यांश)""", RegexOption.IGNORE_CASE)
        val RANGE = Regex("""(\d{1,3})\s*(?:-|–|—|to|and|से)\s*(\d{1,3})""")
        val SOLUTION = Regex("""^\s*(ans(wer)?\s*[.:]|sol(ution)?\s*[.:]|explanation\s*[.:]|hint\s*[.:])""", RegexOption.IGNORE_CASE)
        private val DEVANAGARI = Regex("[ऀ-ॿ]")
        private val LATIN3 = Regex("[A-Za-z]{3}")
        private val NOT_LETTER = Regex("[^a-zऀ-ॿ]")
        private val DIGITS = Regex("""\d+""")
        private val SPACES = Regex("""\s+""")

        fun norm(t: String) = t.trim().lowercase().replace(DIGITS, "#").replace(SPACES, " ")

        private fun roundBox(b: Box) = listOf(b.x0, b.y0, b.x1, b.y1).map { Math.round(it * 100) / 100.0 }

        /**
         * Fix of legacy defect #1: a page is an answer key if it has a key header and >= 70 % of its other lines
         * look like `<n> <letter>`, or if it is a dense grid of such tokens (several per line).
         */
        fun looksLikeAnswerKey(lines: List<TextLine>): Boolean {
            if (lines.isEmpty()) return false
            val head = lines.any { ANSWER_KEY.containsMatchIn(it.text) }
            val body = lines.filter { !ANSWER_KEY.containsMatchIn(it.text) && !CLUTTER.containsMatchIn(it.text) && !PAGE_NO.matches(it.text) }
            if (body.isEmpty()) return false
            val keyLike = body.count { KEY_LINE.matches(it.text) }
            if (head && keyLike >= 0.7 * body.size) return true
            // dense grid: "1 A 2 C 3 B ..." or "1-A 2-C" several per line
            val tokens = body.flatMap { GRID_SPLIT.findAll(it.text).map { m -> m.value.trim() }.toList() }
            val gridLike = tokens.count { KEY_TOKEN.matches(it) }
            return head && tokens.size >= 10 && gridLike >= 0.7 * tokens.size
        }
        private val GRID_SPLIT = Regex("""\d{1,3}\s*[.\-:)]?\s*\(?[A-Za-z0-9]\)?(?=\s|$)|\S+""")
    }
}
