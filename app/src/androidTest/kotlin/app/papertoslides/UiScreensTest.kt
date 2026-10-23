package app.papertoslides

import android.app.Application
import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.geometry.Offset
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.core.template.DeckTheme
import app.papertoslides.core.template.SlideTemplate
import app.papertoslides.job.JobRunner
import app.papertoslides.job.JobSpec
import app.papertoslides.ui.MainViewModel
import app.papertoslides.ui.PaperTheme
import app.papertoslides.ui.ReviewScreen
import app.papertoslides.ui.TemplateEditorScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** Drives the new screens on the emulator and saves screenshots (CI uploads them for a human look). */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class UiScreensTest {
    @get:Rule val rule = createComposeRule()
    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    // app-private folder: CI copies it out with `adb exec-out run-as` (shared storage is closed to adb on Android 11+)
    private val shots = File(ctx.filesDir, "screens").apply { mkdirs() }

    private fun SemanticsNodeInteraction.save(name: String) {
        val bmp = captureToImage().asAndroidBitmap()
        File(shots, "$name.png").outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test fun templateDesigner() {
        val vm = MainViewModel(ctx.applicationContext as Application)
        var done = false
        rule.setContent { PaperTheme { TemplateEditorScreen(vm, null, onDone = { done = true }) } }
        rule.onNodeWithText("Institute name (top strip)").performTextInput("SHARMA CLASSES")
        rule.onNodeWithText("Bottom strip (address, phone…)").performTextInput("www.sharma.example · 98765 43210")
        rule.onRoot().save("01_quick_setup")
        rule.onNodeWithText("Apply").performClick()
        rule.waitForIdle()
        rule.onRoot().save("02_designer")
        // drag the question area a little to the right and down
        rule.onAllNodesWithContentDescription("Question area. Drag to move.").onFirst().performTouchInput {
            swipe(center, center + Offset(60f, 30f), durationMillis = 400)
        }
        rule.waitForIdle()
        rule.onRoot().save("03_dragged")
        rule.onNodeWithText("Save").performClick()
        rule.waitUntil(5000) { done }
        rule.waitUntil(5000) { vm.templates.value.any { it.elements.any { e -> e.text == "SHARMA CLASSES" } } }
        val t = vm.templates.value.first { it.elements.any { e -> e.text == "SHARMA CLASSES" } }
        assertTrue("question area below the strip: ${t.questionArea}", t.questionArea.y >= 0.6)
    }

    @Test fun reviewScreenShowsRealSlides() {
        val s = Services.get(ctx)
        val id = "uitest_review"
        s.projects.delete(id)
        File(s.projects.srcDir(id), "00_edge_cases.pdf").outputStream().use { o -> ctx.assets.open("fixtures/edge_cases.pdf").use { it.copyTo(o) } }
        val tpl = SlideTemplate.quickSetup("uit", "UI test", "SHARMA CLASSES", "www.sharma.example", rightMarginIn = 1.0)
        runBlocking { JobRunner(ctx, s) { }.run(JobSpec(id, "Edge cases", ProjectSettings(layout = SlideLayout.TOP_RIGHT, theme = DeckTheme.DARK, template = tpl), noGemini = true), null) }
        val vm = MainViewModel(ctx.applicationContext as Application)
        rule.setContent { PaperTheme { ReviewScreen(vm, id, onBack = {}, onTemplates = {}) } }
        rule.waitUntil(15000) { rule.onAllNodes(hasScrollAction()).fetchSemanticsNodes().isNotEmpty() }
        rule.waitForIdle()
        rule.onRoot().save("04_review_top")
        // the first card sits below the summary: scroll the grid to it
        rule.onAllNodes(hasScrollAction()).onFirst().performScrollToNode(hasContentDescription("Slide of question 1.", substring = true))
        Thread.sleep(1500) // previews decode off the main thread
        rule.waitForIdle()
        rule.onRoot().save("04_review")
        rule.onAllNodes(hasContentDescription("Slide of question 1.", substring = true)).onFirst().performClick()
        rule.waitForIdle(); Thread.sleep(1000); rule.waitForIdle()
        rule.onRoot().save("05_preview_dialog")
        assertTrue(rule.onAllNodes(hasContentDescription("Slide preview of question 1.", substring = true), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        s.projects.delete(id)
    }
}
