package app.papertoslides.platform

import android.graphics.Bitmap
import app.papertoslides.core.imaging.Raster
import app.papertoslides.core.project.Encoded
import app.papertoslides.core.project.Encoder
import java.io.ByteArrayOutputStream
import kotlin.math.max

object Bitmaps {
    fun toRaster(b: Bitmap): Raster {
        val px = IntArray(b.width * b.height)
        b.getPixels(px, 0, b.width, 0, 0, b.width, b.height)
        return Raster(b.width, b.height, px)
    }

    fun toBitmap(r: Raster): Bitmap = Bitmap.createBitmap(r.px, r.w, r.h, Bitmap.Config.ARGB_8888)

    fun jpeg(b: Bitmap, quality: Int): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()

    fun png(b: Bitmap): ByteArray =
        ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    /** Scale so the long side is at most [longSide]. */
    fun fit(b: Bitmap, longSide: Int): Bitmap {
        val s = longSide.toFloat() / max(b.width, b.height)
        if (s >= 1f) return b
        return Bitmap.createScaledBitmap(b, max(1, (b.width * s).toInt()), max(1, (b.height * s).toInt()), true)
    }

    /**
     * Legacy `encode`: text / line art -> lossless PNG (sharp glyph edges); colour photos -> JPEG q92 only when the
     * PNG would be more than twice as big.
     */
    val encoder = Encoder { img, maxW, maxH, colour ->
        val s = minOf(1.0, maxW.toDouble() / img.w, maxH.toDouble() / img.h)
        var bmp = toBitmap(img)
        if (s < 1.0) bmp = Bitmap.createScaledBitmap(bmp, max(1, (img.w * s).toInt()), max(1, (img.h * s).toInt()), true)
        val png = png(bmp)
        if (!colour) return@Encoder Encoded(png, "png", bmp.width, bmp.height)
        val jpg = jpeg(bmp, 92)
        if (png.size <= jpg.size * 2) Encoded(png, "png", bmp.width, bmp.height) else Encoded(jpg, "jpeg", bmp.width, bmp.height)
    }
}
