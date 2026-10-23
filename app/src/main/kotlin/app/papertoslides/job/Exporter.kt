package app.papertoslides.job

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import app.papertoslides.core.gemini.GeminiProtocol
import app.papertoslides.core.pptx.GroupItem
import app.papertoslides.core.pptx.SlideSpec
import app.papertoslides.core.project.DeckExporter
import app.papertoslides.core.project.Item
import app.papertoslides.core.project.ItemSource
import app.papertoslides.core.project.PageCropper
import app.papertoslides.core.project.PageItemSource
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.QaReport
import app.papertoslides.core.project.Rendered
import app.papertoslides.core.project.SlideRecord
import app.papertoslides.data.ProjectStore
import app.papertoslides.platform.Bitmaps
import app.papertoslides.platform.SourceFile
import app.papertoslides.platform.Sources
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException

@Serializable
private data class RenderMeta(val ppi: Double, val glyphPt: Double?)

/** What the review screen draws for one slide: the real composition, as written into the deck. */
@Serializable
data class PreviewRun(val text: String, val color: String, val bold: Boolean = false, val italic: Boolean = false, val rise: Int = 0)

@Serializable
data class PreviewPara(val runs: List<PreviewRun>, val size: Double, val marL: Double = 0.0, val indent: Double = 0.0, val tabs: List<Double> = emptyList(), val spaceBefore: Double = 0.0)

@Serializable
data class PreviewItem(val x: Double, val y: Double, val w: Double, val h: Double, val paras: List<PreviewPara> = emptyList(), val image: String? = null)

@Serializable
data class PreviewSlide(val kind: String, val isText: Boolean, val fontPt: Double?, val items: List<PreviewItem>)

/**
 * Project -> .pptx + QA report. Question pictures are cached per (parts, layout, dpi, watermark); text and
 * figures are rebuilt from the stored page lines. Switching layout, theme, template or text/picture never calls
 * the API. The deck is written to a temp file and renamed: no half-written deck after a cancel or a full disk.
 */
class Exporter(private val ctx: Context, private val store: ProjectStore, private val templates: app.papertoslides.data.TemplateStore) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun renderKey(p: Project, it: Item): String = GeminiProtocol.sha256(
        (json.encodeToString(it.parts) + json.encodeToString(it.twinParts) + p.settings.layout + p.settings.dpi + p.settings.removeWatermark +
            p.settings.template.questionArea + "r2").encodeToByteArray(),
    ).take(16)

    fun cachedPng(p: Project, it: Item) = File(store.renderDir(p.id), "${it.id}_${renderKey(p, it)}.png")

    fun previewFile(p: Project, itemId: String) = File(store.renderDir(p.id), "preview_$itemId.json")

    fun preview(p: Project, itemId: String): PreviewSlide? =
        runCatching { json.decodeFromString<PreviewSlide>(previewFile(p, itemId).readText()) }.getOrNull()

    fun previewImage(p: Project, name: String) = File(store.renderDir(p.id), name)

    /** Text lines of a page saved by the job (PDF text layer or OCR). */
    fun pageLines(projectId: String, page: Int): List<app.papertoslides.core.model.TextLine>? =
        runCatching { json.decodeFromString<List<app.papertoslides.core.model.TextLine>>(File(store.workDir(projectId), "lines_%04d.json".format(page)).readText()) }.getOrNull()

    suspend fun export(project: Project, governor: Governor?, onItem: (Int, Int) -> Unit): Project {
        val sources = HashMap<Int, SourceFile>()
        val work = store.workDir(project.id)
        val files = store.srcDir(project.id).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val cropper = PageCropper { page, box, ppi ->
            governor?.gate()
            val src = sources.getOrPut(page.file) { Sources.open(files[page.file], work) }
            src.crop(page.index, box, ppi)
        }
        val base = PageItemSource(project, cropper, { pg -> pageLines(project.id, pg) })
        val source = object : ItemSource by base {
            override suspend fun picture(item: Item): Rendered = cachedPicture(project, item) { base.picture(item) }
        }
        val deck = store.deckFile(project)
        val tmp = File(deck.path + ".part")
        val renders = store.renderDir(project.id)
        renders.listFiles { f -> f.name.startsWith("preview_") }?.forEach { it.delete() }
        try {
            val result = tmp.outputStream().use { out ->
                DeckExporter.export(project, out, source, Bitmaps.encoder, templates.images(project.settings.template), onItem) { rec, slide ->
                    rec.itemId?.let { savePreview(project, if (rec.kind == "passage") it + "_passage" else it, rec, slide) }
                }
            }
            if (!tmp.renameTo(deck)) { deck.delete(); if (!tmp.renameTo(deck)) throw IOException("could not save the deck") }
            val saved = store.save(result.project.copy(rev = project.rev))
            store.qaFile(saved).writeText(QaReport.text(saved) + "\n" + QaReport.csv(saved))
            return saved
        } catch (e: IOException) {
            tmp.delete()
            throw IOException(if (ctx.cacheDir.usableSpace < 20_000_000) "The phone is out of storage. Free some space and try again." else (e.message ?: "Could not save the deck"))
        } finally {
            tmp.delete()
            sources.values.forEach { runCatching { it.close() } }
        }
    }

    private suspend fun cachedPicture(p: Project, it: Item, make: suspend () -> Rendered): Rendered {
        val png = cachedPng(p, it)
        val meta = File(png.path + ".json")
        if (png.exists() && meta.exists()) {
            val m = runCatching { json.decodeFromString<RenderMeta>(meta.readText()) }.getOrNull()
            val b = BitmapFactory.decodeFile(png.path)
            if (m != null && b != null) return Rendered(Bitmaps.toRaster(b), m.ppi, m.glyphPt).also { b.recycle() }
        }
        val r = make()
        val b = Bitmaps.toBitmap(r.img)
        png.writeBytes(Bitmaps.png(b)); b.recycle()
        meta.writeText(json.encodeToString(RenderMeta(r.ppi, r.glyphPt)))
        return r
    }

    private fun savePreview(p: Project, key: String, rec: SlideRecord, slide: SlideSpec) {
        val dir = store.renderDir(p.id)
        var k = 0
        fun img(bytes: ByteArray, fmt: String): String {
            val name = "preview_${key}_${k++}.${if (fmt == "png") "png" else "jpg"}"
            File(dir, name).writeBytes(bytes)
            return name
        }
        val items = when (slide) {
            is SlideSpec.Picture -> listOf(PreviewItem(slide.rect.x, slide.rect.y, slide.rect.w, slide.rect.h, image = img(slide.bytes, slide.format)))
            is SlideSpec.Group -> slide.items.map { g ->
                when (g) {
                    is GroupItem.Text -> PreviewItem(g.rect.x, g.rect.y, g.rect.w, g.rect.h, g.paras.map { pp ->
                        PreviewPara(pp.runs.map { PreviewRun(it.text, it.color, it.bold, it.italic, it.rise) }, pp.sizePt, pp.marLIn, pp.indentIn, pp.tabsIn, pp.spaceBeforePt)
                    })
                    is GroupItem.Image -> PreviewItem(g.rect.x, g.rect.y, g.rect.w, g.rect.h, image = img(g.bytes, g.format))
                }
            }
            SlideSpec.Blank -> emptyList()
        }
        File(dir, "preview_$key.json").writeText(json.encodeToString(PreviewSlide(rec.kind, rec.isText, rec.fontPt, items)))
    }

    // ---------------------------------------------------------------- sharing
    fun shareIntent(file: File, mime: String): Intent {
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", file)
        return Intent(Intent.ACTION_SEND).setType(mime).putExtra(Intent.EXTRA_STREAM, uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    /** Save to Downloads; never overwrite: "name (2).pptx". Returns the visible file name. */
    fun saveToDownloads(file: File, mime: String): String {
        val name = uniqueName(file.name)
        if (Build.VERSION.SDK_INT >= 29) {
            val cv = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name); put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS); put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val r = ctx.contentResolver
            val uri: Uri = r.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: throw IOException("Could not save to Downloads")
            try {
                r.openOutputStream(uri)!!.use { o -> file.inputStream().use { it.copyTo(o) } }
                r.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) { r.delete(uri, null, null); throw IOException("Could not save to Downloads (storage full?)") }
        } else {
            @Suppress("DEPRECATION")
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            file.copyTo(File(dir, name))
        }
        return name
    }

    private fun uniqueName(name: String): String {
        val base = name.substringBeforeLast('.'); val ext = name.substringAfterLast('.', "")
        fun exists(n: String): Boolean = if (Build.VERSION.SDK_INT >= 29) {
            ctx.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, arrayOf(MediaStore.Downloads._ID),
                "${MediaStore.Downloads.DISPLAY_NAME}=?", arrayOf(n), null)?.use { it.count > 0 } ?: false
        } else {
            @Suppress("DEPRECATION")
            File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), n).exists()
        }
        var n = name; var k = 2
        while (exists(n)) { n = "$base ($k).$ext"; k++ }
        return n
    }
}
