package app.papertoslides.core

import app.papertoslides.core.geometry.Geometry
import app.papertoslides.core.geometry.SLIDE_H
import app.papertoslides.core.geometry.SLIDE_W
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.pptx.PptxWriter
import app.papertoslides.core.pptx.SlideSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipFile
import javax.imageio.ImageIO

class PptxWriterTest {
    private fun png(w: Int, h: Int, label: String): ByteArray {
        val img = BufferedImage(w, h, BufferedImage.TYPE_BYTE_GRAY)
        val g = img.createGraphics()
        g.color = Color.WHITE; g.fillRect(0, 0, w, h)
        g.color = Color.BLACK; g.font = g.font.deriveFont(28f); g.drawString(label, 10, 40)
        g.dispose()
        return ByteArrayOutputStream().also { ImageIO.write(img, "png", it) }.toByteArray()
    }

    /** 30 questions with mixed shapes (wide, tall, tiny) to stress the placement. */
    fun buildDeck(layout: SlideLayout, file: File, blank: Boolean = true): File {
        PptxWriter(file.outputStream(), "Test & <deck>").use { w ->
            for (i in 1..30) {
                val (pw, ph) = when (i % 3) { 0 -> 1800 to 300; 1 -> 600 to 1400; else -> 300 to 60 }
                val p = Geometry.place(layout, pw, ph, srcWidthIn = pw / 250.0, glyphPt = 10.0)
                w.add(SlideSpec.Picture(png(pw, ph, "Q$i"), "png", p.rect, "Question $i"))
                if (blank) w.add(SlideSpec.Blank)
            }
        }
        return file
    }

    private fun validate(deck: File, layout: String, vararg extra: String): Pair<Int, String> {
        val tool = File(Fixtures.dir, "tools/validate_pptx.py")
        val probe = runCatching { ProcessBuilder("python3", "-c", "import pptx").start().waitFor() }.getOrDefault(1)
        assumeTrue("python-pptx not installed", probe == 0)
        val p = ProcessBuilder(listOf("python3", tool.path, deck.path, "--layout", layout) + extra).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        return p.waitFor() to out
    }

    @Test fun `C1 TOP deck passes validate_pptx`() {
        val deck = buildDeck(SlideLayout.TOP, File(Fixtures.outDir, "deck_top.pptx"))
        val (code, out) = validate(deck, "top", "--expect-questions", "30")
        assertEquals(out, 0, code)
    }

    @Test fun `C1 TOP_RIGHT deck passes validate_pptx`() {
        val deck = buildDeck(SlideLayout.TOP_RIGHT, File(Fixtures.outDir, "deck_top_right.pptx"))
        val (code, out) = validate(deck, "top-right", "--expect-questions", "30")
        assertEquals(out, 0, code)
    }

    @Test fun `C5 deck without blank slides`() {
        val deck = buildDeck(SlideLayout.TOP, File(Fixtures.outDir, "deck_noblank.pptx"), blank = false)
        val (code, out) = validate(deck, "top", "--no-blank", "--expect-questions", "30")
        assertEquals(out, 0, code)
        ZipFile(deck).use { z -> assertEquals(30, z.entries().toList().count { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }) }
    }

    @Test fun `no resource folder starts with an underscore (Android drops them from the APK)`() {
        val root = File(PptxWriter::class.java.getResource("/pptx/static-parts.txt")!!.toURI()).parentFile
        val bad = root.walkTopDown().filter { it.name.startsWith("_") }.toList()
        assertTrue("$bad", bad.isEmpty())
    }

    @Test fun `alt text and title are escaped`() {
        val f = File(Fixtures.outDir, "esc.pptx")
        PptxWriter(f.outputStream(), "A & B").use { it.add(SlideSpec.Picture(png(10, 10, ""), "png", SlideLayout.TOP.box(), "Q <1> & \"2\"")) }
        ZipFile(f).use { z ->
            val xml = z.getInputStream(z.getEntry("ppt/slides/slide1.xml")).readBytes().decodeToString()
            assertTrue(xml.contains("Q &lt;1&gt; &amp; &quot;2&quot;"))
        }
    }

    @Test fun `geometry never crosses the centre lines and caps short questions`() {
        for (layout in SlideLayout.entries) for ((w, h) in listOf(4000 to 100, 100 to 4000, 50 to 20, 1000 to 1000)) {
            val r = Geometry.place(layout, w, h).rect
            assertTrue(r.y + r.h <= SLIDE_H / 2 + 1e-9)
            assertTrue(r.x >= 0 && r.x + r.w <= SLIDE_W)
            if (layout == SlideLayout.TOP_RIGHT) assertTrue(r.x >= SLIDE_W / 2)
        }
        val tiny = Geometry.place(SlideLayout.TOP, 300, 40, srcWidthIn = 1.2, glyphPt = 10.0)
        assertEquals(36.0, tiny.displayPt!!, 0.01)                         // C4
        val tall = Geometry.place(SlideLayout.TOP, 1000, 6000, srcWidthIn = 7.0, glyphPt = 10.0)
        assertTrue(Geometry.sizeFlags(tall.displayPt).contains("too_small_to_read")) // C3: flagged, not clipped
        assertTrue(tall.rect.h <= SlideLayout.TOP.box().h + 1e-9)
    }

    @Test fun `target dpi depends on the layout`() {
        val parts = listOf(7.0 to 1.0)
        val top = Geometry.targetDpi(SlideLayout.TOP, parts)
        val tr = Geometry.targetDpi(SlideLayout.TOP_RIGHT, parts)
        assertTrue(top > tr)
        assertEquals(250, Geometry.targetDpi(SlideLayout.TOP_RIGHT, listOf(20.0 to 20.0)))
        assertTrue(Geometry.targetDpi(SlideLayout.TOP, listOf(0.5 to 0.1)) <= 600)
    }
}
