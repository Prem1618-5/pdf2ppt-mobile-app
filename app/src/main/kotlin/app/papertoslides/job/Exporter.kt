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
import app.papertoslides.core.project.DeckExporter
import app.papertoslides.core.project.Item
import app.papertoslides.core.project.ItemRenderer
import app.papertoslides.core.project.PageCropper
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.QaReport
import app.papertoslides.core.project.Rendered
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

/**
 * Project -> .pptx + QA report. Question pictures are cached per (parts, layout, dpi, watermark), so switching layout
 * re-renders only what changed and never calls the API. The deck is written to a temp file and renamed: no
 * half-written deck after a cancel or a full disk.
 */
class Exporter(private val ctx: Context, private val store: ProjectStore) {
    private val json = Json { ignoreUnknownKeys = true }

    fun renderKey(p: Project, it: Item): String = GeminiProtocol.sha256(
        (json.encodeToString(it.parts) + p.settings.layout + p.settings.dpi + p.settings.removeWatermark + "r1").encodeToByteArray(),
    ).take(16)

    fun cachedPng(p: Project, it: Item) = File(store.renderDir(p.id), "${it.id}_${renderKey(p, it)}.png")

    suspend fun export(project: Project, governor: Governor?, onItem: (Int, Int) -> Unit): Project {
        val sources = HashMap<Int, SourceFile>()
        val work = store.workDir(project.id)
        val files = store.srcDir(project.id).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val renderer = ItemRenderer(PageCropper { page, box, ppi ->
            val src = sources.getOrPut(page.file) { Sources.open(files[page.file], work) }
            src.crop(page.index, box, ppi)
        })
        val deck = store.deckFile(project)
        val tmp = File(deck.path + ".part")
        try {
            val result = tmp.outputStream().use { out ->
                DeckExporter.export(project, out, { item -> governor?.gate(); render(project, item, renderer) }, Bitmaps.encoder, onItem)
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

    private suspend fun render(p: Project, it: Item, renderer: ItemRenderer): Rendered {
        val png = cachedPng(p, it)
        val meta = File(png.path + ".json")
        if (png.exists() && meta.exists()) {
            val m = runCatching { json.decodeFromString<RenderMeta>(meta.readText()) }.getOrNull()
            val b = BitmapFactory.decodeFile(png.path)
            if (m != null && b != null) return Rendered(Bitmaps.toRaster(b), m.ppi, m.glyphPt).also { b.recycle() }
        }
        val r = renderer.render(p, it)
        val b = Bitmaps.toBitmap(r.img)
        png.writeBytes(Bitmaps.png(b)); b.recycle()
        meta.writeText(json.encodeToString(RenderMeta(r.ppi, r.glyphPt)))
        return r
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
