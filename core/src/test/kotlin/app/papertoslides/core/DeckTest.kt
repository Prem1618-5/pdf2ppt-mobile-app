package app.papertoslides.core

import app.papertoslides.core.geometry.RectIn
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.ladder.LadderTiers
import app.papertoslides.core.ladder.LayoutLadder
import app.papertoslides.core.project.DeckExporter
import app.papertoslides.core.project.ExportResult
import app.papertoslides.core.project.PageItemSource
import app.papertoslides.core.project.PassageMode
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.core.project.QuestionStyle
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.SlideTemplate
import app.papertoslides.core.template.ThemeColours
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Templates, light/dark, text vs picture, passages: decks from the edge-case paper, validated (and rendered). */
class DeckTest {
    private val institute = SlideTemplate.quickSetup("t1", "Institute", "SHARMA CLASSES · Physics · Chemistry · Maths", "www.sharmaclasses.example · 98765 43210", rightMarginIn = 1.2)

    private fun build(name: String, file: String, settings: ProjectSettings): Pair<File, ExportResult> = JvmPdf(File(Fixtures.dir, "../testdata/$file")).use { pdf ->
        val infos = pdf.pageInfos()
        val res = runBlocking { LayoutLadder(infos, LadderTiers(), settings.lang).run() }
        val project = Project(id = name, name = name, createdAt = 0, sources = listOf(file), pages = Project.pagesFrom(infos, pdf.projectPages(file), res),
            items = Project.itemsFrom(res, settings), settings = settings, integrity = res.integrity)
        val out = File(Fixtures.outDir, "$name.pptx")
        val r = runBlocking { out.outputStream().use { DeckExporter.export(project, it, PageItemSource(project, pdf.cropper, { p -> pdf.lines(p) }), JvmPdf.encoder) } }
        // manifest for scripts/validate_deck.py
        fun box(b: RectIn?) = if (b == null) "null" else "[${b.x},${b.y},${b.w},${b.h}]"
        val t = settings.template
        val json = buildString {
            append("{\"min_pt\":${settings.minPt},\"bg\":\"${ThemeColours.of(settings.theme, t).bg}\",")
            append("\"template_texts\":[${t.elements.filter { it.text.isNotBlank() }.joinToString(",") { "\"" + it.text.replace("\"", "\\\"") + "\"" }}],\"slides\":[")
            append(r.records.joinToString(",") { rec ->
                "{\"kind\":\"${rec.kind}\",\"text\":${rec.isText},\"box\":${box(rec.box)},\"template_boxes\":[${t.elements.joinToString(",") { box(it.rect) }}]}"
            })
            append("]}")
        }
        File(Fixtures.outDir, "$name.manifest.json").writeText(json)
        out to r
    }

    private fun validate(deck: File, render: Boolean = true) {
        val probe = runCatching { ProcessBuilder("python3", "-c", "import pptx, pymupdf").start().waitFor() }.getOrDefault(1)
        assumeTrue(probe == 0)
        val haveSoffice = runCatching { ProcessBuilder("soffice", "--version").start().waitFor() == 0 }.getOrDefault(false)
        val args = mutableListOf("python3", File(Fixtures.dir, "../scripts/validate_deck.py").path, deck.path, File(deck.path.removeSuffix(".pptx") + ".manifest.json").path)
        if (render && haveSoffice) args += "--render"
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        println(out)
        assertEquals(out, 0, p.waitFor())
    }

    @Test fun `top layout, light, editable text, plain template`() {
        val (deck, r) = build("edge_top_text_light", "edge_cases.pdf", ProjectSettings(layout = SlideLayout.TOP))
        val q = r.records.filter { it.kind == "question" }
        assertEquals(15, q.size)
        println(r.project.items.map { "${it.label}:${it.flags}" })
        assertTrue("most questions as text: ${q.count { it.isText }}", q.count { it.isText } >= 13)
        validate(deck)
    }

    @Test fun `top right, dark theme, institute template, passage repeated`() {
        val (deck, r) = build("edge_tr_dark_institute", "edge_cases.pdf", ProjectSettings(layout = SlideLayout.TOP_RIGHT, theme = DeckTheme.DARK, template = institute))
        val passageQs = r.project.items.filter { it.passageGroup != null }
        assertEquals((6..10).toList(), passageQs.map { it.number })
        // repeated passage: every dependent question uses the full height (quadrants I + IV)
        val recs = r.records.filter { it.kind == "question" && it.itemId in passageQs.map { q -> q.id } }
        assertTrue(recs.all { it.expanded })
        validate(deck)
    }

    @Test fun `passage once on its own slide`() {
        val (deck, r) = build("edge_tr_once", "edge_cases.pdf", ProjectSettings(layout = SlideLayout.TOP_RIGHT, passageMode = PassageMode.ONCE, template = institute))
        assertEquals(1, r.records.count { it.kind == "passage" })
        assertTrue(r.project.items.filter { it.passageGroup != null }.all { "passage_separate" in it.flags })
        validate(deck)
    }

    @Test fun `picture style, dark, institute template`() {
        val (deck, r) = build("edge_pic_dark", "edge_cases.pdf", ProjectSettings(layout = SlideLayout.TOP, style = QuestionStyle.PICTURE, theme = DeckTheme.DARK, template = institute))
        assertTrue(r.records.filter { it.kind == "question" }.none { it.isText })
        validate(deck, render = false)
    }

    @Test fun `Hindi paper in English only - twins dropped, English shown as text`() {
        val (deck, r) = build("hindi_en", "hindi_bilingual.pdf", ProjectSettings(layout = SlideLayout.TOP, lang = "en"))
        assertEquals(listOf(1, 2, 3), r.project.items.map { it.number })
        assertTrue(r.records.filter { it.kind == "question" }.all { it.isText })
        validate(deck)
    }

    @Test fun `garbled Hindi text layer is never shown as text`() {
        val (deck, r) = build("hindi_both", "hindi_bilingual.pdf", ProjectSettings(layout = SlideLayout.TOP, lang = "both"))
        val q = r.records.filter { it.kind == "question" }
        println(r.project.items.map { "${it.label}:${it.flags}" })
        assertTrue(q.isNotEmpty())
        assertTrue(r.project.items.all { "as_picture" in it.flags })
        validate(deck, render = false)
    }
}
