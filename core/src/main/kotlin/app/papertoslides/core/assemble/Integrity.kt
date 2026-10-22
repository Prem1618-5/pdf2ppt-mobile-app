package app.papertoslides.core.assemble

import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.Question
import app.papertoslides.core.model.Roles
import kotlinx.serialization.Serializable

/** What the ladder knows about one page when judging integrity. */
data class PageFacts(
    val layout: PageLayout?,
    /** The page has visible content (text lines or ink). */
    val hasInk: Boolean,
    val duplicate: Boolean = false,
)

@Serializable
data class SectionInfo(val section: Int, val first: Int?, val last: Int?, val count: Int)

@Serializable
data class Integrity(
    val ok: Boolean,
    val detected: Int,
    val sections: List<SectionInfo>,
    val gaps: List<String>,
    val duplicates: List<String>,
    val unnumbered: Int,
    val unaccountedPages: List<Int>,
    /** Pages that should go to the next ladder tier. */
    val failingPages: List<Int>,
    val reasons: List<String>,
)

/**
 * Replacement of the legacy `integrity_check().ok`, which had false passes (context §5.1, §10 #3).
 *
 * A run is trusted only if: >= 3 questions, all numbered, consecutive per section (gaps and duplicates fail);
 * every page with content is accounted for; zero questions is never ok; answer-key pages are already removed.
 */
object IntegrityCheck {

    private val KNOWN_EMPTY = setOf(
        PageLayout.KIND_ANSWER_KEY, PageLayout.KIND_BLANK, PageLayout.KIND_COVER,
        PageLayout.KIND_SOLUTIONS, PageLayout.KIND_DUPLICATE,
    )

    fun check(questions: List<Question>, pages: List<PageFacts>): Integrity {
        val reasons = mutableListOf<String>()
        val failing = sortedSetOf<Int>()
        val inkPages = pages.indices.filter { pages[it].hasInk && !pages[it].duplicate }

        // rule 2: every page accounted for
        val anchorPages = pages.indices.filter { i ->
            pages[i].layout?.fragments?.any { it.role == Roles.QUESTION || it.role == Roles.CONTINUATION || it.role == Roles.SHARED_PASSAGE } == true
        }.toSet()
        val unaccounted = pages.indices.filter { i ->
            val p = pages[i]
            !p.duplicate && p.hasInk && i !in anchorPages && (p.layout == null || p.layout.pageKind !in KNOWN_EMPTY)
        }
        // a lone text page with no anchors next to pages without anchors (e.g. a cover) only fails
        // when the file has anchors somewhere, or when nothing at all was found
        if (unaccounted.isNotEmpty()) {
            reasons += "pages without questions: ${unaccounted.joinToString { (it + 1).toString() }}"
            failing += unaccounted
        }

        // rule 3: zero questions is never ok; rule 1: at least 3
        if (questions.isEmpty()) {
            reasons += "no questions found"
            failing += inkPages
        } else if (questions.size < 3) {
            reasons += "only ${questions.size} question(s) found"
            failing += questions.flatMap { it.pages }
        }

        // rule 1: all numbered
        val unnumbered = questions.filter { it.number == null }
        if (unnumbered.isNotEmpty()) {
            reasons += "${unnumbered.size} question(s) without a number"
            failing += unnumbered.flatMap { it.pages }
        }

        // rule 1: consecutive per section
        val gaps = mutableListOf<String>()
        val dups = mutableListOf<String>()
        val sections = mutableListOf<SectionInfo>()
        for ((s, qs) in questions.groupBy { it.section }.toSortedMap()) {
            val numbered = qs.filter { it.number != null }
            if (numbered.isEmpty()) { sections += SectionInfo(s + 1, null, null, qs.size); continue }
            val seen = HashSet<Int>()
            for (q in numbered) {
                if (!seen.add(q.number!!)) { dups += "S${s + 1}:${q.number}"; failing += q.pages }
            }
            for ((a, b) in numbered.zipWithNext()) {
                val na = a.number!!; val nb = b.number!!
                if (nb != na + 1 && nb !in 1..na) {
                    for (n in na + 1 until nb) gaps += "S${s + 1}:$n"
                    failing += (a.root.pageSeq..b.root.pageSeq)
                }
            }
            sections += SectionInfo(s + 1, numbered.minOf { it.number!! }, numbered.maxOf { it.number!! }, qs.size)
        }
        if (gaps.isNotEmpty()) reasons += "missing numbers: ${gaps.joinToString()}"
        if (dups.isNotEmpty()) reasons += "repeated numbers: ${dups.joinToString()}"

        val ok = reasons.isEmpty()
        return Integrity(
            ok = ok, detected = questions.size, sections = sections, gaps = gaps, duplicates = dups,
            unnumbered = unnumbered.size, unaccountedPages = unaccounted,
            failingPages = if (ok) emptyList() else failing.filter { it in pages.indices }, reasons = reasons,
        )
    }
}
