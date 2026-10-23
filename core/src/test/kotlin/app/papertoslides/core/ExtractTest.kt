package app.papertoslides.core

import app.papertoslides.core.compose.Block
import app.papertoslides.core.compose.Extractor
import app.papertoslides.core.compose.ParaKind
import app.papertoslides.core.compose.PartInput
import app.papertoslides.core.compose.QuestionContent
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.ladder.LadderTiers
import app.papertoslides.core.ladder.LayoutLadder
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.ProjectSettings
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Edge cases end to end on the JVM: real PDF -> ladder -> items -> crops + text lines -> structured content. */
class ExtractTest {
    companion object {
        val pdfFile = File(Fixtures.dir, "../testdata/edge_cases.pdf")
        val expected = Json.parseToJsonElement(File(Fixtures.dir, "../testdata/edge_expected.json").readText()).jsonObject["edge_cases.pdf"]!!.jsonObject

        /** number -> content (question part only; passage separately). */
        fun contents(): Map<Int, Pair<QuestionContent, QuestionContent?>> = JvmPdf(pdfFile).use { pdf ->
            val infos = pdf.pageInfos()
            val res = runBlocking { LayoutLadder(infos, LadderTiers()).run() }
            val pages = Project.pagesFrom(infos, pdf.projectPages("edge"), res)
            val items = Project.itemsFrom(res, ProjectSettings())
            items.associate { item ->
                fun inputs(parts: List<app.papertoslides.core.project.Part>) = parts.map { part ->
                    val meta = pages[part.page]
                    val img = Imaging.toLight(runBlocking { pdf.cropper.crop(meta, part.box, 200.0) }, true, photo = false)
                    PartInput(img, 200.0, part.box, pdf.lines(part.page), meta.wIn, meta.hIn)
                }
                val q = Extractor.extract(inputs(item.parts), item.number)
                val p = if (item.passageParts.isNotEmpty()) Extractor.extract(inputs(item.passageParts), null, passage = true) else null
                item.number!! to (q to p)
            }
        }
    }

    @Test fun `every edge case question has the right structure`() {
        val got = contents()
        assertEquals((1..15).toList(), got.keys.sorted())
        val qs = expected["questions"]!!.jsonObject
        val problems = mutableListOf<String>()
        for ((n, spec) in qs) {
            val (c, passage) = got[n.toInt()]!!
            val s = spec.jsonObject
            val opts = s["options"]
            when (opts) {
                is JsonArray -> { val want = opts.map { it.jsonPrimitive.content }; val have = c.options.map { it.text.chars }; if (want != have) problems += "Q$n options $have != $want" }
                is JsonPrimitive -> if (c.options.size != opts.intOrNull) problems += "Q$n ${c.options.size} options, want ${opts.intOrNull}"
                else -> {}
            }
            val figs = c.blocks.count { it is Block.Figure } + (if (c.optionsFigure != null) 1 else 0)
            s["figure"]?.jsonPrimitive?.booleanOrNull?.let { if (it != (figs > 0)) problems += "Q$n figure=$figs" }
            s["options_as_picture"]?.let { if (c.optionsFigure == null) problems += "Q$n options should be a picture" }
            s["statements"]?.jsonPrimitive?.intOrNull?.let { want -> val have = c.blocks.count { it is Block.Para && it.kind == ParaKind.STATEMENT }; if (have != want) problems += "Q$n statements $have != $want" }
            s["passage"]?.let { if (passage == null || passage.blocks.none { it is Block.Para && it.kind == ParaKind.PASSAGE }) problems += "Q$n passage missing" }
            if (!c.safe) problems += "Q$n unsafe: ${c.unsafe}"
            if (c.label != "$n.") problems += "Q$n label '${c.label}'"
            println("Q$n label=${c.label} blocks=${c.blocks.map { if (it is Block.Para) "${it.kind}:${it.text.chars.take(50)}" else "FIG(${(it as Block.Figure).what})" }} opts=${c.options.map { it.marker + it.text.chars }} optFig=${c.optionsFigure != null}")
        }
        assertTrue(problems.joinToString("\n"), problems.isEmpty())
    }

    @Test fun `superscripts survive into the content`() {
        val c = contents()[1]!!.first
        val stem = c.blocks.filterIsInstance<Block.Para>().first().text
        assertEquals("If x2 + y2 = 25 and x = 3, find the positive value of y.", stem.chars)
        assertEquals(2, stem.rise.count { it == 1 })
    }
}
