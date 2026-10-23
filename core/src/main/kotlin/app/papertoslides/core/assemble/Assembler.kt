package app.papertoslides.core.assemble

import app.papertoslides.core.model.Box
import app.papertoslides.core.model.Fragment
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.Question
import app.papertoslides.core.model.RawFragment
import app.papertoslides.core.model.Roles

data class Dropped(var solutions: Int = 0, var clutter: Int = 0, var duplicates: Int = 0, var twins: Int = 0, var orphans: Int = 0)

data class Assembly(val questions: List<Question>, val dropped: Dropped)

/** Port of legacy `assemble.py`: fragments -> questions. Pure logic, no Android. */
object Assembler {

    fun devRatio(text: String): Double {
        var dev = 0; var lat = 0
        for (c in text) {
            if (c in 'ऀ'..'ॿ') dev++ else if (c.code < 128 && c.isLetter()) lat++
        }
        return if (dev + lat > 0) dev.toDouble() / (dev + lat) else 0.0
    }

    /** Language from the transcribed script, not a model's label (legacy `_script_lang`). */
    fun scriptLang(text: String, fallback: String): String {
        var dev = 0; var lat = 0
        for (c in text) {
            if (c in 'ऀ'..'ॿ') dev++ else if (c.code < 128 && c.isLetter()) lat++
        }
        if (dev + lat < 12) return fallback
        val r = dev.toDouble() / (dev + lat)
        return if (r > 0.6) "hi" else if (r < 0.1) "en" else "bilingual"
    }

    /** Page layouts -> fragments in page order. Gemini layouts are re-ordered geometrically (never trust LLM order). */
    fun fragmentsOf(layouts: List<PageLayout?>): List<Fragment> {
        val out = mutableListOf<Fragment>()
        for ((seq, lay) in layouts.withIndex()) {
            if (lay == null) continue
            val placed = lay.fragments.mapNotNull { f -> Box.fromThousands(f.box)?.let { f to it } }
            val ordered = if (lay.model.startsWith("classical")) placed.sortedBy { it.first.order }
            else readingOrder(placed.filter { it.first.role != Roles.CLUTTER }) { it.second } + placed.filter { it.first.role == Roles.CLUTTER }
            ordered.forEachIndexed { i, (f, box) -> out += toFragment(seq, i, f, box, lay.model) }
        }
        return out
    }

    private fun toFragment(seq: Int, order: Int, f: RawFragment, box: Box, model: String): Fragment {
        val text = f.stem.trim()
        return Fragment(
            pageSeq = seq, order = order, role = f.role, label = f.label, number = f.number,
            lang = scriptLang(text + " " + f.options.joinToString(" "), f.lang),
            box = box, text = text, options = f.options.filter { it.isNotBlank() }, confidence = f.confidence,
            hasFigure = f.hasFigure, continuesPrevious = f.continuesPrevious, continuesOnNext = f.continuesOnNext,
            model = model, erase = f.erase.mapNotNull { e -> if (e.size == 4) Box(e[0], e[1], e[2], e[3]) else null },
        )
    }

    fun assemble(fragsIn: List<Fragment>, langPref: String = "en"): Assembly {
        val frags = fragsIn.sortedWith(compareBy({ it.pageSeq }, { it.order }))
        val questions = mutableListOf<Question>()
        val dropped = Dropped()
        var pendingPassage: MutableList<Fragment>? = null
        var passageGroupOpen = false
        var passageEnd: Int? = null      // last question number the open passage serves, if printed
        var groupHead: Question? = null

        for (f in frags) {
            when (f.role) {
                Roles.SOLUTION, Roles.ANSWER_KEY -> { dropped.solutions++; continue }
                Roles.CLUTTER -> { dropped.clutter++; continue }
                Roles.INSTRUCTIONS, Roles.SECTION_HEADER -> { passageGroupOpen = false; pendingPassage = null; groupHead = null; continue }
                Roles.SHARED_PASSAGE -> {
                    pendingPassage = mutableListOf(f); passageGroupOpen = true; groupHead = null
                    passageEnd = f.label?.substringAfter('-', "")?.toIntOrNull()
                    continue
                }
            }
            // a passage running over a column / page break: its continuation belongs to the passage
            if (f.role == Roles.CONTINUATION && pendingPassage != null) { pendingPassage.add(f); continue }
            // an unnumbered "question" right after a question is its option block / table tail
            val isCont = f.role == Roles.CONTINUATION ||
                (f.role == Roles.QUESTION && f.number == null && questions.isNotEmpty() && !f.model.startsWith("classical"))
            if (isCont) {
                val recent = questions.takeLast(8).asReversed()
                // prefer an open question of the same language (bilingual spreads interleave)
                var target = recent.firstOrNull { q -> (q.parts.lastOrNull() ?: q.root).continuesOnNext && q.lang == f.lang }
                    ?: recent.firstOrNull { q -> q.lang == f.lang || f.lang == "other" || f.lang == "bilingual" || q.lang == "bilingual" }
                    ?: questions.lastOrNull()
                if (target == null) { dropped.orphans++; continue }
                target.parts += f
                target.flag("split_stitched")
                if (f.pageSeq != target.root.pageSeq) target.flag("page_break_stitched")
                continue
            }
            if (f.role != Roles.QUESTION) continue
            val q = Question(f)
            val end = passageEnd
            if (passageGroupOpen && end != null && f.number != null && f.number > end) { passageGroupOpen = false; pendingPassage = null; groupHead = null }
            if (pendingPassage != null) {
                q.passageParts += pendingPassage
                q.flag("shared_passage")
                pendingPassage = null
                groupHead = q
            } else if (passageGroupOpen) {
                groupHead?.let { q.passageOf = it; q.flag("refers_to_passage") }
            }
            questions += q
        }

        // ---- bilingual twins: same number printed once in Hindi and once in English
        val removed = HashSet<Question>()
        if (langPref in setOf("en", "hi", "both")) {
            val byNum = questions.filter { it.number != null }.groupBy { it.number!! }
            for ((_, qs) in byNum) {
                if (qs.size < 2) continue
                val ratios = qs.associateWith { devRatio(it.text) }
                val lo = qs.minBy { ratios[it]!! }
                val hi = qs.maxBy { ratios[it]!! }
                if (ratios[hi]!! - ratios[lo]!! < 0.3) continue
                val main = if (langPref == "hi") hi else lo
                for (q in qs) {
                    if (q === main || kotlin.math.abs(ratios[q]!! - ratios[main]!!) < 0.3) continue
                    removed += q
                    dropped.twins++
                    if (langPref == "both") { main.twins += q.root; main.twins += q.parts }
                }
                main.flag("bilingual_twin")
            }
        }
        // ---- duplicate pages / repeated photos: same number + same text
        val keep = mutableListOf<Question>()
        for (q in questions) {
            if (q in removed) continue
            val dup = keep.takeLast(60).any { k ->
                k.number != null && k.number == q.number && k.lang == q.lang && similar(k.text, q.text)
            }
            if (dup) dropped.duplicates++ else keep += q
        }

        // ---- sections: a restart to 1 (or 2) opens a new section only after >= 3 questions (context §5.1)
        var section = 0; var prev: Int? = null; var inSection = 0
        for (q in keep) {
            val n = q.number
            if (n != null && prev != null && n < prev && (n <= 2 || prev - n > 20) && inSection >= 3) {
                section++; inSection = 0
            }
            q.section = section
            inSection++
            if (n != null) prev = n
        }
        keep.forEachIndexed { i, q ->
            q.slideNo = i + 1
            if (q.number == null) q.flag("no_number")
            if (q.confidence < 0.6) q.flag("low_confidence")
        }
        for (q in keep) q.passageSlideOf = q.passageOf?.takeIf { it in keep }?.slideNo
        return Assembly(keep, dropped)
    }

    // ------------------------------------------------------------------ similarity (difflib replacement)
    private val NORM = Regex("""[^\p{L}\p{Nd}_ऀ-ॿ]+""")
    private fun norm(t: String) = t.lowercase().replace(NORM, " ").trim()

    fun similar(a0: String, b0: String): Boolean {
        val a = norm(a0).take(400); val b = norm(b0).take(400)
        if (a.isEmpty() || b.isEmpty()) return false
        return ratio(a, b) > 0.75
    }

    /** Ratcliff/Obershelp similarity, as difflib.SequenceMatcher.ratio(). */
    fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        return 2.0 * matches(a, 0, a.length, b, 0, b.length) / (a.length + b.length)
    }

    private fun matches(a: String, a0: Int, a1: Int, b: String, b0: Int, b1: Int): Int {
        if (a0 >= a1 || b0 >= b1) return 0
        var best = 0; var bi = 0; var bj = 0
        var prev = IntArray(b1 - b0 + 1)
        for (i in a0 until a1) {
            val cur = IntArray(b1 - b0 + 1)
            for (j in b0 until b1) {
                if (a[i] == b[j]) {
                    val v = prev[j - b0] + 1
                    cur[j - b0 + 1] = v
                    if (v > best) { best = v; bi = i - v + 1; bj = j - v + 1 }
                }
            }
            prev = cur
        }
        if (best == 0) return 0
        return best + matches(a, a0, bi, b, b0, bj) + matches(a, bi + best, a1, b, bj + best, b1)
    }

    // ------------------------------------------------------------------ reading order
    /**
     * Recursive XY-cut on boxes (legacy `reading_order`). Column gutters / a spread fold are cut first; wide blocks
     * bridging the gutters split the group into horizontal bands. Independent of the order an LLM emitted.
     */
    fun <T> readingOrder(items: List<T>, key: (T) -> Box): List<T> {
        if (items.size <= 1) return items.toList()

        fun gutters(group: List<T>): List<Double> {
            val spans = group.map { key(it).x0 to key(it).x1 }.sortedWith(compareBy({ it.first }, { it.second }))
            val out = mutableListOf<Double>()
            var reach = spans[0].second
            for ((a, b) in spans.drop(1)) {
                if (a > reach - 0.012) out += (reach + a) / 2
                reach = maxOf(reach, b)
            }
            return out
        }
        fun split(group: List<T>): Pair<List<T>, List<T>>? {
            for (g in gutters(group)) {
                val left = group.filter { (key(it).x0 + key(it).x1) / 2 < g }
                val right = group.filter { (key(it).x0 + key(it).x1) / 2 >= g }
                if (left.isNotEmpty() && right.isNotEmpty()) return left to right
            }
            return null
        }

        split(items)?.let { (l, r) -> return readingOrder(l, key) + readingOrder(r, key) }

        val gx0 = items.minOf { key(it).x0 }
        val gx1 = items.maxOf { key(it).x1 }
        val wide = items.filter { key(it).w > 0.5 * (gx1 - gx0) }
        val narrow = items.filter { it !in wide }
        if (wide.isNotEmpty() && narrow.isNotEmpty() && split(narrow) != null) {
            val out = mutableListOf<T>()
            var rest = narrow.sortedBy { key(it).y0 }
            for (w in wide.sortedBy { key(it).y0 }) {
                val wy = (key(w).y0 + key(w).y1) / 2
                val above = rest.filter { (key(it).y0 + key(it).y1) / 2 < wy }
                rest = rest.filter { it !in above }
                out += readingOrder(above, key)
                out += w
            }
            return out + readingOrder(rest, key)
        }
        return items.sortedWith(compareBy({ key(it).y0 }, { key(it).x0 }))
    }
}
