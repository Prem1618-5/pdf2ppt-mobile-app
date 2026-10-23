package app.papertoslides.core.pptx

import app.papertoslides.core.geometry.EMU_PER_IN
import app.papertoslides.core.geometry.RectIn
import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** One slide: a picture (question), a group of native text + pictures, or nothing (blank working slide). */
sealed interface SlideSpec {
    data class Picture(val bytes: ByteArray, val format: String, val rect: RectIn, val altText: String) : SlideSpec
    /** An invisible container (group shape) with text boxes and pictures, movable as one unit in PowerPoint. */
    data class Group(val name: String, val altText: String, val items: List<GroupItem>) : SlideSpec
    data object Blank : SlideSpec
}

sealed interface GroupItem {
    val rect: RectIn
    data class Text(override val rect: RectIn, val paras: List<TextPara>) : GroupItem
    data class Image(override val rect: RectIn, val bytes: ByteArray, val format: String, val altText: String) : GroupItem
}

data class TextRunSpec(val text: String, val color: String, val bold: Boolean = false, val italic: Boolean = false, val rise: Int = 0)

data class TextPara(
    val runs: List<TextRunSpec>,
    val sizePt: Double,
    val marLIn: Double = 0.0,
    val indentIn: Double = 0.0,
    val tabsIn: List<Double> = emptyList(),
    val spaceBeforePt: Double = 0.0,
    val align: String = "l",
)

/** The slide layout every slide uses: background colour + the teacher's template elements. */
data class LayoutSpec(
    val bg: String,
    val elements: List<app.papertoslides.core.template.TemplateElement>,
    /** image name -> (bytes, "png" | "jpeg") */
    val images: Map<String, Pair<ByteArray, String>> = emptyMap(),
)

/**
 * Hand-written OOXML writer (context §5.6): static parts (theme, master, 11 layouts, props) come from the reference
 * deck as resources; presentation, slides, rels, media and content types are generated. No Apache POI.
 * Slides stream into the zip one by one, so only one picture is in memory at a time.
 */
class PptxWriter(out: OutputStream, private val title: String = "Questions", private val layout: LayoutSpec? = null) : AutoCloseable {
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
            is SlideSpec.Group -> {
                val rels = StringBuilder(layoutRel)
                val body = StringBuilder()
                var id = 3
                var img = 0
                for (item in slide.items) when (item) {
                    is GroupItem.Text -> body.append(textShape(id++, "Question text", item.rect, item.paras, null, false))
                    is GroupItem.Image -> {
                        img++
                        val ext = if (item.format == "png") "png" else "jpeg"
                        put("ppt/media/image${n}_$img.$ext", item.bytes)
                        rels.append(rel("rId${img + 1}", IMAGE, "../media/image${n}_$img.$ext"))
                        body.append(picShape(id++, "Figure $img", item.altText, "rId${img + 1}", item.rect))
                    }
                }
                val b = bounds(slide.items.map { it.rect })
                val grp = """<p:grpSp><p:nvGrpSpPr><p:cNvPr id="2" name="${esc(slide.name)}" descr="${esc(slide.altText)}"/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>""" +
                    """<p:grpSpPr><a:xfrm><a:off x="${emu(b.x)}" y="${emu(b.y)}"/><a:ext cx="${emu(b.w)}" cy="${emu(b.h)}"/><a:chOff x="${emu(b.x)}" y="${emu(b.y)}"/><a:chExt cx="${emu(b.w)}" cy="${emu(b.h)}"/></a:xfrm></p:grpSpPr>""" +
                    body + "</p:grpSp>"
                put("ppt/slides/slide$n.xml", slideXmlBody(grp))
                put("ppt/slides/_rels/slide$n.xml.rels", rels(rels.toString()))
                slides += "group"
            }
        }
    }

    val slideCount get() = slides.size

    override fun close() {
        if (closed) return
        closed = true
        val custom = setOf("ppt/slideLayouts/slideLayout7.xml", "ppt/slideLayouts/_rels/slideLayout7.xml.rels")
        for (part in STATIC_PARTS) if (layout == null || part !in custom) put(part, resource(part))
        if (layout != null) writeLayout(layout)
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

    private fun slideXml(pic: SlideSpec.Picture?): String =
        slideXmlBody(if (pic == null) "" else picShape(2, "Picture 1", pic.altText, "rId2", pic.rect))

    private fun slideXmlBody(body: String): String =
        XML_HEAD + """<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">""" +
            """<p:cSld><p:spTree><p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr><p:grpSpPr/>$body</p:spTree></p:cSld>""" +
            """<p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr></p:sld>"""

    private fun picShape(id: Int, name: String, alt: String, rid: String, r: RectIn) =
        """<p:pic><p:nvPicPr><p:cNvPr id="$id" name="${esc(name)}" descr="${esc(alt)}"/><p:cNvPicPr><a:picLocks noChangeAspect="1"/></p:cNvPicPr><p:nvPr/></p:nvPicPr>""" +
            """<p:blipFill><a:blip r:embed="$rid"/><a:stretch><a:fillRect/></a:stretch></p:blipFill>""" +
            """<p:spPr><a:xfrm><a:off x="${emu(r.x)}" y="${emu(r.y)}"/><a:ext cx="${emu(r.w)}" cy="${emu(r.h)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></p:spPr></p:pic>"""

    /** A text box; [fill] null = invisible (no fill, no line). [anchorCentre] for template strips. */
    private fun textShape(id: Int, name: String, r: RectIn, paras: List<TextPara>, fill: String?, anchorCentre: Boolean, inset: Double = 0.0): String {
        val fillXml = if (fill == null) "<a:noFill/>" else """<a:solidFill><a:srgbClr val="${fill.removePrefix("#")}"/></a:solidFill>"""
        val ins = emu(inset)
        val ps = paras.joinToString("") { p ->
            val tabs = if (p.tabsIn.isEmpty()) "" else "<a:tabLst>" + p.tabsIn.joinToString("") { """<a:tab pos="${emu(it)}" algn="l"/>""" } + "</a:tabLst>"
            val spc = if (p.spaceBeforePt > 0) """<a:spcBef><a:spcPts val="${(p.spaceBeforePt * 100).toInt()}"/></a:spcBef>""" else ""
            val ppr = """<a:pPr marL="${emu(p.marLIn)}" indent="${emu(p.indentIn)}" algn="${p.align}"><a:lnSpc><a:spcPct val="100000"/></a:lnSpc>$spc<a:buNone/>$tabs</a:pPr>"""
            val runs = p.runs.joinToString("") { run(it, p.sizePt) }
            "<a:p>$ppr$runs${endRPr(p)}</a:p>"
        }
        return """<p:sp><p:nvSpPr><p:cNvPr id="$id" name="${esc(name)}"/><p:cNvSpPr txBox="1"/><p:nvPr/></p:nvSpPr>""" +
            """<p:spPr><a:xfrm><a:off x="${emu(r.x)}" y="${emu(r.y)}"/><a:ext cx="${emu(r.w)}" cy="${emu(r.h)}"/></a:xfrm><a:prstGeom prst="rect"><a:avLst/></a:prstGeom>$fillXml<a:ln><a:noFill/></a:ln></p:spPr>""" +
            """<p:txBody><a:bodyPr wrap="square" lIns="$ins" tIns="0" rIns="$ins" bIns="0" anchor="${if (anchorCentre) "ctr" else "t"}" rtlCol="0"><a:noAutofit/></a:bodyPr><a:lstStyle/>$ps</p:txBody></p:sp>"""
    }

    private fun rPr(r: TextRunSpec, size: Double): String {
        val hindi = DEVA.containsMatchIn(r.text)
        val base = when (r.rise) { 1 -> """ baseline="30000""""; -1 -> """ baseline="-25000""""; else -> "" }
        return """<a:rPr lang="${if (hindi) "hi-IN" else "en-US"}" sz="${(size * 100).toInt()}" b="${if (r.bold) 1 else 0}" i="${if (r.italic) 1 else 0}"$base dirty="0">""" +
            """<a:solidFill><a:srgbClr val="${r.color.removePrefix("#")}"/></a:solidFill><a:latin typeface="Arial"/><a:cs typeface="Nirmala UI"/></a:rPr>"""
    }

    private fun run(r: TextRunSpec, size: Double) = "<a:r>${rPr(r, size)}<a:t>${esc(r.text, keepTab = true)}</a:t></a:r>"

    private fun endRPr(p: TextPara): String {
        val c = p.runs.lastOrNull()?.color ?: "#000000"
        return """<a:endParaRPr lang="en-US" sz="${(p.sizePt * 100).toInt()}" dirty="0"><a:solidFill><a:srgbClr val="${c.removePrefix("#")}"/></a:solidFill></a:endParaRPr>"""
    }

    /** Slide layout 7 ("Blank") with the theme background and the template elements on it. */
    private fun writeLayout(l: LayoutSpec) {
        val base = String(resource("ppt/slideLayouts/slideLayout7.xml"), Charsets.UTF_8)
        val bg = """<p:bg><p:bgPr><a:solidFill><a:srgbClr val="${l.bg.removePrefix("#")}"/></a:solidFill><a:effectLst/></p:bgPr></p:bg>"""
        val shapes = StringBuilder()
        val rels = StringBuilder(rel("rId1", NS_R + "slideMaster", "../slideMasters/slideMaster1.xml"))
        var id = 20; var img = 0
        for (e in l.elements) when (e.kind) {
            app.papertoslides.core.template.ElementKind.IMAGE -> {
                val data = e.image?.let { l.images[it] } ?: continue
                img++
                val ext = if (data.second == "png") "png" else "jpeg"
                put("ppt/media/template_$img.$ext", data.first)
                rels.append(rel("rId${img + 1}", IMAGE, "../media/template_$img.$ext"))
                shapes.append(picShape(id++, "Template logo $img", "Logo", "rId${img + 1}", e.rect))
            }
            else -> {
                val algn = when (e.align) { "left" -> "l"; "right" -> "r"; else -> "ctr" }
                val paras = e.text.split('\n').map { line ->
                    TextPara(listOf(TextRunSpec(line, e.textColor, bold = e.bold)), e.fontPt, align = algn)
                }
                shapes.append(textShape(id++, "Template ${e.id}", e.rect, paras, e.fill, anchorCentre = true, inset = 0.1))
            }
        }
        val xml = base.replace("""<p:cSld name="Blank">""", """<p:cSld name="Blank">$bg""").replace("</p:spTree>", "$shapes</p:spTree>")
        put("ppt/slideLayouts/slideLayout7.xml", xml)
        put("ppt/slideLayouts/_rels/slideLayout7.xml.rels", rels(rels.toString()))
    }

    private fun bounds(rs: List<RectIn>): RectIn {
        val x0 = rs.minOf { it.x }; val y0 = rs.minOf { it.y }
        return RectIn(x0, y0, rs.maxOf { it.x + it.w } - x0, rs.maxOf { it.y + it.h } - y0)
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

        // stored as "rels/": Android's packager silently drops resource folders whose names start with "_"
        private fun resource(name: String): ByteArray =
            PptxWriter::class.java.getResourceAsStream("/pptx/" + name.replace("_rels/", "rels/"))?.use { it.readBytes() }
                ?: error("missing pptx resource $name")

        private fun rel(id: String, type: String, target: String) = """<Relationship Id="$id" Type="$type" Target="$target"/>"""
        private fun rels(body: String) = XML_HEAD +
            """<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$body</Relationships>"""

        private val DEVA = Regex("[\u0900-\u097F]")
        private fun emu(v: Double) = Math.round(v * EMU_PER_IN)

        fun esc(s: String, keepTab: Boolean = false) = buildString {
            for (c in s) when (c) {
                '&' -> append("&amp;"); '<' -> append("&lt;"); '>' -> append("&gt;"); '"' -> append("&quot;"); '\'' -> append("&apos;")
                '\t' -> append(if (keepTab) '\t' else ' ')
                else -> if (c.code < 0x20) append(' ') else append(c)
            }
        }
    }
}
