package app.papertoslides.core.project

import app.papertoslides.core.geometry.Geometry
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.pptx.PptxWriter
import app.papertoslides.core.pptx.SlideSpec
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Re-renders a page region from the ORIGINAL source (PDF clip render or native photo pixels). */
fun interface PageCropper {
    /** [box] in page fractions; returns pixels at [pxPerIn] (for photos the native pixels, never upscaled). */
    suspend fun crop(page: ProjectPage, box: Box, pxPerIn: Double): Raster
}

data class Encoded(val bytes: ByteArray, val format: String, val w: Int, val h: Int)

/** PNG for text / line art, JPEG only for colour photos (legacy `encode`). Platform codec. */
fun interface Encoder { fun encode(img: Raster, maxW: Int, maxH: Int, colour: Boolean): Encoded }

data class Rendered(val img: Raster, val ppi: Double, val glyphPt: Double?)

/** Port of legacy `render_parts`: crop -> erase -> to_light -> strip_rules -> tighten -> stack -> compress gaps -> tighten. */
class ItemRenderer(private val cropper: PageCropper) {

    fun targetDpi(project: Project, item: Item): Int =
        Geometry.targetDpi(project.settings.layout, item.parts.map { p ->
            val m = project.pages[p.page]
            p.box.w * m.wIn to p.box.h * m.hIn
        }, project.settings.dpi)

    suspend fun render(project: Project, item: Item): Rendered {
        val s = project.settings
        val dpi = targetDpi(project, item).toDouble()
        val imgs = ArrayList<Raster>(); val ppis = ArrayList<Double>(); val lefts = ArrayList<Double>()
        for (part in item.parts) {
            val meta = project.pages[part.page]
            val box = part.box
            var ppi: Double
            var img: Raster
            when (meta.kind) {
                PageKind.PDF_DIGITAL -> { img = cropper.crop(meta, box, dpi); ppi = dpi } // vector: render sharp
                PageKind.IMAGE -> {
                    img = cropper.crop(meta, box, 10_000.0) // native pixels, no resampling
                    ppi = img.w / max(1e-6, box.w * meta.wIn)
                }
                PageKind.PDF_SCAN -> { ppi = min(dpi, meta.nativePpi ?: dpi); img = cropper.crop(meta, box, ppi) }
            }
            if (part.erase.isNotEmpty()) {
                img = Imaging.erase(img, part.erase.map { e ->
                    intArrayOf(
                        ((e.x0 - box.x0) / box.w * img.w).toInt(), ((e.y0 - box.y0) / box.h * img.h).toInt(),
                        ((e.x1 - box.x0) / box.w * img.w).toInt(), ((e.y1 - box.y0) / box.h * img.h).toInt(),
                    )
                })
            }
            img = Imaging.toLight(img, s.removeWatermark, photo = meta.kind != PageKind.PDF_DIGITAL)
            img = Imaging.stripRules(img)
            val (t, rect) = Imaging.tighten(img)
            img = t
            val rectPpi = ppi
            if (meta.kind != PageKind.PDF_DIGITAL) {
                // photos / scans: upscale gently towards the display resolution and crisp the ink
                val f = min(2.5, dpi / ppi)
                if (f > 1.15) { img = img.resize((img.w * f).toInt(), (img.h * f).toInt()); ppi *= f }
                img = Imaging.sharpen(img, 0.8)
            }
            imgs += img; ppis += ppi
            lefts += box.x0 * meta.wIn + rect[0] / rectPpi
        }
        if (imgs.isEmpty()) return Rendered(Raster(400, 120), 200.0, null)
        val target = ppis.min()
        val scaled = imgs.mapIndexed { i, im ->
            if (ppis[i] > target * 1.05) { val f = target / ppis[i]; im.resize(max(1, (im.w * f).toInt()), max(1, (im.h * f).toInt())) } else im
        }
        val aligned = lefts.max() - lefts.min() < 1.0
        var img = Imaging.stack(scaled, offsets = if (aligned) lefts.map { ((it - lefts.min()) * target).roundToInt() } else null)
        if (!item.hasFigure) img = Imaging.compressGaps(img, target)
        img = Imaging.tighten(img).first
        return Rendered(img, target, Imaging.textHeightPt(img, target))
    }
}

data class ExportResult(val slides: Int, val project: Project)

/** Writes the deck from the project (both layouts; switching layout re-renders, never calls the API). */
object DeckExporter {
    suspend fun export(
        project: Project,
        out: OutputStream,
        render: suspend (Item) -> Rendered,
        encoder: Encoder,
        onItem: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): ExportResult {
        val layout = project.settings.layout
        val box = layout.box()
        val dpi = project.settings.dpi
        val items = ArrayList<Item>()
        PptxWriter(out, project.name).use { w ->
            project.items.forEachIndexed { n, it ->
                val r = render(it)
                val enc = encoder.encode(r.img, (box.w * dpi * 1.05).toInt(), (box.h * dpi * 1.05).toInt(), Imaging.colourfulness(r.img) >= 6)
                val p = Geometry.place(layout, enc.w, enc.h, srcWidthIn = r.img.w / r.ppi, glyphPt = r.glyphPt)
                w.add(SlideSpec.Picture(enc.bytes, enc.format, p.rect, altText(it)))
                if (project.blankAfter(it)) w.add(SlideSpec.Blank)
                val flags = it.flags.filter { f -> f != "small_text" && f != "too_small_to_read" } + Geometry.sizeFlags(p.displayPt)
                items += it.copy(flags = flags.distinct(), displayPt = p.displayPt)
                onItem(n + 1, project.items.size)
            }
            return ExportResult(w.slideCount, project.copy(items = items))
        }
    }

    fun altText(it: Item): String = buildString {
        append("Question ").append(it.label)
        if (it.passageSlideOf != null) append(". Passage: see slide ").append(it.passageSlideOf)
        if (it.stem.isNotBlank()) append(". ").append(it.stem.take(300))
    }
}

/** QA report: one row per slide with flags, plus the integrity summary. Plain text the teacher can read. */
object QaReport {
    val FLAG_TEXT = mapOf(
        "low_confidence" to "check this slide (uncertain cut)",
        "no_number" to "question number not found",
        "small_text" to "small on the slide",
        "too_small_to_read" to "too small to read on the slide",
        "split_stitched" to "joined from two columns",
        "page_break_stitched" to "joined across pages",
        "shared_passage" to "passage shown with this question",
        "refers_to_passage" to "uses the passage of an earlier slide",
        "bilingual_twin" to "other-language copy removed",
    )

    fun csv(p: Project): String = buildString {
        append("slide,label,number,section,lang,pages,confidence,display_pt,flags,text\n")
        p.items.forEachIndexed { i, it ->
            val pages = it.parts.map { pt -> p.pages[pt.page].ref }.distinct().joinToString(" + ")
            append(listOf((i + 1).toString(), it.label, it.number?.toString() ?: "", (it.section + 1).toString(), it.lang, pages,
                "%.2f".format(it.confidence), it.displayPt?.toString() ?: "", it.flags.joinToString(";"), it.stem.take(300))
                .joinToString(",") { v -> "\"" + v.replace("\"", "\"\"") + "\"" })
            append('\n')
        }
    }

    fun text(p: Project): String = buildString {
        val ig = p.integrity
        append("Paper: ${p.name}\n")
        append("Questions: ${p.items.size}  ·  pages: ${p.pages.size}  ·  Gemini pages: ${p.stats.apiPages} (${p.stats.apiRequests} requests)\n")
        if (ig != null) {
            append(if (ig.ok) "Numbering check: OK\n" else "Numbering check: NEEDS A LOOK — ${ig.reasons.joinToString("; ")}\n")
        }
        val flagged = p.items.withIndex().filter { (_, it) -> it.flags.any { f -> f in setOf("low_confidence", "no_number", "small_text", "too_small_to_read") } }
        if (flagged.isEmpty()) append("No slides flagged.\n")
        for ((i, it) in flagged) {
            append("Slide question ${i + 1} (Q ${it.label}): ")
            append(it.flags.mapNotNull { FLAG_TEXT[it] }.joinToString(", ")).append('\n')
        }
    }
}
