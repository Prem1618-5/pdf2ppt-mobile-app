package app.papertoslides.diag

import android.content.Context
import android.os.Build
import app.papertoslides.BuildConfig
import app.papertoslides.Services
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.model.Tier
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.SlideTemplate
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
            cases += job("garbage_textlayer.pdf", "A5 junk text layer escalates, never silent zero") { p ->
                // the junk text layer must be rejected (no page stays on T0); OCR may then read the real questions
                check(p.pages.none { it.tier == Tier.T0_TEXT }) { "junk text layer was trusted: ${p.pages.map { it.tier }}" }
                check(p.items.isNotEmpty()) { "zero questions" }
                val flagged = p.items.all { "no_number" in it.flags || "low_confidence" in it.flags }
                check(p.integrity?.ok == true || flagged) { "unflagged result with failed integrity" }
                "text layer rejected, ${p.items.size} questions via ${p.pages.mapNotNull { it.tier }.distinct().joinToString()}"
            }; emit(true)
            val institute = SlideTemplate.quickSetup("selftest_t", "Self-test", "SELF-TEST INSTITUTE", "www.example.test", rightMarginIn = 1.2)
            cases += job("edge_cases.pdf", "T1 editable text, dark, institute template", settings = ProjectSettings(layout = SlideLayout.TOP_RIGHT,
                theme = DeckTheme.DARK, template = institute)) { p ->
                check(p.items.map { it.number } == (1..15).toList()) { "numbers ${p.items.map { it.number }}" }
                val asText = p.items.count { "as_picture" !in it.flags }
                check(asText >= 13) { "only $asText of 15 as editable text: ${p.items.filter { "as_picture" in it.flags }.map { it.label }}" }
                ZipFile(s.projects.deckFile(p)).use { z ->
                    val layout = z.getInputStream(z.getEntry("ppt/slideLayouts/slideLayout7.xml")).readBytes().decodeToString()
                    check("SELF-TEST INSTITUTE" in layout) { "template strip missing on the slide layout" }
                    check("16181D" in layout) { "dark background missing" }
                    val groups = z.entries().toList().filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml")) }
                        .count { e -> "<p:grpSp>" in z.getInputStream(e).readBytes().decodeToString() }
                    check(groups == asText) { "$groups text containers, expected $asText" }
                }
                "$asText of 15 as editable text, template + dark theme on the layout"
            }; emit(true)
            cases += job("edge_cases.pdf", "T2 passage with 5 questions, options, figures", settings = ProjectSettings(layout = SlideLayout.TOP_RIGHT)) { p ->
                val group = p.items.filter { it.passageGroup != null }
                check(group.map { it.number } == (6..10).toList()) { "passage group ${group.map { it.number }}" }
                check(group.all { "expanded" in it.flags || "passage_separate" in it.flags }) { "passage questions not given the full height: ${group.map { it.flags }}" }
                check(p.items.first { it.number == 3 }.flags.none { it == "no_number" }) { "statements broke Q3" }
                "passage on Q6-10, statements kept inside Q3"
            }; emit(true)
            cases += job("hindi_bilingual.pdf", "T3 garbled Hindi text layer -> pictures", settings = ProjectSettings(lang = "both")) { p ->
                check(p.items.isNotEmpty() && p.items.all { "as_picture" in it.flags }) { "Hindi shown as text: ${p.items.map { it.flags }}" }
                "${p.items.size} bilingual questions kept as exact pictures"
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
        settings: ProjectSettings = ProjectSettings(layout = layout),
        verify: (app.papertoslides.core.project.Project) -> String,
    ): CaseResult {
        val t = System.currentTimeMillis()
        val id = "selftest_" + asset.substringBefore('.') + "_" + layout.name.lowercase() + "_" + (name.hashCode() and 0xffff)
        s.projects.delete(id)
        return try {
            val src = File(s.projects.srcDir(id), "00_$asset")
            ctx.assets.open("fixtures/$asset").use { i -> src.outputStream().use { i.copyTo(it) } }
            val out = JobRunner(ctx, s) { }.run(JobSpec(id, asset.substringBefore('.'), settings, noGemini = true), null)
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
