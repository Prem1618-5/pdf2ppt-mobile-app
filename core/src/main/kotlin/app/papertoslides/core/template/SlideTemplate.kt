package app.papertoslides.core.template

import app.papertoslides.core.geometry.RectIn
import app.papertoslides.core.geometry.SLIDE_H
import app.papertoslides.core.geometry.SLIDE_W
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.max
import kotlin.math.min

@Serializable
enum class ElementKind { STRIP, TEXT, IMAGE }

/** One branding element the teacher placed on the slide (institute strip, text, logo). Inches, colours "#RRGGBB". */
@Serializable
data class TemplateElement(
    val id: String,
    val kind: ElementKind,
    val rect: RectIn,
    val text: String = "",
    val fontPt: Double = 20.0,
    val bold: Boolean = true,
    /** left | center | right */
    val align: String = "center",
    val textColor: String = "#FFFFFF",
    /** Fill of the strip / text box; null = transparent. */
    val fill: String? = "#1F3A93",
    /** Image file name inside the template folder (logos). */
    val image: String? = null,
)

/**
 * A reusable slide design: the teacher's elements on every slide (on the PowerPoint slide layout, so it stays
 * consistent and can be edited once in PowerPoint) and the question area (the placeholder rectangle). The two
 * question layouts are computed inside the question area.
 */
@Serializable
data class SlideTemplate(
    val id: String,
    val name: String,
    val questionArea: RectIn = FULL,
    val elements: List<TemplateElement> = emptyList(),
    val lightBg: String = "#FFFFFF",
    val darkBg: String = "#16181D",
    val updatedAt: Long = 0,
) {
    /** Clamped to the slide, never smaller than 3 x 2 in, elements inside the slide. */
    fun sanitised(): SlideTemplate {
        fun clamp(r: RectIn, minW: Double, minH: Double): RectIn {
            val w = r.w.coerceIn(minW, SLIDE_W); val h = r.h.coerceIn(minH, SLIDE_H)
            return RectIn(r.x.coerceIn(0.0, SLIDE_W - w), r.y.coerceIn(0.0, SLIDE_H - h), w, h)
        }
        return copy(
            questionArea = clamp(questionArea, 3.0, 2.0),
            elements = elements.map { it.copy(rect = clamp(it.rect, 0.2, 0.1), fontPt = it.fontPt.coerceIn(6.0, 96.0)) },
            lightBg = colour(lightBg, "#FFFFFF"), darkBg = colour(darkBg, "#16181D"),
        )
    }

    /** Elements overlapping the question area (the editor warns about them; they may cover a question). */
    fun overlaps(): List<TemplateElement> = elements.filter { e ->
        val a = questionArea; val r = e.rect
        min(a.x + a.w, r.x + r.w) - max(a.x, r.x) > 0.05 && min(a.y + a.h, r.y + r.h) - max(a.y, r.y) > 0.05
    }

    fun encode(): String = JSON.encodeToString(serializer(), this)

    companion object {
        val FULL = RectIn(0.0, 0.0, SLIDE_W, SLIDE_H)
        val PLAIN = SlideTemplate("plain", "Plain (no branding)")
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true }
        private val HEX = Regex("^#[0-9A-Fa-f]{6}$")
        fun colour(c: String?, fallback: String) = if (c != null && HEX.matches(c)) c.uppercase() else fallback
        fun decode(text: String): SlideTemplate = JSON.decodeFromString(serializer(), text).sanitised()

        /**
         * The client's case in one step: institute name in a strip on top, a second line in a strip at the bottom,
         * and white space on the right. The question area is what is left.
         */
        fun quickSetup(
            id: String, name: String, institute: String, footer: String, colour: String = "#1F3A93",
            textColour: String = "#FFFFFF", stripIn: Double = 0.6, rightMarginIn: Double = 1.0, gapIn: Double = 0.1,
        ): SlideTemplate {
            val els = mutableListOf<TemplateElement>()
            var top = 0.0; var bottom = SLIDE_H
            if (institute.isNotBlank()) {
                els += TemplateElement("top", ElementKind.STRIP, RectIn(0.0, 0.0, SLIDE_W, stripIn), institute, fontPt = (stripIn * 72 * 0.5).coerceIn(12.0, 40.0), fill = colour, textColor = textColour)
                top = stripIn + gapIn
            }
            if (footer.isNotBlank()) {
                els += TemplateElement("bottom", ElementKind.STRIP, RectIn(0.0, SLIDE_H - stripIn * 0.75, SLIDE_W, stripIn * 0.75), footer, fontPt = (stripIn * 0.75 * 72 * 0.45).coerceIn(10.0, 28.0), bold = false, fill = colour, textColor = textColour)
                bottom = SLIDE_H - stripIn * 0.75 - gapIn
            }
            return SlideTemplate(id, name, RectIn(0.0, top, SLIDE_W - rightMarginIn, bottom - top), els).sanitised()
        }
    }
}

@Serializable
enum class DeckTheme { LIGHT, DARK }

/** Colours of the question content per theme ("#RRGGBB"). */
data class ThemeColours(val bg: String, val text: String, val accent: String, val muted: String) {
    companion object {
        fun of(theme: DeckTheme, t: SlideTemplate) = when (theme) {
            DeckTheme.LIGHT -> ThemeColours(t.lightBg, "#111111", "#1F3A93", "#5F6368")
            DeckTheme.DARK -> ThemeColours(t.darkBg, "#F2F2F2", "#8AB4F8", "#B0B4BA")
        }
    }
}
