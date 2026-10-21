package app.papertoslides.core.pptx

import app.papertoslides.core.geometry.EMU_PER_IN
import app.papertoslides.core.geometry.RectIn
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One slide: a picture (question) or nothing (blank working slide). */
sealed interface SlideSpec {
    data class Picture(val bytes: ByteArray, val format: String, val rect: RectIn, val altText: String) : SlideSpec
    data object Blank : SlideSpec
}

/**
 * Hand-written OOXML writer (context §5.6): static parts (theme, master, 11 layouts, props) come from the reference
 * deck as resources; presentation, slides, rels, media and content types are generated. No Apache POI.
 * Slides stream into the zip one by one, so only one picture is in memory at a time.
 */
class PptxWriter(out: OutputStream, private val title: String = "Questions") : AutoCloseable {
    private val zip = ZipOutputStream(out.buffered())
    private val slides = mutableListOf<String?>() // media extension per slide, null = blank
    private var closed = false

    fun add(slide: SlideSpec) {
        val n = slides.size + 1
        when (slide) {
            is SlideSpec.Blank -> {
                put("ppt/slides/slide$n.xml", slideXml(null))
                put("ppt/slides/_rels/slide$n.xml.rels", rels(layoutRel))
                slides += null
            }
            is SlideSpec.Picture -> {
                val ext = if (slide.format == "png") "png" else "jpeg"
                put("ppt/media/image$n.$ext", slide.bytes)
                put("ppt/slides/slide$n.xml", slideXml(slide))
                put("ppt/slides/_rels/slide$n.xml.rels", rels(layoutRel + rel("rId2", IMAGE, "../media/image$n.$ext")))
                slides += ext
            }
        }
    }

    val slideCount get() = slides.size

    override fun close() {
        if (closed) return
        closed = true
        for (part in STATIC_PARTS) put(part, resource(part))
        val ids = slides.indices.joinToString("") { """<p:sldId id="${256 + it}" r:id="rId${10 + it}"/>""" }
        val pres = String(resource("presentation.xml.tmpl"), Charsets.UTF_8)
            .replace("{{SLIDES}}", if (slides.isEmpty()) "" else "<p:sldIdLst>$ids</p:sldIdLst>")
        put("ppt/presentation.xml", pres)
        put("ppt/_rels/presentation.xml.rels", rels(
            rel("rId1", NS_R + "slideMaster", "slideMasters/slideMaster1.xml") +
                rel("rId3", NS_R + "presProps", "presProps.xml") +
                rel("rId4", NS_R + "viewProps", "viewProps.xml") +
                rel("rId5", NS_R + "theme", "theme/theme1.xml") +
                rel("rId6", NS_R + "tableStyles", "tableStyles.xml") +
                slides.indices.joinToString("") { rel("rId${10 + it}", NS_R + "slide", "slides/slide${it + 1}.xml") },
        ))
        put("_rels/.rels", rels(
            rel("rId1", NS_R + "officeDocument", "ppt/presentation.xml") +
                rel("rId2", "http://schemas.openxmlformats.org/package/2006/relationships/metadata/core-properties", "docProps/core.xml") +
                rel("rId3", NS_R + "extended-properties", "docProps/app.xml"),
        ))
        put("docProps/core.xml", XML_HEAD + """<cp:coreProperties xmlns:cp="http://schemas.openxmlformats.org/package/2006/metadata/core-properties" xmlns:dc="http://purl.org/dc/elements/1.1/" xmlns:dcterms="http://purl.org/dc/terms/" xmlns:dcmitype="http://purl.org/dc/dcmitype/" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"><dc:title>${esc(title)}</dc:title><dc:creator>Paper to Slides</dc:creator><cp:revision>1</cp:revision></cp:coreProperties>""")
        put("docProps/app.xml", XML_HEAD + """<Properties xmlns="http://schemas.openxmlformats.org/officeDocument/2006/extended-properties" xmlns:vt="http://schemas.openxmlformats.org/officeDocument/2006/docPropsVTypes"><Application>Paper to Slides</Application><PresentationFormat>Widescreen</PresentationFormat><Slides>${slides.size}</Slides></Properties>""")
        put("[Content_Types].xml", contentTypes())
        zip.close()
    }

    private fun contentTypes(): String {
        val o = StringBuilder(XML_HEAD)
        o.append("""<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">""")
        o.append("""<Default Extension="png" ContentType="image/png"/><Default Extension="jpeg" ContentType="image/jpeg"/>""")
        o.append("""<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/>""")
        fun ov(part: String, type: String) = o.append("""<Override PartName="/$part" ContentType="$type"/>""")
        val pml = "application/vnd.openxmlformats-officedocument.presentationml."
        ov("ppt/presentation.xml", pml + "presentation.main+xml")
        ov("ppt/presProps.xml", pml + "presProps+xml")
        ov("ppt/viewProps.xml", pml + "viewProps+xml")
        ov("ppt/tableStyles.xml", pml + "tableStyles+xml")
        ov("ppt/slideMasters/slideMaster1.xml", pml + "slideMaster+xml")
        for (i in 1..11) ov("ppt/slideLayouts/slideLayout$i.xml", pml + "slideLayout+xml")
        ov("ppt/theme/theme1.xml", "application/vnd.openxmlformats-officedocument.theme+xml")
        for (i in slides.indices) ov("ppt/slides/slide${i + 1}.xml", pml + "slide+xml")
        ov("docProps/core.xml", "application/vnd.openxmlformats-package.core-properties+xml")
        ov("docProps/app.xml", "application/vnd.openxmlformats-officedocument.extended-properties+xml")
        return o.append("</Types>").toString()
    }

    private fun slideXml(pic: SlideSpec.Picture?): String {
        val body = if (pic == null) "" else {
            val r = pic.rect
            fun emu(v: Double) = Math.round(v * EMU_PER_IN)
            """<p:pic><p:nvPicPr><p:cNvPr id="2" name="Picture 1" descr="${esc(pic.altText)}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
                """<p:blipFill><a:blip r:embed="rId2"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
                """<p:spPr><a:xfrm><a:off x="${emu(r.x)}" y="${emu(r.y)}"/><a:ext cx="${emu(r.w)}" cy="${emu(r.h)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""
        }
        return XML_HEAD + """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$body</p:spTree></p:cSld>""" +
            """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""
    }

    private fun put(name: String, text: String) = put(name, text.toByteArray(Charsets.UTF_8))
    private fun put(name: String, bytes: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(bytes)
        zip.closeEntry()
    }

    companion object {
        private const val XML_HEAD = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>"""
        private const val NS_R = "http://schemas.openxmlformats.org/officeDocument/2006/relationships/"
        private const val IMAGE = NS_R + "image"
        private val layoutRel = rel("rId1", NS_R + "slideLayout", "../slideLayouts/slideLayout7.xml")

        private val STATIC_PARTS: List<String> by lazy {
            String(resource("static-parts.txt"), Charsets.UTF_8).lines().map { it.trim() }.filter { it.isNotEmpty() }
        }

        private fun resource(name: String): ByteArray =
            PptxWriter::class.java.getResourceAsStream("/pptx/$name")?.use { it.readBytes() }
                ?: error("missing pptx resource $name")

        private fun rel(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$type" Target="$target"/>"""
        private fun rels(body: String) = XML_HEAD +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$body</Relationships>"""

        fun esc(s: String) = buildString {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
                else -> if (c.code < 0x20 && c != '\t' && c != '\n' && c != '\r') append(' ') else append(c)
            }
        }
    }
}
