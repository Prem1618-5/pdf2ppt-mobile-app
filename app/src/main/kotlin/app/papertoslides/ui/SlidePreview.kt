package app.papertoslides.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import app.papertoslides.core.geometry.SLIDE_H
import app.papertoslides.core.geometry.SLIDE_W
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.ElementKind
import app.papertoslides.core.template.SlideTemplate
import app.papertoslides.core.template.ThemeColours
import app.papertoslides.job.PreviewSlide
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

fun hexColor(h: String?): Color = runCatching { Color(android.graphics.Color.parseColor(h)) }.getOrDefault(Color.Transparent)

/** Images used by a preview: template logos and figure pictures, decoded off the main thread. */
@Composable
fun rememberImages(files: List<File>): Map<String, ImageBitmap> {
    val key = files.joinToString { it.path + it.lastModified() }
    val m by produceState(emptyMap<String, ImageBitmap>(), key) {
        value = withContext(Dispatchers.IO) {
            files.filter { it.exists() }.mapNotNull { f ->
                val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(f.path, o)
                var s = 1; while (maxOf(o.outWidth, o.outHeight) / (s * 2) >= 1200) s *= 2
                BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s })?.let { f.name to it.asImageBitmap() }
            }.toMap()
        }
    }
    return m
}

/**
 * A to-scale slide as the deck will show it: theme background, the template's strips / texts / logos, and either
 * the saved composition of a question ([slide]) or, without one, the shaded question box of [layout].
 */
@Composable
fun SlidePreview(
    template: SlideTemplate,
    theme: DeckTheme,
    modifier: Modifier = Modifier,
    layout: SlideLayout? = null,
    slide: PreviewSlide? = null,
    images: Map<String, ImageBitmap> = emptyMap(),
    showArea: Boolean = false,
    description: String,
) {
    val measurer = rememberTextMeasurer()
    val colours = ThemeColours.of(theme, template)
    Canvas(modifier.fillMaxWidth().aspectRatio((SLIDE_W / SLIDE_H).toFloat()).semantics { contentDescription = description }) {
        val k = size.width / SLIDE_W.toFloat()
        drawRect(hexColor(colours.bg))
        drawRect(Color.Gray.copy(alpha = 0.5f), style = Stroke(1.5f))
        for (e in template.elements) {
            val o = Offset((e.rect.x * k).toFloat(), (e.rect.y * k).toFloat()); val sz = Size((e.rect.w * k).toFloat(), (e.rect.h * k).toFloat())
            if (e.kind == ElementKind.IMAGE) {
                images[e.image ?: ""]?.let { drawImageFit(it, o, sz) } ?: drawRect(Color.Gray.copy(alpha = 0.3f), o, sz)
                continue
            }
            e.fill?.let { drawRect(hexColor(it), o, sz) }
            if (e.text.isNotBlank()) {
                val style = TextStyle(color = hexColor(e.textColor), fontSize = ptToSp(e.fontPt, k), fontWeight = if (e.bold) FontWeight.Bold else FontWeight.Normal,
                    textAlign = when (e.align) { "left" -> TextAlign.Left; "right" -> TextAlign.Right; else -> TextAlign.Center })
                val pad = (0.1 * k).toInt()
                val r = measurer.measure(AnnotatedString(e.text), style, constraints = Constraints(maxWidth = (sz.width.toInt() - 2 * pad).coerceAtLeast(1)))
                drawText(r, topLeft = Offset(o.x + pad, o.y + (sz.height - r.size.height) / 2))
            }
        }
        val area = template.questionArea
        if (showArea) {
            drawRect(Color(0xFF2754C5).copy(alpha = 0.12f), Offset((area.x * k).toFloat(), (area.y * k).toFloat()), Size((area.w * k).toFloat(), (area.h * k).toFloat()))
        }
        if (slide != null) {
            for (it in slide.items) {
                val o = Offset((it.x * k).toFloat(), (it.y * k).toFloat())
                if (it.image != null) { images[it.image]?.let { img -> drawImageFit(img, o, Size((it.w * k).toFloat(), (it.h * k).toFloat())) }; continue }
                var y = o.y
                for (p in it.paras) {
                    y += (p.spaceBefore / 72 * k).toFloat()
                    val text = buildAnnotatedString {
                        for (r in p.runs) {
                            pushStyle(SpanStyle(color = hexColor(r.color), fontWeight = if (r.bold) FontWeight.Bold else null, fontStyle = if (r.italic) FontStyle.Italic else null,
                                baselineShift = when (r.rise) { 1 -> BaselineShift.Superscript; -1 -> BaselineShift.Subscript; else -> null },
                                fontSize = if (r.rise != 0) ptToSp(p.size * 0.65, k) else TextUnit.Unspecified))
                            append(r.text.replace("\t", "    "))
                            pop()
                        }
                    }
                    val left = ((p.marL + minOf(0.0, p.indent)) * k).toFloat()
                    val res = measurer.measure(text, TextStyle(fontSize = ptToSp(p.size, k)), constraints = Constraints(maxWidth = ((it.w - p.marL - minOf(0.0, p.indent)) * k).toInt().coerceAtLeast(1)))
                    drawText(res, topLeft = Offset(o.x + left, y))
                    y += res.size.height
                }
            }
        } else if (layout != null) {
            val b = layout.box(area)
            val shade = Color(0xFF2754C5)
            drawRect(shade.copy(alpha = 0.22f), Offset((b.x * k).toFloat(), (b.y * k).toFloat()), Size((b.w * k).toFloat(), (b.h * k).toFloat()))
            for (i in 0 until 4) {
                val ly = (b.y + 0.3 + i * 0.5) * k
                drawRect(hexColor(colours.text).copy(alpha = 0.55f), Offset(((b.x + 0.2) * k).toFloat(), ly.toFloat()), Size((b.w * (if (i == 0) 0.8 else 0.45) * k).toFloat(), (0.17 * k).toFloat()))
            }
        }
    }
}

/** pt on the slide -> sp on screen, for a canvas of [k] px per inch. */
private fun DrawScope.ptToSp(pt: Double, k: Float): TextUnit = (pt / 72.0 * k / density / fontScale).toFloat().sp

private fun DrawScope.drawImageFit(img: ImageBitmap, o: Offset, sz: Size) {
    val s = minOf(sz.width / img.width, sz.height / img.height)
    val w = (img.width * s).toInt().coerceAtLeast(1); val h = (img.height * s).toInt().coerceAtLeast(1)
    drawImage(img, srcOffset = IntOffset.Zero, srcSize = IntSize(img.width, img.height), dstOffset = IntOffset(o.x.toInt(), o.y.toInt()), dstSize = IntSize(w, h))
}
