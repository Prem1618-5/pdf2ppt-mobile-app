package app.papertoslides.platform

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.exifinterface.media.ExifInterface
import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.model.Box
import app.papertoslides.core.model.TextLine
import app.papertoslides.core.pdf.Glyph
import app.papertoslides.core.pdf.LineBuilder
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.tom_roush.pdfbox.text.TextPosition
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A friendly, teacher-readable failure for one input file (A6). */
class SourceException(message: String) : Exception(message)

/** One input file: a PDF (many pages) or an image (one page). */
interface SourceFile : AutoCloseable {
    val name: String
    val pageCount: Int
    /** Page size in inches (PDF: from the page box; images: assume A4 width). */
    fun sizeIn(page: Int): Pair<Double, Double>
    /** Text layer lines (empty for images / scans). */
    suspend fun textLines(page: Int): List<TextLine>
    /** Whole page with long side = [longSide] px. */
    suspend fun renderFull(page: Int, longSide: Int): Bitmap
    /** Region of the page at [pxPerIn]; images return native pixels when [pxPerIn] is huge. Clip-rendered, never the whole page at high dpi. */
    suspend fun crop(page: Int, box: Box, pxPerIn: Double): Raster
    /** Pixels per inch of the embedded scan, if known. */
    fun nativePpi(page: Int): Double? = null
    val isImage: Boolean
}

object Sources {
    private const val MAX_IMAGE_SIDE = 3200
    private const val MAX_CLIP = 4096
    private val PDF_MAGIC = "%PDF".toByteArray()

    fun init(ctx: Context) = PDFBoxResourceLoader.init(ctx.applicationContext)

    fun open(file: File, workDir: File): SourceFile {
        if (file.length() == 0L) throw SourceException("${file.name} is empty")
        val head = file.inputStream().use { s -> ByteArray(8).also { s.read(it) } }
        val isPdf = head.take(4).toByteArray().contentEquals(PDF_MAGIC) || file.name.lowercase().endsWith(".pdf")
        return if (isPdf) PdfSource(file) else ImageSource.open(file, workDir)
    }

    // ---------------------------------------------------------------- PDF
    private class PdfSource(private val file: File) : SourceFile {
        override val name: String = file.name
        override val isImage = false
        private val fd: ParcelFileDescriptor
        private val renderer: PdfRenderer
        private val lock = Mutex()   // PdfRenderer: one open page at a time, not thread-safe
        private var textDoc: PDDocument? = null
        private val sizes = HashMap<Int, Pair<Double, Double>>()

        init {
            val head = file.inputStream().use { s -> ByteArray(5).also { s.read(it) } }
            if (!head.take(4).toByteArray().contentEquals(PDF_MAGIC)) throw SourceException("Could not read ${file.name}: it is not really a PDF")
            fd = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
            renderer = try {
                PdfRenderer(fd)
            } catch (e: SecurityException) {
                fd.close(); throw SourceException("${file.name} needs a password. Remove the password and try again.")
            } catch (e: Exception) {
                fd.close(); throw SourceException("Could not read ${file.name}. The file may be damaged.")
            }
            if (renderer.pageCount == 0) { close(); throw SourceException("Could not read ${file.name}: it has no pages") }
            try {
                for (i in 0 until renderer.pageCount) renderer.openPage(i).use { sizes[i] = it.width / 72.0 to it.height / 72.0 }
            } catch (e: Exception) {
                close(); throw SourceException("Could not read ${file.name}. The file may be damaged.")
            }
            textDoc = try {
                PDDocument.load(file)
            } catch (e: InvalidPasswordException) {
                close(); throw SourceException("${file.name} needs a password. Remove the password and try again.")
            } catch (e: Throwable) {
                null // pixels still work; the ladder falls back to OCR
            }
        }

        override val pageCount get() = renderer.pageCount

        override fun sizeIn(page: Int): Pair<Double, Double> = sizes.getValue(page)

        override suspend fun textLines(page: Int): List<TextLine> = lock.withLock {
            val doc = textDoc ?: return emptyList()
            runCatching {
                val p = doc.getPage(page)
                val box = p.cropBox
                val (w, h) = if (p.rotation % 180 == 0) box.width.toDouble() to box.height.toDouble() else box.height.toDouble() to box.width.toDouble()
                LineBuilder.build(glyphs(doc, page), w, h)
            }.getOrDefault(emptyList())
        }

        /** Mirrors core/src/test/.../JvmPlatform.kt (desktop PDFBox), which is checked against the golden lines. */
        private fun glyphs(doc: PDDocument, page: Int): List<Glyph> {
            val out = ArrayList<Glyph>()
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
            s.startPage = page + 1; s.endPage = page + 1
            s.getText(doc)
            return out
        }

        override suspend fun renderFull(page: Int, longSide: Int): Bitmap = lock.withLock {
            renderer.openPage(page).use { p ->
                val z = longSide.toFloat() / max(p.width, p.height)
                val bmp = white(max(1, (p.width * z).toInt()), max(1, (p.height * z).toInt()))
                p.render(bmp, null, Matrix().apply { setScale(z, z) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                bmp
            }
        }

        override suspend fun crop(page: Int, box: Box, pxPerIn: Double): Raster = lock.withLock {
            renderer.openPage(page).use { p ->
                var z = (pxPerIn / 72.0).toFloat()
                val cw = box.w * p.width; val ch = box.h * p.height
                z = min(z, (MAX_CLIP / max(cw, ch)).toFloat()) // cap a clip render at ~4096 px
                val bw = max(1, (cw * z).roundToInt()); val bh = max(1, (ch * z).roundToInt())
                val bmp = white(bw, bh)
                val m = Matrix().apply { setScale(z, z); postTranslate((-box.x0 * p.width * z).toFloat(), (-box.y0 * p.height * z).toFloat()) }
                p.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                Bitmaps.toRaster(bmp).also { bmp.recycle() }
            }
        }

        override fun nativePpi(page: Int): Double? = null

        override fun close() {
            runCatching { renderer.close() }
            runCatching { fd.close() }
            runCatching { textDoc?.close() }
        }
    }

    private fun white(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

    // ---------------------------------------------------------------- images
    /** Photo / screenshot: EXIF-transposed, downscaled to <= 3200 px, stored as a normalised JPEG, cropped by region. */
    class ImageSource private constructor(override val name: String, private val normalised: File, private val w: Int, private val h: Int) : SourceFile {
        override val isImage = true
        override val pageCount = 1
        private val lock = Mutex()

        override fun sizeIn(page: Int): Pair<Double, Double> {
            val wIn = if (w < h) 8.3 else 11.7 // unknown physical size: assume an A4 print, as legacy
            return wIn to wIn * h / w
        }

        override suspend fun textLines(page: Int) = emptyList<TextLine>()

        override suspend fun renderFull(page: Int, longSide: Int): Bitmap = lock.withLock {
            val o = BitmapFactory.Options().apply { inSampleSize = sample(max(w, h), longSide) }
            Bitmaps.fit(BitmapFactory.decodeFile(normalised.path, o) ?: throw SourceException("Could not read $name"), longSide)
        }

        override suspend fun crop(page: Int, box: Box, pxPerIn: Double): Raster = lock.withLock {
            val r = Rect((box.x0 * w).toInt(), (box.y0 * h).toInt(), (box.x1 * w).toInt().coerceAtMost(w), (box.y1 * h).toInt().coerceAtMost(h))
            val dec = if (Build.VERSION.SDK_INT >= 31) BitmapRegionDecoder.newInstance(normalised.path)
            else @Suppress("DEPRECATION") BitmapRegionDecoder.newInstance(normalised.path, false)
            try {
                var bmp = dec!!.decodeRegion(r, BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 })
                val wantW = (box.w * sizeIn(0).first * pxPerIn).toInt()
                if (wantW > 0 && bmp.width > wantW * 1.05) bmp = Bitmap.createScaledBitmap(bmp, wantW, max(1, bmp.height * wantW / bmp.width), true)
                Bitmaps.toRaster(bmp)
            } finally { dec?.recycle() }
        }

        override fun close() {}

        /** Rotate the stored page (de-skew from OCR line angles). */
        fun deskew(degrees: Float): ImageSource {
            val src = BitmapFactory.decodeFile(normalised.path) ?: return this
            val m = Matrix().apply { setRotate(-degrees) }
            val rot = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
            val out = white(rot.width, rot.height)
            Canvas(out).drawBitmap(rot, 0f, 0f, null)
            FileOutputStream(normalised).use { out.compress(Bitmap.CompressFormat.JPEG, 95, it) }
            return ImageSource(name, normalised, out.width, out.height)
        }

        companion object {
            fun open(file: File, workDir: File): ImageSource {
                val norm = File(workDir, file.nameWithoutExtension + ".norm.jpg")
                if (norm.exists()) { // already normalised (and maybe de-skewed) by an earlier pass
                    val b = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(norm.path, b)
                    if (b.outWidth > 0) return ImageSource(file.name, norm, b.outWidth, b.outHeight)
                }
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                if (bounds.outWidth <= 0) {
                    val ext = file.extension.lowercase()
                    throw SourceException(if (ext in setOf("tif", "tiff")) "TIFF images are not supported on Android. Save it as PDF, JPG or PNG."
                    else "Could not read ${file.name}. Use a PDF, JPG, PNG, WebP or HEIC file.")
                }
                val o = BitmapFactory.Options().apply { inSampleSize = sample(max(bounds.outWidth, bounds.outHeight), MAX_IMAGE_SIDE) }
                var bmp = BitmapFactory.decodeFile(file.path, o) ?: throw SourceException("Could not read ${file.name}")
                bmp = Bitmaps.fit(bmp, MAX_IMAGE_SIDE)
                val rot = runCatching { ExifInterface(file.path).rotationDegrees }.getOrDefault(0)
                val flip = runCatching { ExifInterface(file.path).isFlipped }.getOrDefault(false)
                if (rot != 0 || flip) { // EXIF orientation first, always
                    val m = Matrix().apply { if (flip) postScale(-1f, 1f); postRotate(rot.toFloat()) }
                    bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                }
                val flat = white(bmp.width, bmp.height).also { Canvas(it).drawBitmap(bmp, 0f, 0f, null) } // transparent PNGs -> white paper
                FileOutputStream(norm).use { flat.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                return ImageSource(file.name, norm, flat.width, flat.height)
            }

            private fun sample(side: Int, target: Int): Int { var s = 1; while (side / (s * 2) >= target) s *= 2; return s }
        }
    }
}
