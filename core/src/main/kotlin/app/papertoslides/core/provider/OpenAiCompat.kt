package app.papertoslides.core.provider

import app.papertoslides.core.gemini.GeminiProtocol
import app.papertoslides.core.gemini.HttpResponse
import app.papertoslides.core.gemini.HttpTransport
import app.papertoslides.core.gemini.KeyCheck
import app.papertoslides.core.gemini.ModelInfo
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
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap

/** Chat-completions protocol (Groq, NVIDIA NIM, OpenRouter, Mistral, ...): same prompt, JSON described in text. */
object OpenAiProtocol {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /** These APIs have no response schema, so the shape is spelled out after the shared prompt. */
    val FORMAT = """

Reply with ONLY one JSON object, no markdown and no commentary, in exactly this shape:
{"pages":[{"page":1,"page_kind":"questions|cover_or_instructions|answer_key|solutions|blank_or_ad|mixed","dark_background":false,"watermark_present":false,"fragments":[{"order":0,"role":"${Roles.ALL.joinToString("|")}","label":"7.","number":7,"continues_previous":false,"continues_on_next":false,"box":[ymin,xmin,ymax,xmax],"lang":"en|hi|bilingual|other","has_figure":false,"has_math":false,"has_table":false,"stem":"...","options":["..."],"confidence":0.9}]}]}
Use integers 0-1000 for box. Use null for label/number when there is none."""

    fun requestBody(model: String, jpegs: List<ByteArray>, maxTokens: Int, jsonMode: Boolean): String = buildJsonObject {
        put("model", model)
        put("temperature", 0)
        put("max_tokens", maxTokens)
        if (jsonMode) putJsonObject("response_format") { put("type", "json_object") }
        put("messages", buildJsonArray {
            add(buildJsonObject {
                put("role", "user")
                put("content", buildJsonArray {
                    jpegs.forEachIndexed { i, j ->
                        add(buildJsonObject { put("type", "text"); put("text", "PAGE ${i + 1}") })
                        add(buildJsonObject {
                            put("type", "image_url")
                            putJsonObject("image_url") { put("url", "data:image/jpeg;base64," + Base64.getEncoder().encodeToString(j)) }
                        })
                    }
                    add(buildJsonObject { put("type", "text"); put("text", GeminiProtocol.PROMPT.replace("{n}", jpegs.size.toString()) + FORMAT) })
                })
            })
        })
    }.toString()

    /** The JSON object inside a model reply that may be wrapped in ```json fences or prose. */
    fun extractJson(text: String): String? {
        val a = text.indexOf('{'); val b = text.lastIndexOf('}')
        return if (a in 0 until b) text.substring(a, b + 1) else null
    }

    /** Pages of a 200 response keyed by PAGE k (1-based), or null if unreadable (truncated / not JSON). */
    fun parseResponse(body: String): Map<Int, PageLayout>? = try {
        val root = json.parseToJsonElement(body).jsonObject
        val content = root["choices"]!!.jsonArray[0].jsonObject["message"]!!.jsonObject["content"]!!.let { c ->
            if (c is JsonPrimitive) c.content else c.jsonArray.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.content ?: "" }
        }
        val out = json.parseToJsonElement(extractJson(content)!!).jsonObject
        val byPage = LinkedHashMap<Int, PageLayout>()
        for (p in out["pages"]!!.jsonArray) {
            val lay = json.decodeFromJsonElement<PageLayout>(normalise(p))
            byPage.putIfAbsent(lay.page, lay)
        }
        byPage
    } catch (e: Exception) {
        null
    }

    /** Models write box numbers as 12.0 or "12": make them integers so decoding cannot fail on that. */
    private fun normalise(page: JsonElement): JsonElement {
        val o = page as? JsonObject ?: return page
        val frags = (o["fragments"] as? JsonArray)?.map { f ->
            val fo = f as? JsonObject ?: return@map f
            val box = (fo["box"] as? JsonArray)?.mapNotNull { v -> (v as? JsonPrimitive)?.content?.toDoubleOrNull() ?: (v as? JsonPrimitive)?.doubleOrNull }
            JsonObject(fo + ("box" to buildJsonArray { (box ?: emptyList()).forEach { add(JsonPrimitive(it.toInt())) } }))
        } ?: return page
        return JsonObject(o + ("fragments" to JsonArray(frags)))
    }

    fun parseModels(body: String): List<String> = try {
        json.parseToJsonElement(body).jsonObject["data"]!!.jsonArray.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.content }
    } catch (e: Exception) {
        emptyList()
    }
}

/**
 * One request attempt for a non-Gemini provider (scheduler contract). The key travels only in the Authorization
 * header. A slice bigger than the provider's image limit is sent in chunks and merged.
 */
class OpenAiClient(
    private val http: HttpTransport,
    private val jpegOf: suspend (page: Int) -> ByteArray,
    private val baseUrlOf: (Provider) -> String = { it.baseUrl },
) : GeminiApi {
    private val noJsonMode = ConcurrentHashMap.newKeySet<String>()

    override suspend fun layout(key: String, model: String, pages: List<Int>): ApiResult {
        val provider = KeyToken.provider(key)
        val secret = KeyToken.secret(key)
        val url = baseUrlOf(provider).trimEnd('/') + "/chat/completions"
        val headers = mapOf("Authorization" to "Bearer $secret", "Content-Type" to "application/json")
        val merged = LinkedHashMap<Int, PageLayout>()
        for (chunk in pages.chunked(provider.maxImages.coerceAtLeast(1))) {
            val jpegs = chunk.map { jpegOf(it) }
            val mode = model !in noJsonMode
            var r = http.post(url, headers, OpenAiProtocol.requestBody(model, jpegs, MAX_TOKENS, mode))
            if (r.status == 400 && mode && ("response_format" in r.body || "json" in r.body.lowercase()) && noJsonMode.add(model)) {
                r = http.post(url, headers, OpenAiProtocol.requestBody(model, jpegs, MAX_TOKENS, false))
            }
            if (r.status != 200) return ApiResult(r.status, r.body.take(4000))
            val parsed = OpenAiProtocol.parseResponse(r.body) ?: return ApiResult(200, "", unreadable = true)
            chunk.forEachIndexed { k, p -> parsed[k + 1]?.let { merged[p] = it.copy(page = p, model = model) } }
        }
        return ApiResult(200, "", merged)
    }

    companion object {
        const val MAX_TOKENS = 8000

        /** Free key check: `GET /models`, no generation quota. */
        suspend fun checkKey(http: HttpTransport, provider: Provider, baseUrl: String, secret: String): Pair<KeyCheck, List<String>> {
            if (baseUrl.isBlank()) return KeyCheck.Problem("Enter the base URL first") to emptyList()
            val r: HttpResponse = http.get(baseUrl.trimEnd('/') + "/models", mapOf("Authorization" to "Bearer $secret"))
            val ids = if (r.status == 200) OpenAiProtocol.parseModels(r.body) else emptyList()
            val check = when {
                r.status == 200 -> KeyCheck.Ok(ids.map { ModelInfo(it, null, true) })
                r.status == 0 -> KeyCheck.Problem("No internet connection")
                r.status in setOf(401, 403) -> KeyCheck.Invalid
                r.status == 429 -> KeyCheck.Problem("Too many requests right now; try again in a minute")
                r.status == 404 -> KeyCheck.Problem("${provider.label} did not recognise that address (check the base URL)")
                else -> KeyCheck.Problem("${provider.label} answered HTTP ${r.status}")
            }
            return check to ids
        }
    }
}

/** Sends each request to the client of the key's provider. */
class RoutingApi(private val gemini: GeminiApi, private val openai: GeminiApi) : GeminiApi {
    override suspend fun layout(key: String, model: String, pages: List<Int>): ApiResult =
        if (KeyToken.provider(key).style == ApiStyle.GEMINI) gemini.layout(key, model, pages) else openai.layout(key, model, pages)
}
