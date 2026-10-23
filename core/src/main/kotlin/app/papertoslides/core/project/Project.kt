package app.papertoslides.core.project

import app.papertoslides.core.assemble.Integrity
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.ladder.LadderResult
import app.papertoslides.core.ladder.PageInfo
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.model.Roles
import app.papertoslides.core.model.Tier
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.SlideTemplate
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class ProjectSettings(
    val layout: SlideLayout = SlideLayout.TOP,
    val blankAfter: Boolean = true,
    val removeWatermark: Boolean = true,
    /** en | hi | both (papers that print each question twice). */
    val lang: String = "en",
    /** Pixels per displayed inch of the question pictures. */
    val dpi: Int = 250,
    /** Light or dark slides. */
    val theme: DeckTheme = DeckTheme.LIGHT,
    /** Editable text when it can be trusted (AUTO), always text, or always a picture of the paper. */
    val style: QuestionStyle = QuestionStyle.AUTO,
    /** The teacher's slide design (copied into the project so later template edits never change this deck). */
    val template: SlideTemplate = SlideTemplate.PLAIN,
    val passageMode: PassageMode = PassageMode.REPEAT,
    /** Long questions may use the full height (quadrants I + IV) instead of being shrunk below [minPt]. */
    val expandLong: Boolean = true,
    val maxPt: Double = 28.0,
    val minPt: Double = 16.0,
)

@Serializable
data class ProjectPage(
    val seq: Int,
    val ref: String,
    val kind: PageKind,
    val file: Int,
    val index: Int,
    val wIn: Double,
    val hIn: Double,
    val nativePpi: Double? = null,
    val tier: Tier? = null,
    val duplicateOf: Int? = null,
)

@Serializable
data class Part(val page: Int, val box: Box, val erase: List<Box> = emptyList())

/** How a question is put on its slide. AUTO = editable text when the text can be trusted, else a picture. */
@Serializable
enum class QuestionStyle { AUTO, TEXT, PICTURE }

/** Comprehension passages: repeat the passage on every dependent question slide, or show it once before them. */
@Serializable
enum class PassageMode { REPEAT, ONCE }

@Serializable
data class Item(
    val id: String,
    val label: String,
    val number: Int? = null,
    val section: Int = 0,
    val lang: String = "en",
    val flags: List<String> = emptyList(),
    val parts: List<Part>,
    /** The other-language copy of this question (language "both"), shown after it. */
    val twinParts: List<Part> = emptyList(),
    /** Shared passage (comprehension / data set) this question depends on, on its pages. */
    val passageParts: List<Part> = emptyList(),
    /** Questions sharing one passage have the same group number; the first one has [passageHead]. */
    val passageGroup: Int? = null,
    val passageHead: Boolean = false,
    val stem: String = "",
    /** Options as Gemini transcribed them (other sources rebuild text from the page lines). */
    val options: List<String> = emptyList(),
    /** Passage text as Gemini transcribed it. */
    val passageText: String = "",
    /** Where text for the editable-text style comes from: pdf | ocr | gemini | none. */
    val textFrom: String = "none",
    /** Per-slide override of the deck's question style. */
    val style: QuestionStyle? = null,
    val confidence: Double = 1.0,
    /** Per-slide override of the global blank-after setting (null = use the global one). */
    val blankAfter: Boolean? = null,
    val passageSlideOf: Int? = null,
    val hasFigure: Boolean = false,
    val displayPt: Double? = null,
)

@Serializable
data class JobStats(
    val pages: Int = 0,
    val tiers: Map<String, Int> = emptyMap(),
    val apiPages: Int = 0,
    val apiRequests: Int = 0,
    val seconds: Double = 0.0,
)

/** One paper = one project. project.json is the single source of truth; the deck is re-made from it. */
@Serializable
data class Project(
    val version: Int = 1,
    val id: String,
    val name: String,
    val createdAt: Long,
    val sources: List<String>,
    val pages: List<ProjectPage>,
    val items: List<Item>,
    val settings: ProjectSettings,
    val integrity: Integrity? = null,
    val stats: JobStats = JobStats(),
    /** Bumped on every edit; a stale copy is never saved over a newer one. */
    val rev: Int = 0,
) {
    fun encode(): String = JSON.encodeToString(serializer(), this)

    fun blankAfter(item: Item) = item.blankAfter ?: settings.blankAfter

    companion object {
        val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = false }
        fun decode(text: String): Project = JSON.decodeFromString(serializer(), text)

        /** Questions -> slide items. Boxes are padded towards neighbours here, once. */
        fun itemsFrom(result: LadderResult, settings: ProjectSettings): List<Item> {
            val boxesByPage = HashMap<Int, List<Box>>()
            result.layouts.forEachIndexed { i, lay ->
                boxesByPage[i] = lay?.fragments?.mapNotNull { Box.fromThousands(it.box) } ?: emptyList()
            }
            val qs = result.assembly.questions
            val heads = qs.filter { it.passageParts.isNotEmpty() }
            return qs.mapIndexed { i, q ->
                val frags = (listOf(q.root) + q.parts).filter { it.role != Roles.CLUTTER }
                fun part(f: app.papertoslides.core.model.Fragment) = Part(f.pageSeq, Imaging.paddedBox(f.box, boxesByPage[f.pageSeq] ?: emptyList()), f.erase)
                val parts = frags.map { part(it) }
                val head = if (q.passageParts.isNotEmpty()) q else q.passageOf
                val passage = head?.passageParts ?: emptyList()
                val tier = result.tiers.getOrNull(q.root.pageSeq)
                val flags = q.flags.toMutableList()
                if (tier == Tier.T3_HEURISTIC && "low_confidence" !in flags) flags += "low_confidence"
                if (q.passageSlideOf != null && "refers_to_passage" !in flags) flags += "refers_to_passage"
                Item(
                    id = "q${i + 1}", label = q.label, number = q.number, section = q.section, lang = q.lang,
                    flags = flags, parts = parts, stem = q.text.take(1500), confidence = q.confidence,
                    passageParts = passage.map { part(it) },
                    twinParts = if (settings.lang == "both") q.twins.filter { it.role != Roles.CLUTTER }.map { part(it) } else emptyList(), passageGroup = head?.let { heads.indexOf(it) }?.takeIf { it >= 0 },
                    passageHead = head === q, options = (listOf(q.root) + q.parts).flatMap { it.options },
                    passageText = passage.joinToString("\n") { it.text },
                    textFrom = when (tier) { Tier.T0_TEXT -> "pdf"; Tier.T1_OCR -> "ocr"; Tier.T2_GEMINI -> "gemini"; else -> "none" },
                    passageSlideOf = q.passageSlideOf, hasFigure = frags.any { it.hasFigure },
                )
            }
        }

        fun pagesFrom(infos: List<PageInfo>, meta: List<ProjectPage>, result: LadderResult): List<ProjectPage> =
            meta.mapIndexed { i, p -> p.copy(tier = result.tiers.getOrNull(i), duplicateOf = infos.getOrNull(i)?.duplicateOf) }
    }
}
