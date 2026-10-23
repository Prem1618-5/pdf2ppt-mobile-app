package app.papertoslides.core.project

import app.papertoslides.core.compose.Block
import app.papertoslides.core.compose.Composer
import app.papertoslides.core.compose.Composition
import app.papertoslides.core.compose.Extractor
import app.papertoslides.core.compose.PartInput
import app.papertoslides.core.compose.QuestionContent
import app.papertoslides.core.compose.Shape
import app.papertoslides.core.geometry.Geometry
import app.papertoslides.core.geometry.INSET
import app.papertoslides.core.geometry.RectIn
import app.papertoslides.core.geometry.SMALL_TEXT_PT
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.model.TextLine
import app.papertoslides.core.pptx.GroupItem
import app.papertoslides.core.pptx.LayoutSpec
import app.papertoslides.core.pptx.PptxWriter
import app.papertoslides.core.pptx.SlideSpec
import app.papertoslides.core.pptx.TextPara
import app.papertoslides.core.pptx.TextRunSpec
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.ThemeColours
import java.io.OutputStream
import kotlin.math.max
import kotlin.math.min

/** What the deck builder needs for one item: pictures of the paper and, when possible, its text content. */
interface ItemSource {
    suspend fun picture(item: Item): Rendered
    suspend fun passagePicture(item: Item): Rendered?
    suspend fun content(item: Item): QuestionContent?
    suspend fun passageContent(item: Item): QuestionContent?
}

/**
 * The platform-independent item source: pictures via [ItemRenderer], text from the stored page lines (PDF text
 * layer / OCR) or from Gemini's transcription, figures cut from crops of the original page.
 */
class PageItemSource(
    private val project: Project,
    private val cropper: PageCropper,
    /** Text lines of a page (text layer or OCR), null if none are stored. */
    private val linesOf: suspend (page: Int) -> List<TextLine>?,
    private val renderer: ItemRenderer = ItemRenderer(cropper),
) : ItemSource {
    override suspend fun picture(item: Item) = renderer.render(project, item.copy(parts = item.parts + item.twinParts))

    override suspend fun passagePicture(item: Item) =
        if (item.passageParts.isEmpty()) null else renderer.render(project, item.copy(parts = item.passageParts, hasFigure = true))

    override suspend fun content(item: Item): QuestionContent? {
        if (item.textFrom == "gemini") {
            val c = Extractor.fromGemini(item.label.let { if (it.endsWith(".")) it else "$it." }, item.stem, item.options, null)
            if (!item.hasFigure) return c
            // Gemini gives the text; the figures still come from the page, which needs line boxes (OCR)
            val withLines = inputs(item.parts) ?: return c.copy(unsafe = c.unsafe + "figure without text boxes")
            val figs = Extractor.extract(withLines, item.number).blocks.filterIsInstance<Block.Figure>()
            if (figs.isEmpty()) return c.copy(unsafe = c.unsafe + "figure not found")
            val stem = c.blocks
            return c.copy(blocks = stem + figs)
        }
        if (item.textFrom != "pdf" && item.textFrom != "ocr") return null
        val ins = inputs(item.parts) ?: return null
        val main = Extractor.extract(ins, item.number)
        if (item.twinParts.isEmpty()) return main
        val twin = inputs(item.twinParts)?.let { Extractor.extract(it, item.number) } ?: return main.copy(unsafe = main.unsafe + "twin without text")
        return main.copy(twin = twin, unsafe = main.unsafe + twin.unsafe)
    }

    override suspend fun passageContent(item: Item): QuestionContent? {
        if (item.passageParts.isEmpty()) return null
        if (item.textFrom == "gemini" && item.passageText.isNotBlank()) return Extractor.fromGemini("", "", emptyList(), item.passageText)
        val ins = inputs(item.passageParts) ?: return null
        return Extractor.extract(ins, null, passage = true)
    }

    /** Crops at a resolution good for figures that are magnified like the text (about 2.5 x). */
    private suspend fun inputs(parts: List<Part>): List<PartInput>? = parts.map { part ->
        val meta = project.pages[part.page]
        val lines = linesOf(part.page)?.takeIf { it.isNotEmpty() } ?: return null
        val ppi = when (meta.kind) {
            PageKind.PDF_DIGITAL -> min(400.0, project.settings.dpi * 1.6)
            PageKind.PDF_SCAN -> min(400.0, meta.nativePpi ?: 300.0)
            PageKind.IMAGE -> 10_000.0
        }
        var img = cropper.crop(meta, part.box, ppi)
        val realPpi = img.w / max(1e-6, part.box.w * meta.wIn)
        img = Imaging.toLight(img, project.settings.removeWatermark, photo = meta.kind != PageKind.PDF_DIGITAL)
        PartInput(img, realPpi, part.box, lines, meta.wIn, meta.hIn)
    }
}

/** One slide in the finished deck, for validation and for the review screen. */
data class SlideRecord(val kind: String, val itemId: String?, val box: RectIn?, val isText: Boolean, val fontPt: Double?, val expanded: Boolean)

data class ExportResult(val slides: Int, val project: Project, val records: List<SlideRecord> = emptyList())

/**
 * Writes the deck from the project: template on the slide layout, light/dark theme, each question as editable
 * text in an invisible container (or a picture when its text cannot be trusted), passages repeated on every
 * dependent question or shown once on their own slide when they do not fit. Never calls the API.
 */
object DeckExporter {
    suspend fun export(
        project: Project,
        out: OutputStream,
        source: ItemSource,
        encoder: Encoder,
        templateImages: Map<String, Pair<ByteArray, String>> = emptyMap(),
        onItem: (done: Int, total: Int) -> Unit = { _, _ -> },
        /** Every question / passage slide as written (the app keeps a preview of it). */
        onSlide: (SlideRecord, SlideSpec) -> Unit = { _, _ -> },
    ): ExportResult {
        val s = project.settings
        val t = s.template
        val colours = ThemeColours.of(s.theme, t)
        val area = t.questionArea
        val layout = s.layout
        val box = layout.box(area)
        val tall = layout.box(area, expanded = true)
        val full = RectIn(area.x + INSET, area.y + INSET, area.w - 2 * INSET, area.h - 2 * INSET)
        val composer = Composer(colours, s.maxPt, s.minPt)
        val records = mutableListOf<SlideRecord>()
        val items = ArrayList<Item>()
        val passageSlide = HashMap<Int, Int>() // passage group -> slide number of its own passage slide
        val groupOnce = HashSet<Int>()

        PptxWriter(out, project.name, LayoutSpec(colours.bg, t.elements, templateImages)).use { w ->
            fun themed(img: Raster) = if (s.theme == DeckTheme.DARK) Imaging.toTheme(img, colours.bg, colours.text) else img
            fun pic(img: Raster, rect: RectIn, alt: String): SlideSpec.Picture {
                val e = encoder.encode(themed(img), (rect.w * s.dpi * 1.05).toInt().coerceAtLeast(1), (rect.h * s.dpi * 1.05).toInt().coerceAtLeast(1), Imaging.colourfulness(img) >= 6)
                return SlideSpec.Picture(e.bytes, e.format, rect, alt)
            }
            fun group(c: Composition, name: String, alt: String) = SlideSpec.Group(name, alt, c.shapes.map { sh ->
                when (sh) {
                    is Shape.TextBox -> GroupItem.Text(sh.rect, sh.paras.map { p ->
                        TextPara(p.runs.map { TextRunSpec(it.text, it.color, it.bold, it.italic, it.rise) }, p.sizePt, p.marLIn, p.indentIn, p.tabsIn, p.spaceBeforePt)
                    })
                    is Shape.Pic -> {
                        val e = encoder.encode(themed(sh.img), (sh.rect.w * s.dpi * 1.05).toInt().coerceAtLeast(1), (sh.rect.h * s.dpi * 1.05).toInt().coerceAtLeast(1), Imaging.colourfulness(sh.img) >= 6)
                        GroupItem.Image(sh.rect, e.bytes, e.format, sh.alt)
                    }
                }
            })

            /** The passage on its own slide(s): text if trustworthy, else picture pieces. */
            suspend fun passageSlides(item: Item, style: QuestionStyle) {
                val g = item.passageGroup ?: return
                if (g in passageSlide) return
                val range = project.items.filter { it.passageGroup == g }.mapNotNull { it.number }.let { if (it.isEmpty()) "" else " ${it.min()}–${it.max()}" }
                val title = "Passage for questions$range"
                passageSlide[g] = w.slideCount + 1
                val pc = if (style != QuestionStyle.PICTURE) source.passageContent(item) else null
                if (pc != null && (pc.safe || style == QuestionStyle.TEXT)) {
                    // split paragraph by paragraph over as many slides as needed
                    var rest = pc.blocks
                    while (rest.isNotEmpty()) {
                        var take = rest.size
                        var comp: Composition? = null
                        while (take > 0) {
                            comp = composer.compose(null, pc.copy(blocks = rest.take(take)), full, null, note = title)
                            if (comp != null) break
                            take--
                        }
                        if (comp == null) break // a single paragraph too long even alone: picture below
                        val sp = group(comp, "Passage", title)
                        w.add(sp)
                        records += SlideRecord("passage", item.id, full, true, comp.fontPt, true).also { onSlide(it, sp) }
                        rest = rest.drop(take)
                    }
                    if (rest.isEmpty()) return
                }
                val r = source.passagePicture(item) ?: return
                val one = Geometry.placeIn(full, true, r.img.w, r.img.h, r.img.w / r.ppi, r.glyphPt)
                val pieces = if ((one.displayPt ?: 99.0) < SMALL_TEXT_PT) Imaging.splitAtBlank(r.img, minOf(4, kotlin.math.ceil(SMALL_TEXT_PT / (one.displayPt ?: 12.0)).toInt() + 1)) else listOf(r.img)
                for (pc2 in pieces) {
                    val p = Geometry.placeIn(full, true, pc2.w, pc2.h, pc2.w / r.ppi, r.glyphPt)
                    val sp = pic(pc2, p.rect, title)
                    w.add(sp)
                    records += SlideRecord("passage", item.id, full, false, p.displayPt, true).also { onSlide(it, sp) }
                }
            }

            for ((n, item) in project.items.withIndex()) {
                val style = item.style ?: s.style
                val flags = item.flags.filter { it !in setOf("small_text", "too_small_to_read", "as_picture", "expanded", "passage_separate", "text_shrunk") }.toMutableList()
                val g = item.passageGroup
                var once = g != null && (s.passageMode == PassageMode.ONCE || g in groupOnce)
                if (once) passageSlides(item, style)
                val note = if (once && g != null) passageSlide[g]?.let { "Read the passage on slide $it" } else null

                var comp: Composition? = null
                var why: List<String> = emptyList()
                if (style != QuestionStyle.PICTURE) {
                    val q = source.content(item)
                    val passage = if (g != null && !once) source.passageContent(item) else null
                    val usable = q != null && !q.isEmpty && (q.safe || style == QuestionStyle.TEXT) &&
                        (g == null || once || (passage != null && (passage.safe || style == QuestionStyle.TEXT)))
                    why = (q?.unsafe ?: listOf("no text for this page")) + (passage?.unsafe ?: emptyList())
                    if (usable) {
                        val expand = if (s.expandLong || g != null) tall else null
                        comp = composer.compose(q, passage, box, expand, note, forceExpanded = g != null && !once)
                        if (comp == null && g != null && !once) {
                            // the passage does not fit with the question: show it once, on its own slide
                            groupOnce += g; once = true
                            passageSlides(item, style)
                            val note2 = passageSlide[g]?.let { "Read the passage on slide $it" }
                            comp = composer.compose(q, null, box, expand, note2)
                        }
                        if (comp == null) why = listOf("too long for the slide as text")
                    }
                }
                val slide: SlideSpec
                val record: SlideRecord
                if (comp != null) {
                    slide = group(comp, "Question ${item.label}", altText(item))
                    record = SlideRecord("question", item.id, comp.box, true, comp.fontPt, comp.expanded)
                    if (comp.expanded) flags += "expanded"
                    if ((comp.fontPt ?: 99.0) < s.maxPt - 6) flags += "text_shrunk"
                } else {
                    if (style != QuestionStyle.PICTURE) flags += "as_picture"
                    var r = source.picture(item)
                    val withPassage = g != null && !once
                    if (withPassage) {
                        val pr = source.passagePicture(item)
                        if (pr != null) r = stackRendered(pr, r)
                    }
                    var target = if (withPassage) tall else box
                    var p = Geometry.placeIn(target, layout.centred, r.img.w, r.img.h, r.img.w / r.ppi, r.glyphPt)
                    if (!withPassage && (s.expandLong) && (p.displayPt ?: 99.0) < SMALL_TEXT_PT) {
                        val p2 = Geometry.placeIn(tall, layout.centred, r.img.w, r.img.h, r.img.w / r.ppi, r.glyphPt)
                        if ((p2.displayPt ?: 0.0) > (p.displayPt ?: 0.0) + 0.5) { p = p2; target = tall }
                    }
                    if (target == tall) flags += "expanded"
                    slide = pic(r.img, p.rect, altText(item, note))
                    record = SlideRecord("question", item.id, target, false, p.displayPt, target == tall)
                    flags += Geometry.sizeFlags(p.displayPt)
                }
                if (once && g != null) flags += "passage_separate"
                w.add(slide)
                records += record
                onSlide(record, slide)
                if (project.blankAfter(item)) { w.add(SlideSpec.Blank); records += SlideRecord("blank", item.id, null, false, null, false) }
                items += item.copy(flags = flags.distinct(), displayPt = record.fontPt)
                onItem(n + 1, project.items.size)
            }
            return ExportResult(w.slideCount, project.copy(items = items), records)
        }
    }

    private fun stackRendered(top: Rendered, bottom: Rendered): Rendered {
        val ppi = min(top.ppi, bottom.ppi)
        fun at(r: Rendered) = if (r.ppi > ppi * 1.05) r.img.resize(max(1, (r.img.w * ppi / r.ppi).toInt()), max(1, (r.img.h * ppi / r.ppi).toInt())) else r.img
        val img = Imaging.stack(listOf(at(top), at(bottom)), gap = (0.25 * ppi).toInt())
        return Rendered(img, ppi, bottom.glyphPt ?: top.glyphPt)
    }

    fun altText(it: Item, note: String? = null): String = buildString {
        append("Question ").append(it.label)
        if (note != null) append(". ").append(note)
        if (it.stem.isNotBlank()) append(". ").append(it.stem.take(300))
    }
}
