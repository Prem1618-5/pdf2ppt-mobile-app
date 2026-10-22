package app.papertoslides.data

import app.papertoslides.core.project.Project
import java.io.File

data class ProjectSummary(val id: String, val name: String, val createdAt: Long, val questions: Int, val flagged: Int, val deck: File?)

/**
 * files/projects/<id>/ : project.json (single source of truth), src/ (copies of the picked files), work/ (layout
 * JPEGs, OCR cache), renders/ (question pictures per layout), the .pptx and the QA report.
 */
class ProjectStore(private val root: File) {
    init { root.mkdirs() }

    fun dir(id: String) = File(root, id)
    fun srcDir(id: String) = File(dir(id), "src").apply { mkdirs() }
    fun workDir(id: String) = File(dir(id), "work").apply { mkdirs() }
    fun renderDir(id: String) = File(dir(id), "renders").apply { mkdirs() }
    fun deckFile(p: Project) = File(dir(p.id), safe(p.name) + "-slides.pptx")
    fun qaFile(p: Project) = File(dir(p.id), safe(p.name) + "-qa-report.txt")

    @Synchronized fun load(id: String): Project? = runCatching { Project.decode(File(dir(id), "project.json").readText()) }.getOrNull()

    /** Never saves a stale copy over a newer one (legacy editor lesson): rev must move forward. */
    @Synchronized fun save(p: Project): Project {
        val current = load(p.id)
        if (current != null && current.rev > p.rev) error("stale project copy (rev ${p.rev} < ${current.rev})")
        val next = p.copy(rev = (current?.rev ?: p.rev) + 1)
        val f = File(dir(p.id), "project.json"); f.parentFile?.mkdirs()
        val tmp = File(f.path + ".tmp"); tmp.writeText(next.encode()); tmp.renameTo(f)
        return next
    }

    fun list(): List<ProjectSummary> = (root.listFiles() ?: emptyArray()).filter { it.isDirectory }.mapNotNull { d ->
        val p = load(d.name) ?: return@mapNotNull null
        ProjectSummary(p.id, p.name, p.createdAt, p.items.size,
            p.items.count { it.flags.any { f -> f in setOf("low_confidence", "no_number", "too_small_to_read") } },
            deckFile(p).takeIf { it.exists() })
    }.sortedByDescending { it.createdAt }

    fun delete(id: String) = dir(id).deleteRecursively()

    companion object {
        private val BAD = Regex("""[^\p{L}\p{N} ._()-]+""")
        /** Safe file name: no path tricks, no odd characters, not too long. */
        fun safe(name: String): String = name.replace(BAD, "_").trim().trim('.').take(80).ifEmpty { "paper" }
    }
}
