package app.papertoslides.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import app.papertoslides.core.geometry.Geometry
import app.papertoslides.core.geometry.SLIDE_H
import app.papertoslides.core.geometry.SLIDE_W
import app.papertoslides.core.geometry.SlideLayout

/**
 * A to-scale 16:9 slide. Without [picture] it shades the question area of the layout (options cards);
 * with a picture it places it exactly as the deck will (review preview).
 */
@Composable
fun SlideDrawing(layout: SlideLayout, modifier: Modifier = Modifier, picture: ImageBitmap? = null, description: String) {
    val outline = MaterialTheme.colorScheme.outline
    val shade = MaterialTheme.colorScheme.primary
    Canvas(modifier.fillMaxWidth().aspectRatio((SLIDE_W / SLIDE_H).toFloat()).semantics { contentDescription = description }) {
        val k = size.width / SLIDE_W.toFloat()
        drawRect(androidx.compose.ui.graphics.Color.White)
        drawRect(outline, style = Stroke(width = 2f))
        drawLine(outline.copy(alpha = 0.35f), Offset(0f, size.height / 2), Offset(size.width, size.height / 2), 1.5f)
        if (layout == SlideLayout.TOP_RIGHT) drawLine(outline.copy(alpha = 0.35f), Offset(size.width / 2, 0f), Offset(size.width / 2, size.height), 1.5f)
        if (picture == null) {
            val b = layout.box()
            drawRect(shade.copy(alpha = 0.22f), Offset((b.x * k).toFloat(), (b.y * k).toFloat()), Size((b.w * k).toFloat(), (b.h * k).toFloat()))
            for (i in 0 until 4) { // a few "text lines" so the card reads as a question
                val y = (b.y + 0.35 + i * 0.55) * k
                val w = (b.w * (if (i == 0) 0.8 else 0.45)) * k
                drawRect(shade.copy(alpha = 0.6f), Offset(((b.x + 0.25) * k).toFloat(), y.toFloat()), Size(w.toFloat(), (0.2 * k).toFloat()))
            }
        } else {
            val p = Geometry.place(layout, picture.width, picture.height).rect
            drawImage(picture, srcOffset = IntOffset.Zero, srcSize = IntSize(picture.width, picture.height),
                dstOffset = IntOffset((p.x * k).toInt(), (p.y * k).toInt()), dstSize = IntSize((p.w * k).toInt().coerceAtLeast(1), (p.h * k).toInt().coerceAtLeast(1)))
        }
    }
}

val FLAG_LABELS = mapOf(
    "low_confidence" to "Check", "no_number" to "No number", "small_text" to "Small", "too_small_to_read" to "Too small",
    "split_stitched" to "Joined", "page_break_stitched" to "Across pages", "shared_passage" to "Passage", "refers_to_passage" to "Uses passage",
)
val WARN_FLAGS = setOf("low_confidence", "no_number", "too_small_to_read", "small_text")
