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

@Serializable
data class Item(
    val id: String,
    val label: String,
    val number: Int? = null,
    val section: Int = 0,
    val lang: String = "en",
    val flags: List<String> = emptyList(),
    val parts: List<Part>,
    val stem: String = "",
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
            return result.assembly.questions.mapIndexed { i, q ->
                val frags = (listOfNotNull(q.passage) + q.root + q.parts + (if (settings.lang == "both") q.twins else emptyList()))
                    .filter { it.role != Roles.CLUTTER }
                val parts = frags.map { f ->
                    Part(f.pageSeq, Imaging.paddedBox(f.box, boxesByPage[f.pageSeq] ?: emptyList()), f.erase)
                }
                val flags = q.flags.toMutableList()
                val tier = result.tiers.getOrNull(q.root.pageSeq)
                if (tier == Tier.T3_HEURISTIC && "low_confidence" !in flags) flags += "low_confidence"
                if (q.passageSlideOf != null && "refers_to_passage" !in flags) flags += "refers_to_passage"
                Item(
                    id = "q${i + 1}", label = q.label, number = q.number, section = q.section, lang = q.lang,
                    flags = flags, parts = parts, stem = q.text.take(1500), confidence = q.confidence,
                    passageSlideOf = q.passageSlideOf, hasFigure = frags.any { it.hasFigure },
                )
            }
        }

        fun pagesFrom(infos: List<PageInfo>, meta: List<ProjectPage>, result: LadderResult): List<ProjectPage> =
            meta.mapIndexed { i, p -> p.copy(tier = result.tiers.getOrNull(i), duplicateOf = infos.getOrNull(i)?.duplicateOf) }
    }
}
