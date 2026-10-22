package app.papertoslides.platform

import android.graphics.Bitmap
import app.papertoslides.core.model.TextLine
import com.google.android.gms.tasks.Task
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.devanagari.DevanagariTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OcrResult(val lines: List<TextLine>, val skewDegrees: Float)

/**
 * Tier T1: ML Kit Text Recognition, bundled models, fully offline. Latin always; Devanagari too when the paper
 * language is Hindi / both. Output has the `text_lines()` shape of the classical parser (size = line height).
 */
class Ocr(hindi: Boolean) : AutoCloseable {
    private val latin: TextRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val deva: TextRecognizer? = if (hindi) TextRecognition.getClient(DevanagariTextRecognizerOptions.Builder().build()) else null

    suspend fun read(bmp: Bitmap): OcrResult {
        val img = InputImage.fromBitmap(bmp, 0)
        // the Devanagari model also reads Latin script, so use it alone for Hindi papers
        val rec = deva ?: latin
        val text = rec.process(img).await()
        val w = bmp.width.toDouble(); val h = bmp.height.toDouble()
        val lines = ArrayList<TextLine>()
        val angles = ArrayList<Float>()
        for (block in text.textBlocks) for (line in block.lines) {
            val r = line.boundingBox ?: continue
            lines += TextLine(r.left / w, r.top / h, r.right / w, r.bottom / h, line.text, r.height().toDouble() / h * 842)
            if (line.text.length >= 12) angles += line.angle
        }
        val skew = if (angles.size >= 5) angles.sorted()[angles.size / 2] else 0f
        return OcrResult(lines.sortedWith(compareBy({ it.y0 }, { it.x0 })), skew)
    }

    override fun close() { latin.close(); deva?.close() }
}

suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { c ->
    addOnSuccessListener { c.resume(it) }
    addOnFailureListener { c.resumeWithException(it) }
    addOnCanceledListener { c.cancel() }
}
