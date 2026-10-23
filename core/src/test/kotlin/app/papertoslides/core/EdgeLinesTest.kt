package app.papertoslides.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EdgeLinesTest {
    @Test fun `superscripts and subscripts stay on their line as runs`() {
        JvmPdf(File(Fixtures.dir, "../testdata/edge_cases.pdf")).use { pdf ->
            val l1 = pdf.lines(0).first { it.text.startsWith("1.") }
            assertEquals("1. If x2 + y2 = 25 and x = 3, find the positive value of y.", l1.text)
            assertEquals(listOf("2", "2"), l1.runs.filter { it.rise == 1 }.map { it.text })
            val l13 = pdf.lines(2).first { it.text.startsWith("13.") }
            assertEquals(listOf("2", "2"), l13.runs.filter { it.rise == -1 }.map { it.text })
            assertTrue(l13.text.contains("H2O"))
        }
    }
}
