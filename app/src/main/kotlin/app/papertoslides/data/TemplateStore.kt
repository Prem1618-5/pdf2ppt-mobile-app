package app.papertoslides.data

import app.papertoslides.core.template.SlideTemplate
import java.io.File

/**
 * Slide templates the teacher designed, saved for future decks: files/templates/<id>.json plus its logo images.
 * Each deck keeps its own copy of the template it was made with, so editing a template never changes old decks.
 */
class TemplateStore(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(id: String) = File(dir, ProjectStore.safe(id) + ".json")
    fun imageFile(name: String) = File(dir, ProjectStore.safe(name))

    @Synchronized fun list(): List<SlideTemplate> =
        listOf(SlideTemplate.PLAIN) + (dir.listFiles { f -> f.extension == "json" } ?: emptyArray())
            .mapNotNull { runCatching { SlideTemplate.decode(it.readText()) }.getOrNull() }
            .sortedByDescending { it.updatedAt }

    @Synchronized fun get(id: String?): SlideTemplate =
        if (id == null || id == SlideTemplate.PLAIN.id) SlideTemplate.PLAIN
        else runCatching { SlideTemplate.decode(file(id).readText()) }.getOrDefault(SlideTemplate.PLAIN)

    @Synchronized fun save(t: SlideTemplate): SlideTemplate {
        require(t.id != SlideTemplate.PLAIN.id) { "the plain template cannot be changed" }
        val s = t.sanitised().copy(updatedAt = System.currentTimeMillis())
        val tmp = File(dir, s.id + ".tmp")
        tmp.writeText(s.encode()); tmp.renameTo(file(s.id))
        return s
    }

    @Synchronized fun delete(id: String) {
        val t = get(id)
        t.elements.mapNotNull { it.image }.forEach { imageFile(it).delete() }
        file(id).delete()
    }

    /** Logo images of a template, for the PPTX writer: name -> (bytes, format). */
    fun images(t: SlideTemplate): Map<String, Pair<ByteArray, String>> =
        t.elements.mapNotNull { it.image }.distinct().mapNotNull { n ->
            val f = imageFile(n)
            if (f.exists()) n to (f.readBytes() to (if (n.endsWith(".png")) "png" else "jpeg")) else null
        }.toMap()
}
