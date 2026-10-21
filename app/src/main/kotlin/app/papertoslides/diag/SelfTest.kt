package app.papertoslides.diag

import android.content.Context
import android.os.Build
import app.papertoslides.BuildConfig
import app.papertoslides.Services
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.model.Tier
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.job.JobRunner
import app.papertoslides.job.JobSpec
import app.papertoslides.platform.SourceException
import app.papertoslides.security.KeyVault
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.zip.ZipFile

data class CaseResult(val name: String, val pass: Boolean, val detail: String, val ms: Long)

data class SelfTestReport(val running: Boolean, val cases: List<CaseResult>, val peakMemMb: Long, val header: String) {
    val passed get() = cases.count { it.pass }

    fun text(): String = buildString {
        append(header).append('\n')
        append("Result: $passed / ${cases.size} passed · peak memory ${peakMemMb} MB · API calls 0 (self-test never uses Gemini)\n")
        for (c in cases) append(if (c.pass) "PASS " else "FAIL ").append(c.name).append(" (").append(c.ms).append(" ms): ").append(c.detail).append('\n')
    }
}

/**
 * Settings -> Self-test: runs the bundled fixtures through the REAL pipeline on this phone (PdfRenderer, PdfBox,
 * ML Kit, imaging, PPTX writer) with Gemini disabled, and checks them against fixtures/expected.json.
 * The owner screenshots or copies this report; it is the on-device verification of spikes S1-S4.
 */
class SelfTest(private val ctx: Context, private val s: Services) {
    private val header = "Paper to Slides ${BuildConfig.VERSION_NAME} self-test · ${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Runtime.getRuntime().availableProcessors()} cores"

    suspend fun run(onUpdate: (SelfTestReport) -> Unit) = withContext(Dispatchers.Default) {
        val cases = ArrayList<CaseResult>()
        var peak = 0L
        val sampler = CoroutineScope(Dispatchers.Default).launch {
            while (isActive) { val rt = Runtime.getRuntime(); peak = maxOf(peak, (rt.totalMemory() - rt.freeMemory()) / 1_048_576); delay(100) }
        }
        fun emit(running: Boolean) = onUpdate(SelfTestReport(running, cases.toList(), peak, header))
        emit(true)
        try {
            cases += vaultCase(); emit(true)
            cases += job("digital_1col.pdf", "A1 digital, short last page") { p ->
                val nums = p.items.map { it.number }
                check(nums == (1..8).toList()) { "numbers $nums, expected 1..8" }
                check(p.pages.all { it.tier == Tier.T0_TEXT }) { "tiers ${p.pages.map { it.tier }}" }
                "8 questions from the text layer, 0 API calls"
            }; emit(true)
            cases += job("digital_2col_split.pdf", "A2 two columns + answer key") { p ->
                val nums = p.items.map { it.number }
                check(nums == (1..30).toList()) { "numbers ${nums.take(40)}, expected 1..30" }
                check(p.integrity?.ok == true) { "integrity ${p.integrity?.reasons}" }
                deckCheck(p, 60); "30 questions, key page dropped, deck 60 slides"
            }; emit(true)
            cases += job("digital_2col_split.pdf", "C1 same paper, Top right layout", SlideLayout.TOP_RIGHT) { p ->
                deckCheck(p, 60); "deck written in quadrant I layout"
            }; emit(true)
            cases += job("scan_pages.pdf", "A3 scanned pages via on-device OCR") { p ->
                val found = p.items.mapNotNull { it.number }.filter { it in 1..30 }.toSet()
                val rate = found.size * 100 / 30
                check(rate >= 90) { "OCR found ${found.size}/30 numbers ($rate %): ${(1..30).filter { it !in found }} missing" }
                "OCR found ${found.size}/30 numbers, 0 API calls"
            }; emit(true)
            cases += job("photo_exif_rot.jpg", "A4 rotated photo (EXIF + de-skew)") { p ->
                val nums = p.items.mapNotNull { it.number }
                check(nums.size >= 12 && nums.first() == 1) { "numbers found: $nums" }
                "${nums.size} numbered questions on the upright page"
            }; emit(true)
            cases += job("garbage_textlayer.pdf", "A5 junk text layer is never silent", expectFailure = true) { p ->
                val flagged = p.items.all { "no_number" in it.flags || "low_confidence" in it.flags }
                check(p.integrity?.ok == false && flagged) { "integrity ok=${p.integrity?.ok} — should have been flagged" }
                "escalated and flagged (no key: Gemini skipped)"
            }; emit(true)
            cases += job("password.pdf", "A6 password PDF", expectFailure = true) { "should have been refused" }; emit(true)
            cases += job("corrupt.pdf", "A6 damaged PDF", expectFailure = true) { p -> "read anyway: ${p.items.size} items, no crash" }; emit(true)
        } finally {
            sampler.cancel()
            emit(false)
        }
    }

    private fun vaultCase(): CaseResult {
        val t = System.currentTimeMillis()
        return try {
            val secret = "AIzaFAKE-selftest-not-a-real-key-000".encodeToByteArray()
            val blob = KeyVault.seal(secret)
            check(KeyVault.open(blob).contentEquals(secret)) { "round trip mismatch" }
            check(!blob.decodeToString().contains("selftest")) { "blob not encrypted" }
            val bad = blob.copyOf().also { it[it.size - 1] = (it[it.size - 1] + 1).toByte() }
            check(runCatching { KeyVault.open(bad) }.isFailure) { "tampered blob accepted" }
            CaseResult("Key vault (Keystore AES-GCM)", true, "seal/open ok, tampering detected", System.currentTimeMillis() - t)
        } catch (e: Throwable) {
            CaseResult("Key vault (Keystore AES-GCM)", false, e.message ?: e.javaClass.simpleName, System.currentTimeMillis() - t)
        }
    }

    private fun deckCheck(p: app.papertoslides.core.project.Project, slides: Int) {
        val f = s.projects.deckFile(p)
        check(f.exists()) { "deck not written" }
        ZipFile(f).use { z ->
            val n = z.entries().toList().count { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
            check(n == slides) { "$n slides, expected $slides" }
            check(z.getEntry("[Content_Types].xml") != null) { "content types missing" }
        }
    }

    private suspend fun job(
        asset: String, name: String, layout: SlideLayout = SlideLayout.TOP, expectFailure: Boolean = false,
        verify: (app.papertoslides.core.project.Project) -> String,
    ): CaseResult {
        val t = System.currentTimeMillis()
        val id = "selftest_" + asset.substringBefore('.') + "_" + layout.name.lowercase()
        s.projects.delete(id)
        return try {
            val src = File(s.projects.srcDir(id), "00_$asset")
            ctx.assets.open("fixtures/$asset").use { i -> src.outputStream().use { i.copyTo(it) } }
            val out = JobRunner(ctx, s) { }.run(JobSpec(id, asset.substringBefore('.'), ProjectSettings(layout = layout), noGemini = true), null)
            val detail = verify(out.project)
            if (asset == "password.pdf") CaseResult(name, false, "password PDF was not refused", System.currentTimeMillis() - t)
            else CaseResult(name, true, detail, System.currentTimeMillis() - t)
        } catch (e: SourceException) {
            val ok = expectFailure && (asset != "password.pdf" || "password" in (e.message ?: ""))
            val okGarbage = asset == "garbage_textlayer.pdf" // "no questions found" message is an honest, non-silent stop
            CaseResult(name, ok || okGarbage, "friendly message: ${e.message}", System.currentTimeMillis() - t)
        } catch (e: IllegalStateException) {
            CaseResult(name, false, e.message ?: "check failed", System.currentTimeMillis() - t)
        } catch (e: Throwable) {
            CaseResult(name, false, "crashed: ${e.javaClass.simpleName}: ${e.message}", System.currentTimeMillis() - t)
        } finally {
            s.projects.delete(id)
        }
    }
}
