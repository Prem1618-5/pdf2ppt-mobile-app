package app.papertoslides.core.ladder

import app.papertoslides.core.assemble.Assembler
import app.papertoslides.core.assemble.Assembly
import app.papertoslides.core.assemble.Integrity
import app.papertoslides.core.assemble.IntegrityCheck
import app.papertoslides.core.assemble.PageFacts
import app.papertoslides.core.layout.ClassicalLayout
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.Roles
import app.papertoslides.core.model.TextLine
import app.papertoslides.core.model.Tier

/** What pass 1 learned about a page (cheap, small-resolution work only). */
data class PageInfo(
    val index: Int,
    val kind: PageKind,
    /** PDF text layer lines (empty for images and scans). */
    val textLines: List<TextLine>,
    val figures: List<Box> = emptyList(),
    val hasInk: Boolean = textLines.isNotEmpty(),
    val duplicateOf: Int? = null,
)

data class LadderResult(
    val layouts: List<PageLayout?>,
    val tiers: List<Tier?>,
    val assembly: Assembly,
    val integrity: Integrity,
    val geminiPages: Int,
    val rounds: Int,
)

/** Hooks into the platform. Each may be absent (null) when that tier cannot run. */
class LadderTiers(
    /** T1: on-device OCR lines of a page, or null if OCR is unavailable / failed. */
    val ocr: (suspend (page: Int) -> List<TextLine>?)? = null,
    /** T2: Gemini layouts for these pages (through the slice scheduler); pages it could not do are missing. */
    val gemini: (suspend (pages: List<Int>) -> Map<Int, PageLayout>)? = null,
    /** T3: projection-profile heuristic, always low confidence. */
    val heuristic: (suspend (page: Int) -> PageLayout)? = null,
)

fun interface LadderListener { fun onStage(stage: String, detail: String) }

/**
 * Layout ladder (context §5.1): per page, the cheapest method first; escalate only the pages that fail the
 * integrity check, never the whole file. Zero questions is never accepted silently.
 */
class LayoutLadder(
    private val pages: List<PageInfo>,
    private val tiers: LadderTiers,
    private val langPref: String = "en",
    private val listener: LadderListener = LadderListener { _, _ -> },
) {
    private val tier = arrayOfNulls<Tier>(pages.size)
    private val ocrLines = HashMap<Int, List<TextLine>?>()
    private val external = HashMap<Int, PageLayout>()  // T2 / T3 results
    private val geminiTried = HashSet<Int>()
    private var geminiPages = 0

    private fun realText(lines: List<TextLine>) = lines.sumOf { l -> l.text.count { it.isLetterOrDigit() } } >= 8

    suspend fun run(): LadderResult {
        for (p in pages) {
            tier[p.index] = when {
                p.duplicateOf != null -> null
                // legacy defect #2 fixed: a page is "digital" by its real text lines, not by a 200-char threshold
                realText(p.textLines) -> Tier.T0_TEXT
                else -> Tier.T1_OCR
            }
        }
        var rounds = 0
        var state = evaluate()
        while (!state.third.ok && rounds < 4) {
            rounds++
            val failing = state.third.failingPages.filter { tier[it] != null }
            if (!escalate(failing, state.first)) break
            state = evaluate()
        }
        val (layouts, asm, integ) = state
        return LadderResult(layouts, tier.toList(), asm, integ, geminiPages, rounds)
    }

    /** Moves failing pages one rung up. Returns false if nothing could move. */
    private suspend fun escalate(failing: List<Int>, layouts: List<PageLayout?>): Boolean {
        var moved = false
        val toGemini = mutableListOf<Int>()
        for (p in failing) {
            when (tier[p]) {
                Tier.T0_TEXT -> when {
                    tiers.ocr != null -> { tier[p] = Tier.T1_OCR; moved = true }
                    canGemini(p) -> toGemini += p
                    needsHeuristic(p, layouts) -> { heuristic(p); moved = true }
                }
                Tier.T1_OCR -> if (canGemini(p)) toGemini += p else if (needsHeuristic(p, layouts)) { heuristic(p); moved = true }
                Tier.T2_GEMINI -> if (needsHeuristic(p, layouts)) { heuristic(p); moved = true }
                else -> {}
            }
        }
        if (toGemini.isNotEmpty()) {
            listener.onStage("gemini", "${toGemini.size} page(s)")
            geminiTried += toGemini
            val got = tiers.gemini!!.invoke(toGemini.sorted())
            geminiPages += got.size
            for ((p, lay) in got) { external[p] = lay; tier[p] = Tier.T2_GEMINI }
            moved = moved || got.isNotEmpty()
            // pages no key could do: keep the best on-device result, heuristic only if that found nothing
            for (p in toGemini) if (p !in got && needsHeuristic(p, layouts)) { heuristic(p); moved = true }
        }
        return moved
    }

    private fun canGemini(p: Int) = tiers.gemini != null && p !in geminiTried

    private fun needsHeuristic(p: Int, layouts: List<PageLayout?>) =
        tiers.heuristic != null && tier[p] != Tier.T3_HEURISTIC &&
            (layouts.getOrNull(p)?.fragments?.none { it.role == Roles.QUESTION || it.role == Roles.CONTINUATION } ?: true)

    private suspend fun heuristic(p: Int) {
        external[p] = tiers.heuristic!!.invoke(p)
        tier[p] = Tier.T3_HEURISTIC
    }

    /** Sequential classical parse of T0/T1 pages (the numbering sequence runs across pages), then assemble + check. */
    private suspend fun evaluate(): Triple<List<PageLayout?>, Assembly, Integrity> {
        val lines = pages.map { p ->
            when (tier[p.index]) {
                Tier.T0_TEXT -> p.textLines
                Tier.T1_OCR -> ocrFor(p.index) ?: emptyList()
                else -> emptyList()
            }
        }
        val text = ClassicalLayout(ocr = false).apply { prime(lines.filterIndexed { i, _ -> tier[i] == Tier.T0_TEXT }, pages.map { it.figures }) }
        val ocr = ClassicalLayout(ocr = true).apply { prime(lines.filterIndexed { i, _ -> tier[i] == Tier.T1_OCR }) }
        val layouts = ArrayList<PageLayout?>(pages.size)
        var firstQuestionSeen = false
        for (p in pages) {
            val i = p.index
            val lay: PageLayout? = when (tier[i]) {
                null -> PageLayout(pageKind = PageLayout.KIND_DUPLICATE, model = "skip")
                Tier.T0_TEXT, Tier.T1_OCR -> {
                    val parser = if (tier[i] == Tier.T0_TEXT) text else ocr
                    val other = if (parser === text) ocr else text
                    parser.expectedNext = maxOf(parser.expectedNext ?: 0, other.expectedNext ?: 0).takeIf { it > 0 }
                    var l = parser.analyseTextPage(lines[i], if (tier[i] == Tier.T0_TEXT) p.figures else emptyList())
                    other.observe(l)
                    if (!firstQuestionSeen && l.questionCount == 0 && lines[i].any { INSTRUCTIONS.containsMatchIn(it.text) }) {
                        l = l.copy(pageKind = PageLayout.KIND_COVER) // cover / instructions page before the first question
                    }
                    l
                }
                else -> external[i]?.also { text.observe(it); ocr.observe(it) }
            }
            if ((lay?.questionCount ?: 0) > 0) firstQuestionSeen = true
            layouts += lay
        }
        val asm = Assembler.assemble(Assembler.fragmentsOf(layouts), langPref)
        val facts = pages.map { p ->
            val hasInk = p.hasInk || lines[p.index].isNotEmpty()
            PageFacts(layouts[p.index], hasInk, duplicate = p.duplicateOf != null)
        }
        return Triple(layouts, asm, IntegrityCheck.check(asm.questions, facts))
    }

    private suspend fun ocrFor(p: Int): List<TextLine>? {
        if (p in ocrLines) return ocrLines[p]
        listener.onStage("ocr", "page ${p + 1}")
        val r = tiers.ocr?.invoke(p)
        ocrLines[p] = r
        return r
    }

    companion object {
        private val INSTRUCTIONS = Regex("""\b(instructions?|candidates?|duration|time allowed|maximum marks|read the following)\b""", RegexOption.IGNORE_CASE)
    }
}
