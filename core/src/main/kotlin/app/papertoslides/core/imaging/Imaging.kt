package app.papertoslides.core.imaging

import app.papertoslides.core.model.Box
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.RawFragment
import app.papertoslides.core.model.Roles
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Port of legacy `imaging.py` (crop, clean, trim, encode decisions) with hand-written kernels instead of OpenCV.
 * Binary opening with a 1-D line kernel is computed exactly from run lengths.
 */
object Imaging {
    const val PAD_Y = 0.018
    const val PAD_X = 0.012

    /** Grow a box by a margin, but never past the midpoint of the gap to a neighbour sharing its rows/columns. */
    fun paddedBox(b: Box, neighbours: List<Box>): Box {
        var loY = b.y0 - PAD_Y; var hiY = b.y1 + PAD_Y; var loX = b.x0 - PAD_X; var hiX = b.x1 + PAD_X
        for (n in neighbours) {
            if (n == b) continue
            val hOverlap = min(b.x1, n.x1) - max(b.x0, n.x0) > 0.2 * min(b.w, n.w)
            val vOverlap = min(b.y1, n.y1) - max(b.y0, n.y0) > 0.2 * min(b.h, n.h)
            if (hOverlap) {
                if (n.y1 <= b.y0 + 0.004) loY = max(loY, (n.y1 + b.y0) / 2)
                if (n.y0 >= b.y1 - 0.004) hiY = min(hiY, (b.y1 + n.y0) / 2)
            }
            if (vOverlap) {
                if (n.x1 <= b.x0 + 0.004) loX = max(loX, (n.x1 + b.x0) / 2)
                if (n.x0 >= b.x1 - 0.004) hiX = min(hiX, (b.x1 + n.x0) / 2)
            }
        }
        return Box(max(0.0, min(loX, b.x0)), max(0.0, min(loY, b.y0)), min(1.0, max(hiX, b.x1)), min(1.0, max(hiY, b.y1)))
    }

    /** Ink = darker than the local mean by [c] (cv2.adaptiveThreshold, THRESH_BINARY_INV, box mean). */
    fun adaptiveInk(g: ByteArray, w: Int, h: Int, block: Int = 35, c: Int = 18): Mask {
        val integ = LongArray((w + 1) * (h + 1))
        for (y in 0 until h) {
            var row = 0L
            for (x in 0 until w) {
                row += g[y * w + x].toInt() and 255
                integ[(y + 1) * (w + 1) + x + 1] = integ[y * (w + 1) + x + 1] + row
            }
        }
        val r = block / 2
        val m = Mask(w, h)
        for (y in 0 until h) {
            val ya = max(0, y - r); val yb = min(h, y + r + 1)
            for (x in 0 until w) {
                val xa = max(0, x - r); val xb = min(w, x + r + 1)
                val sum = integ[yb * (w + 1) + xb] - integ[ya * (w + 1) + xb] - integ[yb * (w + 1) + xa] + integ[ya * (w + 1) + xa]
                val mean = sum.toDouble() / ((yb - ya) * (xb - xa))
                m.on[y * w + x] = (g[y * w + x].toInt() and 255) <= mean - c
            }
        }
        return m
    }

    fun inkMask(img: Raster) = adaptiveInk(img.gray(), img.w, img.h, 35, 18)

    /** Pixels in a horizontal (or vertical) run of at least [len] = binary opening with a line kernel. */
    fun longRuns(m: Mask, len: Int, horizontal: Boolean): Mask {
        val out = Mask(m.w, m.h)
        val outer = if (horizontal) m.h else m.w
        val inner = if (horizontal) m.w else m.h
        for (o in 0 until outer) {
            var i = 0
            while (i < inner) {
                val idx = if (horizontal) o * m.w + i else i * m.w + o
                if (!m.on[idx]) { i++; continue }
                var j = i
                while (j < inner && m.on[if (horizontal) o * m.w + j else j * m.w + o]) j++
                if (j - i >= len) for (k in i until j) out.on[if (horizontal) o * m.w + k else k * m.w + o] = true
                i = j
            }
        }
        return out
    }

    /** Ink without long table rules (they would hide blank bands / margins). */
    fun contentMask(img: Raster): Mask {
        val m = inkMask(img)
        val horiz = longRuns(m, max(10, img.w / 3), true)
        val vert = longRuns(m, max(10, img.h / 4), false)
        for (i in m.on.indices) if (horiz.on[i] || vert.on[i]) m.on[i] = false
        return m
    }

    private fun small(img: Raster, maxSide: Int = 2400): Pair<Raster, Double> {
        val f = max(1.0, max(img.w, img.h).toDouble() / maxSide)
        if (f == 1.0) return img to 1.0
        return img.resize((img.w / f).toInt(), (img.h / f).toInt()) to f
    }

    /**
     * Shrink a crop to its real content: ignore long table rules and drop a narrow sparse strip on the right
     * (marks cells). The left edge is never cut, so the printed question number stays visible.
     */
    fun tighten(full: Raster): Pair<Raster, IntArray> {
        val w0 = full.w; val h0 = full.h
        if (h0 < 20 || w0 < 20) return full to intArrayOf(0, 0, w0, h0)
        val (img, f) = small(full)
        val w = img.w; val h = img.h
        val c = contentMask(img)
        val total = c.count()
        if (total < 50) return full to intArrayOf(0, 0, w0, h0)
        val cols = BooleanArray(w) { c.colHas(it) }
        var right = cols.indexOfLast { it } + 1
        val gapMin = max(12, (0.06 * w).toInt())
        var run = 0; var x = right - 1
        while (x > 0) {
            if (cols[x]) {
                if (run >= gapMin && right - (x + run + 1) < 0.15 * w) {
                    var strip = 0
                    for (xx in x + run until right) strip += c.colCount(xx)
                    if (strip < 0.03 * total) { right = x + 1; break }
                }
                run = 0
            } else run++
            x--
        }
        var x0 = -1; var x1 = -1; var y0 = -1; var y1 = -1
        for (yy in 0 until h) if (c.rowHas(yy, 0, right)) { if (y0 < 0) y0 = yy; y1 = yy }
        for (xx in 0 until right) if (c.colHas(xx)) { if (x0 < 0) x0 = xx; x1 = xx }
        if (x0 < 0 || y0 < 0) return full to intArrayOf(0, 0, w0, h0)
        val mg = 8
        val rect = intArrayOf(max(0, x0 - mg), max(0, y0 - mg), min(w, x1 + 1 + mg), min(h, y1 + 1 + mg))
            .map { (it * f).roundToInt() }.toIntArray()
        rect[2] = min(w0, rect[2]); rect[3] = min(h0, rect[3])
        return full.crop(rect[0], rect[1], rect[2], rect[3]) to rect
    }

    /** Mean channel spread: > 4 means the crop really has colour (coloured figures). */
    fun colourfulness(img: Raster): Double {
        val step = max(1, (img.w * img.h) / 40_000)
        var s = 0L; var n = 0
        var i = 0
        while (i < img.px.size) {
            val c = img.px[i]
            val r = (c shr 16) and 255; val g = (c shr 8) and 255; val b = c and 255
            s += abs(r - g) + abs(g - b); n++
            i += step
        }
        return if (n == 0) 0.0 else s.toDouble() / n
    }

    fun medianGray(img: Raster): Int {
        val hist = IntArray(256)
        val step = max(1, (img.w * img.h) / 40_000)
        var n = 0; var i = 0
        while (i < img.px.size) {
            val c = img.px[i]
            hist[((((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000)]++
            n++; i += step
        }
        var acc = 0
        for (v in 0..255) { acc += hist[v]; if (acc * 2 >= n) return v }
        return 255
    }

    /**
     * Light-theme version (legacy `to_light`). Digital renders: keep as is, pale pixels -> white when watermark
     * removal is on. Photos / scans: divide by a paper estimate, levels, grey ink. Dark frames: inverted to grey.
     */
    fun toLight(src: Raster, removeWatermark: Boolean, photo: Boolean): Raster {
        var img = src.copy()
        val dark = medianGray(img) < 110
        if (!photo && !dark) {
            if (removeWatermark) whitenPale(img, 238)
            return img
        }
        if (dark) {
            for (i in img.px.indices) { val c = img.px[i]; img.px[i] = Raster.grayPx(255 - luma(c)) }
        }
        if (photo || dark) img = normalisePaper(img)
        return img
    }

    private fun luma(c: Int) = (((c shr 16) and 255) * 299 + ((c shr 8) and 255) * 587 + (c and 255) * 114) / 1000

    private fun whitenPale(img: Raster, cut: Int) {
        for (i in img.px.indices) if (luma(img.px[i]) > cut) img.px[i] = Raster.WHITE
    }

    /** Background (paper) estimate = max filter on a downscaled copy, blurred; divide; levels; grey ink. */
    private fun normalisePaper(img: Raster): Raster {
        val f = max(1, max(img.w, img.h) / 300)
        val sw = max(1, img.w / f); val sh = max(1, img.h / f)
        val small = img.resize(sw, sh).gray()
        val bg = IntArray(sw * sh)
        val r = 4
        for (y in 0 until sh) for (x in 0 until sw) {
            var m = 0
            for (yy in max(0, y - r)..min(sh - 1, y + r)) for (xx in max(0, x - r)..min(sw - 1, x + r)) m = max(m, small[yy * sw + xx].toInt() and 255)
            bg[y * sw + x] = m
        }
        val blurred = boxBlur(bg, sw, sh, 3)
        val out = Raster(img.w, img.h)
        for (y in 0 until img.h) {
            val by = min(sh - 1, y / f)
            for (x in 0 until img.w) {
                val p = blurred[by * sw + min(sw - 1, x / f)].coerceAtLeast(1)
                val c = img.px[y * img.w + x]
                fun ch(v: Int) = (((v.toDouble() / p * 255.0) - 25) * 255.0 / (238 - 25)).coerceIn(0.0, 255.0)
                val rr = ch((c shr 16) and 255); val gg = ch((c shr 8) and 255); val bb = ch(c and 255)
                val gray = min(rr, min(gg, bb)) * 0.6 + (rr + gg + bb) / 3 * 0.4
                out.px[y * img.w + x] = Raster.grayPx(gray.roundToInt())
            }
        }
        return out
    }

    private fun boxBlur(a: IntArray, w: Int, h: Int, r: Int): IntArray {
        val tmp = IntArray(a.size); val out = IntArray(a.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (xx in max(0, x - r)..min(w - 1, x + r)) { s += a[y * w + xx]; n++ }
            tmp[y * w + x] = s / n
        }
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0; var n = 0
            for (yy in max(0, y - r)..min(h - 1, y + r)) { s += tmp[yy * w + x]; n++ }
            out[y * w + x] = s / n
        }
        return out
    }

    /** Erase table rules: vertical lines > 55 % of the crop height, horizontal ones > 75 % of its width. */
    fun stripRules(img: Raster): Raster {
        if (img.w < 30 || img.h < 30) return img
        val g = img.gray()
        val m = Mask(img.w, img.h, BooleanArray(g.size) { (g[it].toInt() and 255) < 225 })
        val vert = longRuns(m, (img.h * 0.55).toInt(), false)
        val horiz = longRuns(m, (img.w * 0.75).toInt(), true)
        if (vert.count() + horiz.count() == 0) return img
        val out = img.copy()
        for (y in 0 until img.h) for (x in 0 until img.w) {
            val i = y * img.w + x
            if (vert.on[i] || horiz.on[i]) {
                for (yy in max(0, y - 1)..min(img.h - 1, y + 1)) for (xx in max(0, x - 1)..min(img.w - 1, x + 1)) out.px[yy * img.w + xx] = Raster.WHITE
            }
        }
        return out
    }

    /** Collapse tall blank horizontal bands (between stacked options) so the question can be shown larger. */
    fun compressGaps(img: Raster, ppi: Double, maxGapIn: Double = 0.12): Raster {
        val (sm, f) = small(img)
        val c = contentMask(sm)
        val blankSmall = BooleanArray(sm.h) { !c.rowHas(it) }
        val maxGap = max(4, (maxGapIn * ppi).toInt())
        val keep = ArrayList<Int>(img.h)
        var run = 0
        for (y in 0 until img.h) {
            val b = blankSmall[min(sm.h - 1, (y / f).toInt())]
            run = if (b) run + 1 else 0
            if (run <= maxGap) keep += y
        }
        if (keep.size >= img.h - 2) return img
        val out = Raster(img.w, keep.size)
        keep.forEachIndexed { i, y -> System.arraycopy(img.px, y * img.w, out.px, i * img.w, img.w) }
        return out
    }

    /** Stack fragments vertically; [offsets] (px) keep page-split parts aligned as on the page. */
    fun stack(images: List<Raster>, gap: Int = 14, offsets: List<Int>? = null): Raster {
        val offs = (offsets ?: List(images.size) { 0 }).let { o -> val base = o.min(); o.map { it - base } }
        val w = images.indices.maxOf { images[it].w + offs[it] }
        val h = images.sumOf { it.h } + gap * (images.size - 1)
        val out = Raster(w, h)
        var y = 0
        images.forEachIndexed { i, im ->
            for (yy in 0 until im.h) System.arraycopy(im.px, yy * im.w, out.px, (y + yy) * w + offs[i], im.w)
            y += im.h + gap
        }
        return out
    }

    /** White out rectangles (pixel coordinates l, t, r, b). */
    fun erase(img: Raster, rects: List<IntArray>): Raster {
        if (rects.isEmpty()) return img
        val out = img.copy()
        for (r in rects) for (y in max(0, r[1]) until min(img.h, r[3])) for (x in max(0, r[0]) until min(img.w, r[2])) out.px[y * img.w + x] = Raster.WHITE
        return out
    }

    /** Median glyph height in source points (connected components), used for the displayed font size estimate. */
    fun textHeightPt(full: Raster, pxPerIn: Double): Double? {
        val (img, f) = small(full, 1600)
        val m = inkMask(img)
        val w = img.w; val h = img.h
        val label = IntArray(w * h)
        val heights = ArrayList<Int>()
        val stack = IntArray(w * h)
        var next = 1
        for (start in 0 until w * h) {
            if (!m.on[start] || label[start] != 0) continue
            var sp = 0; stack[sp++] = start; label[start] = next
            var minX = w; var maxX = 0; var minY = h; var maxY = 0; var area = 0
            while (sp > 0) {
                val p = stack[--sp]; val x = p % w; val y = p / w
                area++; minX = min(minX, x); maxX = max(maxX, x); minY = min(minY, y); maxY = max(maxY, y)
                for (dy in -1..1) for (dx in -1..1) {
                    val nx = x + dx; val ny = y + dy
                    if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                    val q = ny * w + nx
                    if (m.on[q] && label[q] == 0) { label[q] = next; stack[sp++] = q }
                }
            }
            next++
            val ch = maxY - minY + 1; val cw = maxX - minX + 1
            if (ch >= 3 && ch <= 0.2 * h && cw <= 3 * ch && area > 6) heights += ch
        }
        if (heights.size < 8) return null
        heights.sort()
        return heights[heights.size / 2] * f / pxPerIn * 72.0
    }

    /** Unsharp mask (photos / scans) so characters stay crisp on a projector. */
    fun sharpen(img: Raster, amount: Double = 1.0): Raster {
        val g = img.gray()
        val blur = boxBlur(IntArray(g.size) { g[it].toInt() and 255 }, img.w, img.h, 1)
        val out = Raster(img.w, img.h)
        for (i in g.indices) {
            val v = g[i].toInt() and 255
            var s = v + amount * (v - blur[i])
            if (s > 232) s = 255.0
            out.px[i] = Raster.grayPx(s.roundToInt().coerceIn(0, 255))
        }
        return out
    }

    /** 16x16 difference hash; Hamming distance <= 12 means duplicate page. */
    fun dhash(img: Raster, size: Int = 16): BooleanArray {
        val s = img.resize(size + 1, size).gray()
        return BooleanArray(size * size) { i ->
            val y = i / size; val x = i % size
            (s[y * (size + 1) + x + 1].toInt() and 255) > (s[y * (size + 1) + x].toInt() and 255)
        }
    }

    fun hamming(a: BooleanArray, b: BooleanArray) = a.indices.count { a[it] != b[it] }

    /** Fraction of ink pixels (page has content?). */
    fun inkRatio(img: Raster): Double {
        val (sm, _) = small(img, 600)
        val m = inkMask(sm)
        return m.count().toDouble() / max(1, sm.w * sm.h)
    }

    /**
     * T3 heuristic (legacy `analyse_image_page`): projection profiles -> columns -> blocks. Without OCR the question
     * boundaries are guesses, so every fragment is unnumbered with confidence 0.25 (always flagged, never "ok").
     */
    fun analyseImagePage(page: Raster): PageLayout {
        val (img, _) = small(page, 1600)
        val g = img.gray()
        val w = img.w; val h = img.h
        val bw = adaptiveInk(g, w, h, 31, 15)
        for (y in 0 until (0.05 * h).toInt()) for (x in 0 until w) bw.on[y * w + x] = false
        for (y in (0.95 * h).toInt() until h) for (x in 0 until w) bw.on[y * w + x] = false
        val occupied = BooleanArray(w) { bw.colCount(it) > 0.01 * h }
        val cols = mutableListOf<IntArray>()
        val gapMin = max(1, (0.015 * w).toInt())
        var start = -1; var run = 0
        for (x in 0 until w + gapMin) {
            val o = x < w && occupied[x]
            if (o) { if (start < 0) start = x; run = 0 }
            else if (start >= 0) {
                run++
                if (run >= gapMin) {
                    if (x - run - start > 0.12 * w) cols += intArrayOf(start, x - run + 1)
                    start = -1; run = 0
                }
            }
        }
        val frags = mutableListOf<RawFragment>()
        for (c in cols.ifEmpty { listOf(intArrayOf(0, w)) }) {
            val rows = BooleanArray(h) { bw.rowCount(it, c[0], c[1]) > 2 }
            val lines = mutableListOf<IntArray>()
            var s = -1
            for (y in 0..h) {
                val o = y < h && rows[y]
                if (o && s < 0) s = y else if (!o && s >= 0) { if (y - s > 3) lines += intArrayOf(s, y); s = -1 }
            }
            if (lines.isEmpty()) continue
            val gaps = (0 until lines.size - 1).map { lines[it + 1][0] - lines[it][1] }
            val thr = if (gaps.isNotEmpty()) gaps.sorted()[gaps.size / 2] * 2.2 + 2 else 1e9
            var block = mutableListOf(lines[0])
            fun emit() {
                val y0 = block.first()[0]; val y1 = block.last()[1]
                frags += RawFragment(
                    role = Roles.QUESTION,
                    box = listOf((y0.toDouble() / h * 1000).toInt(), (c[0].toDouble() / w * 1000).toInt(),
                        ceil(y1.toDouble() / h * 1000).toInt(), ceil(c[1].toDouble() / w * 1000).toInt()),
                    lang = "other", confidence = 0.25,
                )
            }
            for (i in 1 until lines.size) {
                if (gaps[i - 1] > thr) { emit(); block = mutableListOf() }
                block += lines[i]
            }
            emit()
        }
        return PageLayout(
            pageKind = if (frags.isEmpty()) PageLayout.KIND_BLANK else PageLayout.KIND_QUESTIONS,
            darkBackground = g.map { it.toInt() and 255 }.average() < 100,
            fragments = frags.mapIndexed { i, f -> f.copy(order = i) },
            model = "classical-image",
        )
    }
}
