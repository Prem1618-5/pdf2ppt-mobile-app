package app.papertoslides.core.imaging

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * ARGB pixels (same layout as Android `Bitmap.getPixels`). Pure Kotlin so imaging is unit-tested on a JVM;
 * the app converts Bitmap <-> Raster at the edges.
 */
class Raster(val w: Int, val h: Int, val px: IntArray = IntArray(w * h) { WHITE }) {
    init { require(px.size == w * h) }

    fun gray(): ByteArray {
        val g = ByteArray(w * h)
        for (i in px.indices) {
            val c = px[i]
            g[i] = ((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000).toByte()
        }
        return g
    }

    fun crop(l: Int, t: Int, r: Int, b: Int): Raster {
        val x0 = l.coerceIn(0, w); val y0 = t.coerceIn(0, h); val x1 = r.coerceIn(x0, w); val y1 = b.coerceIn(y0, h)
        val out = Raster(max(1, x1 - x0), max(1, y1 - y0))
        if (x1 > x0 && y1 > y0) for (y in y0 until y1) System.arraycopy(px, y * w + x0, out.px, (y - y0) * out.w, x1 - x0)
        return out
    }

    /** Area-averaging downscale / bilinear upscale. */
    fun resize(nw: Int, nh: Int): Raster {
        val tw = max(1, nw); val th = max(1, nh)
        if (tw == w && th == h) return this
        val out = Raster(tw, th)
        if (tw <= w && th <= h) {
            val sx = w.toDouble() / tw; val sy = h.toDouble() / th
            for (y in 0 until th) {
                val ya = (y * sy).toInt(); val yb = max(ya + 1, min(h, ((y + 1) * sy).toInt()))
                for (x in 0 until tw) {
                    val xa = (x * sx).toInt(); val xb = max(xa + 1, min(w, ((x + 1) * sx).toInt()))
                    var r = 0; var g = 0; var b = 0; var n = 0
                    for (yy in ya until yb) { val row = yy * w; for (xx in xa until xb) { val c = px[row + xx]; r += (c shr 16) and 255; g += (c shr 8) and 255; b += c and 255; n++ } }
                    out.px[y * tw + x] = rgb(r / n, g / n, b / n)
                }
            }
        } else {
            for (y in 0 until th) {
                val fy = ((y + 0.5) * h / th - 0.5).coerceIn(0.0, h - 1.0); val y0 = fy.toInt(); val y1 = min(h - 1, y0 + 1); val dy = fy - y0
                for (x in 0 until tw) {
                    val fx = ((x + 0.5) * w / tw - 0.5).coerceIn(0.0, w - 1.0); val x0 = fx.toInt(); val x1 = min(w - 1, x0 + 1); val dx = fx - x0
                    out.px[y * tw + x] = lerp4(px[y0 * w + x0], px[y0 * w + x1], px[y1 * w + x0], px[y1 * w + x1], dx, dy)
                }
            }
        }
        return out
    }

    fun copy() = Raster(w, h, px.copyOf())

    companion object {
        const val WHITE = -1 // 0xFFFFFFFF
        fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
        fun grayPx(v: Int) = rgb(v, v, v)

        fun fromGray(w: Int, h: Int, g: ByteArray) = Raster(w, h, IntArray(w * h) { grayPx(g[it].toInt() and 255) })

        private fun lerp4(a: Int, b: Int, c: Int, d: Int, dx: Double, dy: Double): Int {
            fun ch(s: Int): Int {
                val top = ((a shr s) and 255) * (1 - dx) + ((b shr s) and 255) * dx
                val bot = ((c shr s) and 255) * (1 - dx) + ((d shr s) and 255) * dx
                return (top * (1 - dy) + bot * dy).roundToInt()
            }
            return rgb(ch(16), ch(8), ch(0))
        }
    }
}

/** Boolean mask, row-major. */
class Mask(val w: Int, val h: Int, val on: BooleanArray = BooleanArray(w * h)) {
    operator fun get(x: Int, y: Int) = on[y * w + x]
    fun count() = on.count { it }
    fun rowHas(y: Int, x0: Int = 0, x1: Int = w): Boolean { val r = y * w; for (x in x0 until x1) if (on[r + x]) return true; return false }
    fun colHas(x: Int, y0: Int = 0, y1: Int = h): Boolean { for (y in y0 until y1) if (on[y * w + x]) return true; return false }
    fun rowCount(y: Int, x0: Int = 0, x1: Int = w): Int { var n = 0; val r = y * w; for (x in x0 until x1) if (on[r + x]) n++; return n }
    fun colCount(x: Int, y0: Int = 0, y1: Int = h): Int { var n = 0; for (y in y0 until y1) if (on[y * w + x]) n++; return n }
}
