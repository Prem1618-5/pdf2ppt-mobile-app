package app.papertoslides.ui

import android.app.Application
import android.net.Uri
import android.provider.OpenableColumns
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.papertoslides.Services
import app.papertoslides.core.gemini.GeminiClient
import app.papertoslides.core.gemini.KeyCheck
import app.papertoslides.core.geometry.SlideLayout
import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.project.Project
import app.papertoslides.core.project.ProjectSettings
import app.papertoslides.data.AppSettings
import app.papertoslides.data.ProjectStore
import app.papertoslides.data.ProjectSummary
import app.papertoslides.job.JobBus
import app.papertoslides.job.JobRunner
import app.papertoslides.job.JobSpec
import app.papertoslides.job.PaperWorker
import app.papertoslides.platform.SourceException
import app.papertoslides.platform.Sources
import app.papertoslides.security.KeyView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

/** Files picked and copied, waiting for the options sheet. */
data class Draft(val projectId: String, val name: String, val files: List<String>, val pages: Int, val problems: List<String>)

data class KeyRow(val view: KeyView, val state: String, val note: String?)

data class ReviewState(val project: Project? = null, val busy: String? = null, val error: String? = null, val version: Int = 0)

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val s = Services.get(app)
    private val ctx get() = getApplication<Application>()

    val job = JobBus.state
    private val _projects = MutableStateFlow<List<ProjectSummary>>(emptyList())
    val projects: StateFlow<List<ProjectSummary>> = _projects
    private val _settings = MutableStateFlow(s.settings.get())
    val settings: StateFlow<AppSettings> = _settings
    private val _draft = MutableStateFlow<Draft?>(null)
    val draft: StateFlow<Draft?> = _draft
    private val _preparing = MutableStateFlow(false)
    val preparing: StateFlow<Boolean> = _preparing
    private val _keys = MutableStateFlow<List<KeyRow>>(emptyList())
    val keys: StateFlow<List<KeyRow>> = _keys
    private val _keyMessage = MutableStateFlow<String?>(null)
    val keyMessage: StateFlow<String?> = _keyMessage
    private val _review = MutableStateFlow(ReviewState())
    val review: StateFlow<ReviewState> = _review
    private val editLock = Mutex()

    init { PaperWorker.restore(app); refresh() }

    fun refresh() = viewModelScope.launch(Dispatchers.IO) {
        _projects.value = s.projects.list()
        _settings.value = s.settings.get()
        refreshKeys()
    }

    // ------------------------------------------------------------------ pick -> options -> start
    fun prepare(uris: List<Uri>) = viewModelScope.launch {
        if (uris.isEmpty()) return@launch
        _preparing.value = true
        val d = withContext(Dispatchers.IO) {
            val id = "p" + System.currentTimeMillis().toString(36) + UUID.randomUUID().toString().take(4)
            val src = s.projects.srcDir(id)
            val names = ArrayList<String>(); val problems = ArrayList<String>()
            var pages = 0
            uris.forEachIndexed { i, uri ->
                val raw = displayName(uri) ?: "file${i + 1}"
                val name = ProjectStore.safe(raw)
                val f = File(src, "%02d_%s".format(i, name)) // order prefix keeps the picked order
                try {
                    ctx.contentResolver.openInputStream(uri)!!.use { inp -> f.outputStream().use { inp.copyTo(it) } }
                    names += name
                    pages += runCatching { Sources.open(f, s.projects.workDir(id)).use { it.pageCount } }
                        .getOrElse { e -> problems += ((e as? SourceException)?.message ?: "Could not read $name"); 0 }
                } catch (e: Exception) {
                    problems += "Could not copy $name"
                    f.delete()
                }
            }
            val title = names.firstOrNull()?.substringBeforeLast('.') ?: "Paper"
            Draft(id, if (names.size > 1) "$title +${names.size - 1}" else title, names, pages, problems)
        }
        _preparing.value = false
        _draft.value = d
    }

    private fun displayName(uri: Uri): String? = runCatching {
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()

    fun dismissDraft() {
        _draft.value?.let { d -> viewModelScope.launch(Dispatchers.IO) { if (s.projects.load(d.projectId) == null) s.projects.delete(d.projectId) } }
        _draft.value = null
    }

    fun start(d: Draft, layout: SlideLayout, blankAfter: Boolean, watermark: Boolean, lang: String, allowOverCap: Boolean) {
        val st = s.settings.update { it.copy(layout = layout, blankAfter = blankAfter, removeWatermark = watermark, lang = lang) }
        _settings.value = st
        PaperWorker.start(ctx, JobSpec(d.projectId, d.name, ProjectSettings(layout, blankAfter, watermark, lang, st.dpi), allowOverCap))
        _draft.value = null
    }

    fun cancelJob() = PaperWorker.cancel(ctx)

    fun clearFinishedJob() { if (job.value?.finished == true) JobBus.set(null); refresh() }

    fun deleteProject(id: String) = viewModelScope.launch(Dispatchers.IO) { s.projects.delete(id); _projects.value = s.projects.list() }

    // ------------------------------------------------------------------ review / edit / export
    fun openReview(id: String) = viewModelScope.launch(Dispatchers.IO) {
        _review.value = ReviewState(project = s.projects.load(id), error = if (s.projects.load(id) == null) "This deck could not be opened." else null)
    }

    fun thumbnail(p: Project, itemId: String): File? = p.items.firstOrNull { it.id == itemId }?.let { s.exporter.cachedPng(p, it) }?.takeIf { it.exists() }

    /** Serialised edits: re-read inside the lock, bump rev, re-export (render cache keeps it fast, 0 API calls). */
    private fun edit(busy: String, f: (Project) -> Project) = viewModelScope.launch(Dispatchers.Default) {
        editLock.withLock {
            val cur = _review.value.project ?: return@withLock
            val fresh = s.projects.load(cur.id) ?: cur
            _review.value = _review.value.copy(busy = busy, error = null)
            try {
                val saved = s.projects.save(f(fresh))
                _review.value = _review.value.copy(project = saved)
                val out = s.exporter.export(saved, null) { d, t -> _review.value = _review.value.copy(busy = "$busy $d/$t") }
                _review.value = ReviewState(project = out, version = _review.value.version + 1)
            } catch (e: Exception) {
                _review.value = _review.value.copy(busy = null, error = e.message ?: "Could not update the deck")
            }
        }
    }

    fun setLayout(l: SlideLayout) = edit("Switching layout…") { p -> p.copy(settings = p.settings.copy(layout = l)) }
    fun setBlankAll(on: Boolean) = edit("Updating…") { p -> p.copy(settings = p.settings.copy(blankAfter = on)) }
    fun deleteItem(id: String) = edit("Updating…") { p -> p.copy(items = p.items.filter { it.id != id }) }
    fun toggleBlank(id: String) = edit("Updating…") { p -> p.copy(items = p.items.map { if (it.id == id) it.copy(blankAfter = !p.blankAfter(it)) else it }) }
    fun move(id: String, delta: Int) = edit("Updating…") { p ->
        val l = p.items.toMutableList(); val i = l.indexOfFirst { it.id == id }; val j = i + delta
        if (i >= 0 && j in l.indices) { val t = l[i]; l[i] = l[j]; l[j] = t }
        p.copy(items = l)
    }

    fun deckFile(p: Project) = s.projects.deckFile(p)
    fun qaFile(p: Project) = s.projects.qaFile(p)
    fun shareIntent(f: File, mime: String) = s.exporter.shareIntent(f, mime)
    suspend fun saveToDownloads(f: File, mime: String): Result<String> = withContext(Dispatchers.IO) { runCatching { s.exporter.saveToDownloads(f, mime) } }

    // ------------------------------------------------------------------ keys
    private fun refreshKeys() {
        val health = s.keys.health()
        val now = System.currentTimeMillis()
        _keys.value = s.keys.views().map { v ->
            val h = health.keys[v.hash]
            val lanes = h?.lanes?.values.orEmpty()
            val state = when {
                h?.invalid == true -> "invalid"
                lanes.isNotEmpty() && lanes.all { it.kind.name == "EXHAUSTED" && it.until > now } -> "exhausted"
                else -> "ok"
            }
            val note = if (state == "exhausted") lanes.minOf { it.until } .let { "Out of free quota until ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it))}" } else h?.error
            KeyRow(v, state, note)
        }
        s.keys.lastError?.let { _keyMessage.value = it }
    }

    /** Paste one or many keys; the clipboard is cleared by the screen after this. */
    fun addKeys(text: String) = viewModelScope.launch(Dispatchers.IO) {
        val n = runCatching { s.keys.add(text) }.getOrElse { _keyMessage.value = "Could not save the key securely on this phone."; return@launch }
        _keyMessage.value = if (n == 0) "No new key found. A Gemini key is a long code (39 characters, starts with AIza)." else "Added $n key${if (n > 1) "s" else ""}. Tap Check to test."
        refreshKeys()
    }

    fun removeKey(id: String) = viewModelScope.launch(Dispatchers.IO) {
        s.keys.all().firstOrNull { it.id == id }?.let { JobBus.activeLanes?.removeKey(it.key) } // B13: a running job stops using it
        s.keys.remove(id)
        refreshKeys()
    }

    /** Free check with models.list (no generation quota). */
    fun checkKey(id: String) = viewModelScope.launch(Dispatchers.IO) {
        val k = s.keys.all().firstOrNull { it.id == id } ?: return@launch
        _keyMessage.value = "Checking ${KeyLanes.mask(k.key)}…"
        val health = s.keys.health()
        val rec = health.of(KeyLanes.khash(k.key))
        _keyMessage.value = when (val r = GeminiClient.checkKey(s.http, k.key)) {
            is KeyCheck.Ok -> {
                rec.invalid = false; rec.error = null
                s.settings.update { st -> st.copy(discovered = r.models.filter { it.generates }.associate { it.name to it.outputTokenLimit }, discoveredAt = System.currentTimeMillis()) }
                "${KeyLanes.mask(k.key)} works (${r.models.count { "flash" in it.name }} Flash models available)."
            }
            KeyCheck.Invalid -> { rec.invalid = true; rec.error = "invalid key"; "${KeyLanes.mask(k.key)} was refused by Google. Check that you copied the whole key." }
            is KeyCheck.Problem -> r.message
        }
        s.keys.saveHealth(health)
        refreshKeys()
    }

    fun clearKeyMessage() { _keyMessage.value = null }

    fun updateSettings(f: (AppSettings) -> AppSettings) = viewModelScope.launch(Dispatchers.IO) { _settings.value = s.settings.update(f) }

    fun hasKeys() = s.keys.all().isNotEmpty()

    // ------------------------------------------------------------------ self-test
    private val _selfTest = MutableStateFlow<app.papertoslides.diag.SelfTestReport?>(null)
    val selfTest: StateFlow<app.papertoslides.diag.SelfTestReport?> = _selfTest
    fun runSelfTest() = viewModelScope.launch(Dispatchers.Default) {
        app.papertoslides.diag.SelfTest(ctx, s).run { _selfTest.value = it }
    }

    companion object { fun runnerName(f: File) = JobRunner.displayName(f) }
}
