package app.papertoslides

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.papertoslides.diag.SelfTest
import app.papertoslides.diag.SelfTestReport
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Runs Settings -> Self-test on a (CI) emulator: the real pipeline (PdfRenderer, PdfBox-Android, ML Kit, imaging,
 * PPTX writer, Keystore) on the bundled fixtures. OCR quality cases (A3, A4) are measured and logged; per the build
 * plan a shortfall there is recorded in KNOWN_ISSUES, not a red build.
 */
@RunWith(AndroidJUnit4::class)
class SelfTestOnDevice {
    @Test fun selfTest() = runBlocking {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        var last: SelfTestReport? = null
        SelfTest(ctx, Services.get(ctx)).run { last = it }
        val r = last!!
        r.text().lines().forEach { Log.i("SelfTest", it) }
        val gated = r.cases.filter { !it.name.startsWith("A3") && !it.name.startsWith("A4") }
        assertTrue(r.text(), gated.all { it.pass })
    }
}
