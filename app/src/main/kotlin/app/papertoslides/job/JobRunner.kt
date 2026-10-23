package app.papertoslides.job

import android.content.Context
import android.graphics.BitmapFactory
import app.papertoslides.Services
import app.papertoslides.core.gemini.GeminiClient
import app.papertoslides.core.gemini.GeminiProtocol
import app.papertoslides.core.gemini.KeyCheck
import app.papertoslides.core.gemini.LayoutCache
import app.papertoslides.core.gemini.ModelInfo
import app.papertoslides.core.imaging.Imaging
import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.keypool.SystemClock
import app.papertoslides.core.ladder.LadderListener
import app.papertoslides.core.ladder.LadderTiers
import app.papertoslides.core.ladder.LayoutLadder
import app.papertoslides.core.ladder.PageInfo
import app.papertoslides.core.model.PageKind
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.TextLine
import app.papertoslides.core.model.Tier
import app.papertoslides.core.project.JobStats
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.ProjectPage
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.core.scheduler.SchedulerListener
import app.papertoslides.core.scheduler.SliceScheduler
import app.papertoslides.platform.Bitmaps
import app.papertoslides.platform.NetworkGate
import app.papertoslides.platform.Ocr
import app.papertoslides.platform.SourceException
import app.papertoslides.platform.SourceFile
import app.papertoslides.platform.Sources
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

@Serializable
data class JobSpec(
    val projectId: String,
    val name: String,
    val settings: ProjectSettings,
    val allowOverCap: Boolean = false,
    /** Self-test: never call Gemini (it would spend the owner's quota). */
    val noGemini: Boolean = false,
)

data class JobOutcome(val project: Project, val warnings: List<String>)

/**
 * pass 1 (small layout JPEGs, dHash, text lines) -> layout ladder (T0 -> T1 -> T2 -> T3) -> project.json -> deck.
 * One page at full resolution in memory at a time; every stage is cached so a resumed job does not repeat API calls.
 */
class JobRunner(private val ctx: Context, private val s: Services, private val report: (JobProgress.() -> JobProgress) -> Unit) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun run(spec: JobSpec, governor: Governor?): JobOutcome {
        val t0 = System.currentTimeMillis()
        val store = s.projects
        val work = store.workDir(spec.projectId)
        val files = store.srcDir(spec.projectId).listFiles()?.filter { it.isFile }?.sortedBy { it.name } ?: emptyList()
        val warnings = mutableListOf<String>()
        val sources = ArrayList<SourceFile?>()
        val infos = ArrayList<PageInfo>()
        val pages = ArrayList<ProjectPage>()
        val jpegFiles = ArrayList<File>()
        val hashes = ArrayList<BooleanArray>()
        val app = s.settings.get()
        try {
            // ---- pass 1 -------------------------------------------------------------------------
            for ((fi, f) in files.withIndex()) {
                val src = try { Sources.open(f, work) } catch (e: SourceException) { warnings += e.message!!; null }
                catch (e: Exception) { warnings += "Could not read ${displayName(f)}."; null }
                sources += src
                src ?: continue
                for (pi in 0 until src.pageCount) {
                    coroutineContext.ensureActive()
                    if (pages.size >= app.pageCap && !spec.allowOverCap) {
                        warnings += "Stopped at ${app.pageCap} pages (page limit). Change it in Settings."
                        break
                    }
                    governor?.gate()
                    val seq = pages.size
                    val (wIn, hIn) = src.sizeIn(pi)
                    val jf = File(work, "p%04d.jpg".format(seq))
                    val bmp = src.renderFull(pi, 1536)
                    if (!jf.exists()) jf.writeBytes(Bitmaps.jpeg(bmp, 82))
                    val small = Bitmaps.toRaster(Bitmaps.fit(bmp, 400))
                    bmp.recycle()
                    val h = Imaging.dhash(small)
                    val dup = hashes.indexOfFirst { Imaging.hamming(it, h) <= 12 }.takeIf { it >= 0 }
                    hashes += h
                    val lines = runCatching { src.textLines(pi) }.getOrDefault(emptyList())
                    val kind = when {
                        src.isImage -> PageKind.IMAGE
                        lines.isNotEmpty() -> PageKind.PDF_DIGITAL // by real text lines, not a 200-char rule
                        else -> PageKind.PDF_SCAN
                    }
                    val ink = Imaging.inkRatio(small) > 0.002 || lines.isNotEmpty()
                    infos += PageInfo(seq, kind, lines, hasInk = ink, duplicateOf = dup)
                    pages += ProjectPage(seq, "${displayName(f)}#p${pi + 1}", kind, fi, pi, wIn, hIn, src.nativePpi(pi))
                    jpegFiles += jf
                    report { copy(stage = "Reading pages", done = seq + 1, total = maxOf(total, seq + 1)) }
                }
            }
            if (pages.isEmpty()) throw SourceException(warnings.firstOrNull() ?: "No pages could be read.")

            // ---- layout ladder -------------------------------------------------------------------
            val ocr = Ocr(hindi = spec.settings.lang != "en")
            var apiRequests = 0
            try {
                val ocrDone = java.util.concurrent.atomic.AtomicInteger(0)
                val tiers = LadderTiers(
                    ocr = { p -> ocrPage(p, pages, sources, work, ocr, governor).also { report { copy(stage = "Reading scanned pages", done = ocrDone.incrementAndGet(), total = pages.size) } } },
                    gemini = if (spec.noGemini) null else gemini(spec, jpegFiles, warnings) { apiRequests += it },
                    heuristic = { p ->
                        val b = BitmapFactory.decodeFile(jpegFiles[p].path)
                        Imaging.analyseImagePage(Bitmaps.toRaster(b)).also { b.recycle() }
                    },
                )
                report { copy(stage = "Finding questions", done = 0, total = pages.size, stageStartedAt = System.currentTimeMillis()) }
                val res = LayoutLadder(infos, tiers, spec.settings.lang, LadderListener { _, _ -> }).run()

                // ---- page text lines for editable-text slides (text layer, else OCR) ------------------
                for ((i, info) in infos.withIndex()) {
                    val lines = if (res.tiers.getOrNull(i) == Tier.T0_TEXT) info.textLines
                    else runCatching { json.decodeFromString<List<TextLine>>(File(work, "ocr_%04d.json".format(i)).readText()) }.getOrNull()
                    if (lines != null) File(work, "lines_%04d.json".format(i)).writeText(json.encodeToString(lines))
                }

                // ---- project -----------------------------------------------------------------------
                val tierCounts = res.tiers.filterNotNull().groupingBy { it.name }.eachCount()
                var project = Project(
                    id = spec.projectId, name = spec.name, createdAt = t0, sources = files.map { displayName(it) },
                    pages = Project.pagesFrom(infos, pages, res), items = Project.itemsFrom(res, spec.settings),
                    settings = spec.settings, integrity = res.integrity,
                    stats = JobStats(pages.size, tierCounts, res.geminiPages, apiRequests, (System.currentTimeMillis() - t0) / 1000.0),
                )
                if (project.items.isEmpty()) throw SourceException("No questions were found in this paper. " +
                    if (s.keys.all().isEmpty()) "Add a Gemini key in Keys for scans and photos the phone cannot read." else "Try clearer pictures of the pages.")
                project = store.save(project)
                closeAll(sources)
                sources.clear()

                // ---- deck --------------------------------------------------------------------------
                project = s.exporter.export(project, governor) { d, t -> report { copy(stage = "Making slides", done = d, total = t) } }
                return JobOutcome(project.copy(stats = project.stats.copy(seconds = (System.currentTimeMillis() - t0) / 1000.0)), warnings)
            } finally {
                ocr.close()
            }
        } finally {
            closeAll(sources)
            JobBus.activeLanes = null
        }
    }

    private fun closeAll(sources: List<SourceFile?>) = sources.forEach { runCatching { it?.close() } }

    /** OCR of one page at ~2000 px; images are de-skewed from the OCR line angles. Cached per page. */
    private suspend fun ocrPage(p: Int, pages: List<ProjectPage>, sources: MutableList<SourceFile?>, work: File, ocr: Ocr, governor: Governor?): List<TextLine>? {
        val cache = File(work, "ocr_%04d.json".format(p))
        if (cache.exists()) runCatching { return json.decodeFromString<List<TextLine>>(cache.readText()) }
        val meta = pages[p]
        var src = sources.getOrNull(meta.file) ?: return null
        governor?.gate()
        return try {
            var bmp = src.renderFull(meta.index, 2000)
            var r = ocr.read(bmp)
            if (src is Sources.ImageSource && kotlin.math.abs(r.skewDegrees) in 0.7f..15f) {
                src = src.deskew(r.skewDegrees)
                sources[meta.file] = src
                bmp.recycle()
                bmp = src.renderFull(0, 2000)
                r = ocr.read(bmp)
                // the layout JPEG must match the de-skewed pixels
                File(work, "p%04d.jpg".format(p)).writeBytes(Bitmaps.jpeg(Bitmaps.fit(bmp, 1536), 82))
            }
            bmp.recycle()
            cache.writeText(json.encodeToString(r.lines))
            r.lines
        } catch (e: Exception) {
            null // OCR unavailable on this phone: the ladder moves on
        }
    }

    /** Tier T2: parallel keys through the slice scheduler, with the per-page cache in front. */
    private suspend fun gemini(spec: JobSpec, jpegs: List<File>, warnings: MutableList<String>, onRequests: (Int) -> Unit): (suspend (List<Int>) -> Map<Int, PageLayout>)? {
        val keys = s.keys.secrets()
        if (keys.isEmpty()) return null
        return { pagesIn ->
            val out = ConcurrentHashMap<Int, PageLayout>()
            val cache = LayoutCache(File(ctx.cacheDir, "layout"))
            val need = pagesIn.filter { p ->
                val c = cache.get(jpegs[p].readBytes(), acceptSoft = true)
                if (c != null) { out[p] = c; false } else true
            }
            if (need.isNotEmpty()) {
                val app = s.settings.get()
                val models = models(keys, app.models)
                if (models.isEmpty()) {
                    warnings += "Gemini could not be reached; scanned pages were read on the phone only."
                } else {
                    val health = s.keys.health()
                    val lanes = KeyLanes(keys, models, app.rpmPerKey, SystemClock, health)
                    JobBus.activeLanes = lanes
                    val gate = NetworkGate(ctx)
                    val client = GeminiClient(s.http, { p -> jpegs[p].readBytes() }, s.settings.get().discovered)
                    val sched = SliceScheduler(
                        lanes, client,
                        store = { p, l -> out[p] = l; cache.put(jpegs[p].readBytes(), l) },
                        awaitNetwork = { gate.await { report { copy(message = "Waiting for internet…") } }.also { report { copy(message = null) } } },
                        listener = SchedulerListener { _, done -> report { copy(stage = "Asking Gemini", done = done, total = need.size, lanes = lanes.stats()) } },
                    )
                    report { copy(stage = "Asking Gemini", done = 0, total = need.size, lanes = lanes.stats(), stageStartedAt = System.currentTimeMillis()) }
                    try {
                        val left = sched.run(SliceScheduler.plan(need, app.slicePages))
                        if (left.isNotEmpty()) warnings += "${left.sumOf { it.pages.size }} page(s) could not be sent to Gemini (keys busy or out of quota); they are flagged."
                        if (lanes.locationBlocked) warnings += "Gemini is not available in your region."
                    } finally {
                        onRequests(sched.requestsSent)
                        s.keys.saveHealth(health)
                        report { copy(lanes = lanes.stats()) }
                    }
                }
            }
            out
        }
    }

    /** Model order: user's list checked against models.list (free); refreshed once a day. Never hard-coded. */
    private suspend fun models(keys: List<String>, configured: String): List<String> {
        var app = s.settings.get()
        if (app.discovered.isEmpty() || System.currentTimeMillis() - app.discoveredAt > 24 * 3600_000L) {
            for (k in keys) {
                val r = GeminiClient.checkKey(s.http, k)
                if (r is KeyCheck.Ok) {
                    app = s.settings.update { it.copy(discovered = r.models.filter { m -> m.generates }.associate { m -> m.name to m.outputTokenLimit }, discoveredAt = System.currentTimeMillis()) }
                    break
                }
            }
        }
        val available = app.discovered.map { (n, l) -> ModelInfo(n, l, true) }
        return GeminiProtocol.chooseModels(configured.split(',').map { it.trim() }.filter { it.isNotEmpty() }, available)
    }

    companion object {
        /** "03_My Paper.pdf" -> "My Paper.pdf" (sources are stored with an order prefix). */
        fun displayName(f: File) = f.name.replaceFirst(Regex("^\\d{2}_"), "")
    }
}
