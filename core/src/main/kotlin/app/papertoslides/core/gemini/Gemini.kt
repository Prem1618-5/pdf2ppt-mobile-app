package app.papertoslides.core.gemini

import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.Roles
import app.papertoslides.core.scheduler.ApiResult
import app.papertoslides.core.scheduler.GeminiApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** status 0 = network failure (no HTTP response). */
data class HttpResponse(val status: Int, val body: String)

/** The only network surface of the app: HTTPS to generativelanguage.googleapis.com. */
interface HttpTransport {
    suspend fun post(url: String, headers: Map<String, String>, json: String): HttpResponse
    suspend fun get(url: String, headers: Map<String, String>): HttpResponse
}

data class ModelInfo(val name: String, val outputTokenLimit: Int?, val generates: Boolean)

sealed interface KeyCheck {
    data class Ok(val models: List<ModelInfo>) : KeyCheck
    data object Invalid : KeyCheck
    data class Problem(val message: String) : KeyCheck
}

object GeminiProtocol {
    const val PROMPT_VERSION = "v5"
    const val BASE = "https://generativelanguage.googleapis.com/v1beta"
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** Verbatim from legacy `layout_gemini.py` (bump PROMPT_VERSION if this or the schema changes). */
    val PROMPT = """You are a document layout analyser for exam question papers. You receive {n} page image(s), in document order, each preceded by its tag PAGE k. Analyse EVERY page and return one entry per page in "pages" (page = k). Each image is one page (it may be a photo of a two-page booklet spread, a scan, a screenshot or a video frame; it may have 1-4 text columns, a table layout, watermarks, logos, headers and footers).

Return every content region as a fragment, in true human READING ORDER (columns left-to-right, top-to-bottom inside a column; full-width blocks in their natural position; for a two-page spread finish the whole left page before the right page).

Roles:
- question: a region that STARTS a question. It must include the printed question number/label, the stem, any figure/diagram/table/graph belonging to it (also when placed beside the text), statements/lists, and all its options. One question = one fragment unless it is physically split.
- continuation: the rest of a question split by a column break or page break (e.g. options sitting at the top of the next column, or a first block of a page that has no question number / says "contd."). Set continues_previous=true. A line like "(a) 1 (b) 2 (c) 3 (d) 4" at the top of a column is a continuation, never a new question.
- shared_passage: a passage, data table or figure needed by several following questions (comprehension, data interpretation). Short generic directions like "Directions (Q.1-5): choose the correct option" are role=instructions.
- solution: "Ans.", "Sol.", "Answer:", hints, worked solutions or explanations. answer_key: answer tables/lists.
- instructions, section_header (e.g. "PART-A", "SECTION A", "Trade Theory", subject bands), clutter (logos, running headers/footers, URLs, page numbers, brand banners, marks/negative-marks columns, video player UI, subscribe banners, cursors).

Rules:
- box = [ymin, xmin, ymax, xmax] normalised 0-1000 on THAT page's image. Question boxes must be COMPLETE (question number, every option line, every figure) and must not include neighbouring questions, other columns, solutions or clutter. Fragments must not overlap.
- label = the question label exactly as printed ("7.", "Q.43", "(1)", "12"); number = its integer. Option labels (a)/(A)/(1)/A1 are never question labels; numbers inside a stem are not labels. In a table layout the serial-number cell is the label.
- continues_on_next = true if the question is cut at the bottom of its column/page and continues later (possibly on the next page).
- A continuation at the top of PAGE k+1 belongs to the open question at the end of PAGE k; still return it on PAGE k+1 with continues_previous=true.
- lang: language of the question text (bilingual = English and Hindi both inside this fragment).
- stem: the question text in plain Unicode (maths with Unicode symbols, Devanagari as is), without the label. options: each option text without its (a)/(b) label. For a continuation give only the text it contains.
- watermark_present: true if a faint logo/text watermark sits behind the page content.
- dark_background: true if the page has light text on a dark background (e.g. a video frame).
- confidence 0-1.
Skip nothing that is a question. Do not invent questions."""

    private fun str(enum: List<String>? = null) = buildJsonObject {
        put("type", "STRING"); if (enum != null) putJsonArray("enum") { enum.forEach { add(JsonPrimitive(it)) } }
    }
    private fun t(type: String) = buildJsonObject { put("type", type) }

    val SCHEMA: JsonObject by lazy {
        val fragment = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                put("order", t("INTEGER"))
                put("role", str(Roles.ALL))
                put("label", buildJsonObject { put("type", "STRING"); put("nullable", true) })
                put("number", buildJsonObject { put("type", "INTEGER"); put("nullable", true) })
                put("continues_previous", t("BOOLEAN"))
                put("continues_on_next", t("BOOLEAN"))
                put("box", buildJsonObject { put("type", "ARRAY"); put("items", t("INTEGER")) })
                put("lang", str(listOf("en", "hi", "bilingual", "other")))
                put("has_figure", t("BOOLEAN")); put("has_math", t("BOOLEAN")); put("has_table", t("BOOLEAN"))
                put("stem", t("STRING"))
                put("options", buildJsonObject { put("type", "ARRAY"); put("items", t("STRING")) })
                put("confidence", t("NUMBER"))
            }
            putJsonArray("required") {
                listOf("order", "role", "box", "continues_previous", "continues_on_next", "lang", "has_figure", "has_math", "has_table", "confidence")
                    .forEach { add(JsonPrimitive(it)) }
            }
        }
        val page = buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") {
                put("page", t("INTEGER"))
                put("page_kind", str(listOf("questions", "cover_or_instructions", "answer_key", "solutions", "blank_or_ad", "mixed")))
                put("dark_background", t("BOOLEAN"))
                put("watermark_present", t("BOOLEAN"))
                put("fragments", buildJsonObject { put("type", "ARRAY"); put("items", fragment) })
            }
            putJsonArray("required") { listOf("page", "page_kind", "dark_background", "watermark_present", "fragments").forEach { add(JsonPrimitive(it)) } }
        }
        buildJsonObject {
            put("type", "OBJECT")
            putJsonObject("properties") { put("pages", buildJsonObject { put("type", "ARRAY"); put("items", page) }) }
            putJsonArray("required") { add(JsonPrimitive("pages")) }
        }
    }

    fun requestBody(jpegs: List<ByteArray>, thinking: Boolean, maxOutputTokens: Int): String {
        val body = buildJsonObject {
            putJsonArray("contents") {
                add(buildJsonObject {
                    putJsonArray("parts") {
                        jpegs.forEachIndexed { i, j ->
                            add(buildJsonObject { put("text", "PAGE ${i + 1}") })
                            add(buildJsonObject {
                                putJsonObject("inline_data") {
                                    put("mime_type", "image/jpeg")
                                    put("data", Base64.getEncoder().encodeToString(j))
                                }
                            })
                        }
                        add(buildJsonObject { put("text", PROMPT.replace("{n}", jpegs.size.toString())) })
                    }
                })
            }
            putJsonObject("generationConfig") {
                put("responseMimeType", "application/json")
                put("responseSchema", SCHEMA)
                put("temperature", 0)
                put("maxOutputTokens", maxOutputTokens)
                if (thinking) putJsonObject("thinkingConfig") { put("thinkingLevel", "low") }
            }
        }
        return body.toString()
    }

    /** Pages of a 200 response keyed by PAGE k (1-based), or null if the output is unreadable (truncated / blocked). */
    fun parseResponse(body: String): Map<Int, PageLayout>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val parts = root["candidates"]!!.jsonArray[0].jsonObject["content"]!!.jsonObject["parts"]!!.jsonArray
        val text = parts.last().jsonObject["text"]!!.jsonPrimitive.content
        val out = json.parseToJsonElement(text).jsonObject
        val pages = out["pages"]!!.jsonArray
        val byPage = LinkedHashMap<Int, PageLayout>()
        for (p in pages) {
            val lay = json.decodeFromJsonElement<PageLayout>(p)
            byPage.putIfAbsent(lay.page, lay)
        }
        byPage
    } catch (e: Exception) {
        null
    }

    fun parseModels(body: String): List<ModelInfo> = try {
        json.parseToJsonElement(body).jsonObject["models"]?.jsonArray?.map { m ->
            val o = m.jsonObject
            ModelInfo(
                name = o["name"]!!.jsonPrimitive.content.removePrefix("models/"),
                outputTokenLimit = o["outputTokenLimit"]?.jsonPrimitive?.intOrNull,
                generates = (o["supportedGenerationMethods"] as? JsonArray)?.any { it.jsonPrimitive.contentOrNull == "generateContent" } ?: true,
            )
        } ?: emptyList()
    } catch (e: Exception) {
        emptyList()
    }

    private val VERSION = Regex("""(\d+(?:\.\d+)?)""")

    /**
     * Model order (context §10 #6): keep the user's configured names that exist, in their order; when none is
     * configured or none exists, pick the available Flash models, newest first, "lite" ones last. Never hard-coded.
     */
    fun chooseModels(configured: List<String>, available: List<ModelInfo>): List<String> {
        val gen = available.filter { it.generates }.map { it.name }
        val kept = configured.filter { it in gen }
        if (kept.isNotEmpty()) return kept
        val flash = gen.filter { "flash" in it && "image" !in it && "tts" !in it && "audio" !in it && "live" !in it && "embedding" !in it }
        fun version(n: String) = VERSION.find(n.removePrefix("gemini-"))?.value?.toDoubleOrNull() ?: 0.0
        return flash.sortedWith(compareBy<String>({ "lite" in it }, { -version(it) }, { "preview" in it || "exp" in it })).take(6)
    }

    fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    fun cacheKey(jpeg: ByteArray) = sha256(jpeg + PROMPT_VERSION.toByteArray())

    fun parseLayout(text: String): PageLayout? = runCatching { json.decodeFromString<PageLayout>(text) }.getOrNull()
    fun encodeLayout(p: PageLayout): String = Json.encodeToString(PageLayout.serializer(), p)
}

/** Per-page layout cache under `cacheDir/layout/`, keyed by image hash + prompt version. Lite results are "soft". */
class LayoutCache(private val dir: File) {
    init { dir.mkdirs() }

    private fun file(jpeg: ByteArray) = File(dir, GeminiProtocol.cacheKey(jpeg) + ".json")

    /** A cached layout; a soft (lite-model) one only when [acceptSoft]. */
    fun get(jpeg: ByteArray, acceptSoft: Boolean = true): PageLayout? {
        val f = file(jpeg)
        if (!f.exists()) return null
        val p = GeminiProtocol.parseLayout(f.readText()) ?: return null
        return if ("lite" in p.model && !acceptSoft) null else p
    }

    fun put(jpeg: ByteArray, layout: PageLayout) {
        val f = file(jpeg)
        val tmp = File(dir, f.name + ".tmp")
        tmp.writeText(GeminiProtocol.encodeLayout(layout))
        tmp.renameTo(f)
    }
}

/**
 * One request attempt (scheduler contract). The key travels only in the `x-goog-api-key` header, never in a URL.
 * A 400 mentioning "thinking" is retried once without thinkingConfig, as in the legacy client.
 */
class GeminiClient(
    private val http: HttpTransport,
    private val jpegOf: suspend (page: Int) -> ByteArray,
    private val outputLimits: Map<String, Int?> = emptyMap(),
) : GeminiApi {
    private val noThinking = ConcurrentHashMap.newKeySet<String>()

    override suspend fun layout(key: String, model: String, pages: List<Int>): ApiResult {
        val jpegs = pages.map { jpegOf(it) }
        val limit = (outputLimits[model] ?: 32768).coerceAtMost(65536)
        val url = "${GeminiProtocol.BASE}/models/$model:generateContent"
        val headers = mapOf("x-goog-api-key" to key, "Content-Type" to "application/json")
        var r = http.post(url, headers, GeminiProtocol.requestBody(jpegs, model !in noThinking, limit))
        if (r.status == 400 && "thinking" in r.body.lowercase() && noThinking.add(model)) {
            r = http.post(url, headers, GeminiProtocol.requestBody(jpegs, false, limit))
        }
        if (r.status != 200) return ApiResult(r.status, r.body.take(4000))
        val parsed = GeminiProtocol.parseResponse(r.body) ?: return ApiResult(200, "", unreadable = true)
        // PAGE k (1-based) -> page index; stamp the model so lite results become soft cache entries
        val byIndex = pages.withIndex().mapNotNull { (k, p) -> parsed[k + 1]?.let { p to it.copy(page = p, model = model) } }.toMap()
        return ApiResult(200, "", byIndex)
    }

    companion object {
        /** Free key check: `models.list` uses no generation quota. */
        suspend fun checkKey(http: HttpTransport, key: String): KeyCheck {
            val r = http.get("${GeminiProtocol.BASE}/models?pageSize=1000", mapOf("x-goog-api-key" to key))
            val low = r.body.lowercase()
            return when {
                r.status == 200 -> KeyCheck.Ok(GeminiProtocol.parseModels(r.body))
                r.status == 0 -> KeyCheck.Problem("No internet connection")
                "location is not supported" in low -> KeyCheck.Problem("Gemini is not available in your region")
                r.status in setOf(400, 401, 403) -> KeyCheck.Invalid
                r.status == 429 -> KeyCheck.Problem("Too many requests right now; try again in a minute")
                else -> KeyCheck.Problem("Google answered HTTP ${r.status}")
            }
        }
    }
}

internal fun JsonElement.str(): String? = (this as? JsonPrimitive)?.contentOrNull
