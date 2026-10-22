package app.papertoslides.core

import app.papertoslides.core.assemble.Assembler
import app.papertoslides.core.assemble.IntegrityCheck
import app.papertoslides.core.assemble.PageFacts
import app.papertoslides.core.layout.ClassicalLayout
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.TextLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClassicalLayoutTest {

    private fun parse(pages: List<List<TextLine>>, ocr: Boolean = false): List<PageLayout> {
        val c = ClassicalLayout(ocr)
        c.prime(pages)
        return pages.map { c.analyseTextPage(it) }
    }

    @Test fun `A2 two-column split paper gives exactly 30 questions and drops the answer key`() {
        val pages = Fixtures.lines("digital_2col_split").map { it.lines }
        val layouts = parse(pages)
        assertEquals(PageLayout.KIND_ANSWER_KEY, layouts[2].pageKind)
        val asm = Assembler.assemble(Assembler.fragmentsOf(layouts))
        assertEquals(Fixtures.expectedNumbers("digital_2col_split.pdf"), asm.questions.map { it.number })
        val q27 = asm.questions.first { it.number == 27 }
        assertTrue(q27.flags.contains("split_stitched"))
        assertTrue(asm.questions.none { "www" in it.text || it.text.startsWith("Page") })
        val integ = IntegrityCheck.check(asm.questions, pages.mapIndexed { i, l -> PageFacts(layouts[i], l.isNotEmpty()) })
        assertTrue(integ.reasons.toString(), integ.ok)
    }

    @Test fun `A5 junk text layer is never ok`() {
        val pages = Fixtures.lines("garbage_textlayer").map { it.lines }
        val layouts = parse(pages)
        val asm = Assembler.assemble(Assembler.fragmentsOf(layouts))
        val integ = IntegrityCheck.check(asm.questions, pages.mapIndexed { i, l -> PageFacts(layouts[i], l.isNotEmpty()) })
        assertFalse(integ.ok)
        assertEquals(listOf(0, 1), integ.failingPages)
    }

    @Test fun `anchor regex cases`() {
        val c = ClassicalLayout()
        assertEquals(43, c.anchorNumber("Q.43 Find x"))
        assertEquals(1, c.anchorNumber("(1)".removePrefix("(")))
        assertEquals(12, c.anchorNumber("12. A train"))
        assertEquals(7, c.anchorNumber("Question No. 7 Which"))
        assertNull(c.anchorNumber("(a) 12"))
    }

    @Test fun `OCR tolerant anchor only where the sequence expects it`() {
        val c = ClassicalLayout(ocr = true)
        c.expectedNext = 1
        assertEquals(1, c.anchorNumber("l. What is"))
        c.expectedNext = 10
        assertEquals(10, c.anchorNumber("lO. Which"))
        assertNull(c.anchorNumber("l. stray"))
        assertNull(ClassicalLayout(ocr = false).also { it.expectedNext = 1 }.anchorNumber("l. What"))
    }

    @Test fun `answer key detection`() {
        val key = listOf(TextLine(0.4, 0.05, 0.6, 0.07, "ANSWER KEY")) +
            (1..20).map { TextLine(0.1, 0.1 + it * 0.02, 0.15, 0.11 + it * 0.02, "$it  ${"ABCD"[it % 4]}") }
        assertTrue(ClassicalLayout.looksLikeAnswerKey(key))
        val grid = listOf(TextLine(0.4, 0.05, 0.6, 0.07, "Answers:"), TextLine(0.1, 0.1, 0.9, 0.12, "1-A 2-C 3-B 4-D 5-A 6-B"),
            TextLine(0.1, 0.13, 0.9, 0.15, "7-A 8-C 9-B 10-D 11-A 12-B"))
        assertTrue(ClassicalLayout.looksLikeAnswerKey(grid))
        val questions = Fixtures.lines("digital_1col")[0].lines
        assertFalse(ClassicalLayout.looksLikeAnswerKey(questions))
    }
}
