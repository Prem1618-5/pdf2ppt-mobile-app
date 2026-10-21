package app.papertoslides.core

import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.ladder.PageInfo
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.pdf.Glyph
import app.papertoslides.core.pdf.LineBuilder
import app.papertoslides.core.project.Encoded
import app.papertoslides.core.project.Encoder
import app.papertoslides.core.project.PageCropper
import app.papertoslides.core.project.ProjectPage
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.max

/**
 * JVM stand-in for the phone: desktop PDFBox 2.0.27 (same API as pdfbox-android 2.0.27) for text and pixels.
 * The glyph adapter mirrors app/.../pdf/PdfTextExtractor.kt.
 */
class JvmPdf(val file: File) : AutoCloseable {
    val doc: PDDocument = PDDocument.load(file)
    private val renderer = PDFRenderer(doc)

    fun pageSize(i: Int): Pair<Double, Double> {
        val p = doc.getPage(i); val box = p.cropBox
        return if (p.rotation % 180 == 0) box.width.toDouble() to box.height.toDouble() else box.height.toDouble() to box.width.toDouble()
    }

    fun glyphs(i: Int): List<Glyph> {
        val out = mutableListOf<Glyph>()
        val s = object : PDFTextStripper() {
            override fun writeString(text: String?, positions: MutableList<TextPosition>) {
                for (t in positions) {
                    val size = t.fontSizeInPt.toDouble().takeIf { it > 0 } ?: t.heightDir.toDouble()
                    val d = t.font?.fontDescriptor
                    val asc = (d?.ascent?.takeIf { it > 0 } ?: 900f) / 1000.0
                    val desc = (-(d?.descent ?: -200f)).coerceAtLeast(0f) / 1000.0
                    val base = t.yDirAdj.toDouble()
                    out += Glyph(t.xDirAdj.toDouble(), (t.xDirAdj + t.widthDirAdj).toDouble(), base - asc * size, base + desc * size, base, size, t.unicode ?: "")
                }
            }
        }
        s.startPage = i + 1; s.endPage = i + 1
        s.getText(doc)
        return out
    }

    fun lines(i: Int) = pageSize(i).let { (w, h) -> LineBuilder.build(glyphs(i), w, h) }

    fun render(i: Int, dpi: Float): Raster = toRaster(renderer.renderImageWithDPI(i, dpi, ImageType.RGB))

    fun pageInfos(): List<PageInfo> = (0 until doc.numberOfPages).map { i -> PageInfo(i, PageKind.PDF_DIGITAL, lines(i), hasInk = true) }
        .map { if (it.textLines.isEmpty()) it.copy(kind = PageKind.PDF_SCAN) else it }

    fun projectPages(name: String) = (0 until doc.numberOfPages).map { i ->
        val (w, h) = pageSize(i)
        ProjectPage(i, "$name#p${i + 1}", if (lines(i).isEmpty()) PageKind.PDF_SCAN else PageKind.PDF_DIGITAL, 0, i, w / 72.0, h / 72.0, nativePpi = 200.0)
    }

    val cropper = PageCropper { page, box: Box, ppi ->
        val full = render(page.index, ppi.coerceAtMost(400.0).toFloat())
        full.crop((box.x0 * full.w).toInt(), (box.y0 * full.h).toInt(), (box.x1 * full.w).toInt(), (box.y1 * full.h).toInt())
    }

    override fun close() = doc.close()

    companion object {
        fun toRaster(img: BufferedImage): Raster {
            val px = IntArray(img.width * img.height)
            img.getRGB(0, 0, img.width, img.height, px, 0, img.width)
            for (k in px.indices) px[k] = px[k] or (0xFF shl 24)
            return Raster(img.width, img.height, px)
        }

        val encoder = Encoder { img, maxW, maxH, colour ->
            val s = minOf(1.0, maxW.toDouble() / img.w, maxH.toDouble() / img.h)
            val r = if (s < 1) img.resize(max(1, (img.w * s).toInt()), max(1, (img.h * s).toInt())) else img
            val bi = BufferedImage(r.w, r.h, if (colour) BufferedImage.TYPE_INT_RGB else BufferedImage.TYPE_BYTE_GRAY)
            if (colour) bi.setRGB(0, 0, r.w, r.h, r.px, 0, r.w)
            else { val g = r.gray(); bi.raster.setDataElements(0, 0, r.w, r.h, g) }
            val o = ByteArrayOutputStream(); ImageIO.write(bi, "png", o)
            Encoded(o.toByteArray(), "png", r.w, r.h)
        }
    }
}
