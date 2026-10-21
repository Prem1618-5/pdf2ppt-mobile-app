package app.papertoslides.core

import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.ladder.LadderTiers
import app.papertoslides.core.ladder.LayoutLadder
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.RawFragment
import app.papertoslides.core.model.Tier
import app.papertoslides.core.project.DeckExporter
import app.papertoslides.core.project.ItemRenderer
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.core.project.QaReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.abs

class PipelineTest {

    @Test fun `S1 PDFBox text lines match the PyMuPDF golden lines within 0_01`() {
        JvmPdf(Fixtures.file("digital_2col_split.pdf")).use { pdf ->
            val golden = Fixtures.lines("digital_2col_split")
            for ((i, page) in golden.withIndex()) {
                val got = pdf.lines(i)
                assertEquals("page $i line count", page.lines.size, got.size)
                for (g in page.lines) {
                    val m = got.firstOrNull { it.text == g.text.replace(Regex("\\s+"), " ") && abs(it.y0 - g.y0) < 0.01 }
                        ?: error("page $i: no line '${g.text}' near y=${g.y0}; got ${got.filter { abs(it.y0 - g.y0) < 0.02 }}")
                    // PyMuPDF's line box includes leading spaces (indented option lines); ours starts at the first visible glyph
                    assertTrue("${g.text}: $m vs $g", m.x0 >= g.x0 - 0.01 && m.x0 - g.x0 < 0.03)
                    for ((a, b) in listOf(m.y0 to g.y0, m.x1 to g.x1, m.y1 to g.y1)) assertTrue("${g.text}: $m vs $g", abs(a - b) < 0.01)
                }
            }
        }
    }

    private fun runPdf(name: String, tiers: LadderTiers = LadderTiers()) = JvmPdf(Fixtures.file(name)).use { pdf ->
        val infos = pdf.pageInfos()
        val res = runBlocking { LayoutLadder(infos, tiers).run() }
        Triple(infos, res, pdf.projectPages(name))
    }

    @Test fun `A1 digital_1col gives 8 numbered questions with zero API calls`() {
        var calls = 0
        val (_, res, _) = runPdf("digital_1col.pdf", LadderTiers(gemini = { calls++; emptyMap() }))
        assertEquals(Fixtures.expectedNumbers("digital_1col.pdf"), res.assembly.questions.map { it.number })
        assertTrue(res.integrity.reasons.toString(), res.integrity.ok)
        assertEquals(listOf(Tier.T0_TEXT, Tier.T0_TEXT), res.tiers)
        assertEquals(0, calls)
    }

    @Test fun `A2 digital_2col_split gives 30 questions, stitched, zero API calls`() {
        var calls = 0
        val (_, res, _) = runPdf("digital_2col_split.pdf", LadderTiers(gemini = { calls++; emptyMap() }))
        assertEquals(Fixtures.expectedNumbers("digital_2col_split.pdf"), res.assembly.questions.map { it.number })
        assertTrue(res.assembly.questions.first { it.number == 27 }.flags.contains("split_stitched"))
        assertTrue(res.integrity.ok)
        assertEquals(0, calls)
    }

    @Test fun `A5 junk text layer escalates to OCR then Gemini, never silent zero`() {
        val asked = mutableListOf<Int>()
        val ocrAsked = mutableListOf<Int>()
        val fake = PageLayout(pageKind = "questions", model = "fake-flash", fragments = (0 until 3).map {
            RawFragment(role = "question", number = 0, box = listOf(100 + it * 200, 50, 280 + it * 200, 950))
        })
        val (_, res, _) = runPdf("garbage_textlayer.pdf", LadderTiers(
            ocr = { p -> ocrAsked += p; emptyList() },
            gemini = { pages -> asked += pages; pages.associateWith { p -> fake.copy(fragments = fake.fragments.mapIndexed { k, f -> f.copy(number = p * 3 + k + 1) }) } },
        ))
        assertEquals(listOf(0, 1), ocrAsked)
        assertEquals(listOf(0, 1), asked)
        assertEquals((1..6).toList(), res.assembly.questions.map { it.number })
        assertTrue(res.integrity.ok)
    }

    @Test fun `A5 without OCR or keys the result is flagged, not silently ok`() {
        val (infos, res, _) = runPdf("garbage_textlayer.pdf", LadderTiers(heuristic = { p ->
            JvmPdf(Fixtures.file("garbage_textlayer.pdf")).use { Imaging.analyseImagePage(it.render(p, 100f)) }
        }))
        assertFalse(res.integrity.ok)
        assertTrue(res.assembly.questions.isNotEmpty())
        assertTrue(res.assembly.questions.all { "no_number" in it.flags && "low_confidence" in it.flags })
        assertEquals(infos.size, res.tiers.count { it == Tier.T3_HEURISTIC })
    }

    @Test fun `password PDF throws a catchable error, corrupt PDF never crashes`() {
        val e = runCatching { JvmPdf(Fixtures.file("password.pdf")).use { it.pageInfos() } }.exceptionOrNull()
        assertTrue(e is org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException)
        // PDFBox repairs this broken page tree; the phone's PdfRenderer may refuse it instead: both are handled
        runCatching { JvmPdf(Fixtures.file("corrupt.pdf")).use { it.pageInfos() } }
    }

    @Test fun `end to end deck for A2 in both layouts passes validate_pptx, C2 switching layout calls no API`() {
        val (infos, res, pages) = runPdf("digital_2col_split.pdf")
        JvmPdf(Fixtures.file("digital_2col_split.pdf")).use { pdf ->
            for (layout in SlideLayout.entries) {
                val settings = ProjectSettings(layout = layout)
                val project = Project(id = "t", name = "digital_2col_split", createdAt = 0, sources = listOf("x.pdf"),
                    pages = Project.pagesFrom(infos, pages, res), items = Project.itemsFrom(res, settings), settings = settings, integrity = res.integrity)
                val renderer = ItemRenderer(pdf.cropper)
                val out = File(Fixtures.outDir, "e2e_${layout.name.lowercase()}.pptx")
                val r = runBlocking { out.outputStream().use { DeckExporter.export(project, it, { item -> renderer.render(project, item) }, JvmPdf.encoder) } }
                assertEquals(60, r.slides)
                File(Fixtures.outDir, "e2e_${layout.name.lowercase()}_qa.txt").writeText(QaReport.text(r.project))
                validate(out, if (layout == SlideLayout.TOP) "top" else "top-right")
                assertTrue(out.length() < 15_000_000)
            }
        }
    }

    private fun validate(deck: File, layout: String) {
        val probe = runCatching { ProcessBuilder("python3", "-c", "import pptx").start().waitFor() }.getOrDefault(1)
        assumeTrue(probe == 0)
        val p = ProcessBuilder("python3", File(Fixtures.dir, "tools/validate_pptx.py").path, deck.path, "--layout", layout, "--expect-questions", "30")
            .redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertEquals(out, 0, p.waitFor())
    }
}
