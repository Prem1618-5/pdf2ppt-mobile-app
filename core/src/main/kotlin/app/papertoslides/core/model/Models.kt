package app.papertoslides.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** A piece of a line with one vertical position: 0 normal, 1 superscript, -1 subscript. */
@Serializable
data class TextRun(val text: String, val rise: Int = 0)

/** One text line on a page; coordinates are fractions (0..1) of the page, `size` is the font size or line height. */
@Serializable
data class TextLine(
    val x0: Double,
    val y0: Double,
    val x1: Double,
    val y1: Double,
    val text: String,
    val size: Double = 0.0,
    /** Formatting runs (super/subscripts); empty = one plain run equal to [text]. */
    val runs: List<TextRun> = emptyList(),
    /** Recognition confidence (OCR); 1 for a PDF text layer. */
    val conf: Double = 1.0,
) {
    fun runsOrPlain(): List<TextRun> = runs.ifEmpty { listOf(TextRun(text)) }
}

/** Rectangle as page fractions (x0, y0, x1, y1). */
@Serializable
data class Box(val x0: Double, val y0: Double, val x1: Double, val y1: Double) {
    val w get() = x1 - x0
    val h get() = y1 - y0

    /** Gemini / legacy order: [ymin, xmin, ymax, xmax] in 0..1000. */
    fun toThousands(): List<Int> =
        listOf((y0 * 1000).toInt(), (x0 * 1000).toInt(), kotlin.math.ceil(y1 * 1000).toInt(), kotlin.math.ceil(x1 * 1000).toInt())

    companion object {
        /** Legacy `_box`: [ymin, xmin, ymax, xmax] 0..1000 -> fractions, or null if degenerate. */
        fun fromThousands(b: List<Int>): Box? {
            val v = (b + listOf(0, 0, 1000, 1000)).take(4).map { it.coerceIn(0, 1000) / 1000.0 }
            val (y0, x0, y1, x1) = v
            if (x1 <= x0 || y1 <= y0) return null
            return Box(x0, y0, x1, y1)
        }
    }
}

enum class PageKind { PDF_DIGITAL, PDF_SCAN, IMAGE }

/** Which rung of the layout ladder produced a page's layout (context file §5.1). */
enum class Tier { T0_TEXT, T1_OCR, T2_GEMINI, T3_HEURISTIC }

object Roles {
    const val QUESTION = "question"
    const val CONTINUATION = "continuation"
    const val SHARED_PASSAGE = "shared_passage"
    const val SOLUTION = "solution"
    const val ANSWER_KEY = "answer_key"
    const val INSTRUCTIONS = "instructions"
    const val SECTION_HEADER = "section_header"
    const val CLUTTER = "clutter"
    val ALL = listOf(QUESTION, CONTINUATION, SHARED_PASSAGE, SOLUTION, ANSWER_KEY, INSTRUCTIONS, SECTION_HEADER, CLUTTER)
}

/** A layout fragment exactly as Gemini returns it (also produced by the classical parser). */
@Serializable
data class RawFragment(
    val order: Int = 0,
    val role: String = Roles.QUESTION,
    val label: String? = null,
    val number: Int? = null,
    @SerialName("continues_previous") val continuesPrevious: Boolean = false,
    @SerialName("continues_on_next") val continuesOnNext: Boolean = false,
    val box: List<Int> = emptyList(),
    val lang: String = "en",
    @SerialName("has_figure") val hasFigure: Boolean = false,
    @SerialName("has_math") val hasMath: Boolean = false,
    @SerialName("has_table") val hasTable: Boolean = false,
    val stem: String = "",
    val options: List<String> = emptyList(),
    val confidence: Double = 0.5,
    /** Fractions [x0, y0, x1, y1] to white out (duplicate question-ID column). */
    val erase: List<List<Double>> = emptyList(),
)

@Serializable
data class PageLayout(
    val page: Int = 0,
    @SerialName("page_kind") val pageKind: String = "questions",
    @SerialName("dark_background") val darkBackground: Boolean = false,
    @SerialName("watermark_present") val watermarkPresent: Boolean = false,
    val fragments: List<RawFragment> = emptyList(),
    @SerialName("_model") val model: String = "",
) {
    val questionCount get() = fragments.count { it.role == Roles.QUESTION }

    companion object {
        const val KIND_QUESTIONS = "questions"
        const val KIND_ANSWER_KEY = "answer_key"
        const val KIND_BLANK = "blank_or_ad"
        const val KIND_COVER = "cover_or_instructions"
        const val KIND_SOLUTIONS = "solutions"
        const val KIND_DUPLICATE = "duplicate"
    }
}

/** Assembler input: one fragment placed on a page (port of assemble.Fragment). */
data class Fragment(
    val pageSeq: Int,
    val order: Int,
    val role: String,
    val label: String?,
    val number: Int?,
    val lang: String,
    val box: Box,
    val text: String,
    val options: List<String>,
    val confidence: Double,
    val hasFigure: Boolean = false,
    val continuesPrevious: Boolean = false,
    val continuesOnNext: Boolean = false,
    val model: String = "",
    val erase: List<Box> = emptyList(),
)

/** A question built from fragments (port of assemble.Question). */
class Question(val root: Fragment) {
    val parts = mutableListOf<Fragment>()
    /** The shared passage (with its continuation parts) when this question is the first of its group. */
    val passageParts = mutableListOf<Fragment>()
    val passage get() = passageParts.firstOrNull()
    var passageOf: Question? = null
    var passageSlideOf: Int? = null
    val twins = mutableListOf<Fragment>()
    val flags = mutableListOf<String>()
    var section = 0
    var slideNo = 0

    val number get() = root.number
    val label get() = root.label ?: root.number?.toString() ?: "?"
    val lang get() = root.lang
    val fragments get() = passageParts + root + parts + twins
    val confidence get() = (listOf(root) + parts).minOf { it.confidence }
    val text get() = (listOf(root) + parts).map { it.text }.filter { it.isNotEmpty() }.joinToString(" ").trim()
    val pages get() = fragments.map { it.pageSeq }.distinct()

    fun flag(f: String) { if (f !in flags) flags += f }
}
