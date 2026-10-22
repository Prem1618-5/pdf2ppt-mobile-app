package app.papertoslides.core

import app.papertoslides.core.gemini.GeminiClient
import app.papertoslides.core.gemini.GeminiProtocol
import app.papertoslides.core.gemini.HttpResponse
import app.papertoslides.core.gemini.HttpTransport
import app.papertoslides.core.gemini.KeyCheck
import app.papertoslides.core.gemini.LayoutCache
import app.papertoslides.core.gemini.ModelInfo
import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.scheduler.SliceScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class GeminiTest {
    private val key = "AIzaFAKE_TEST_KEY_0000000000000000001"

    /** Records every request; answers from a script. */
    class FakeHttp(val answer: (url: String, body: String) -> HttpResponse) : HttpTransport {
        val log = mutableListOf<Triple<String, Map<String, String>, String>>()
        override suspend fun post(url: String, headers: Map<String, String>, json: String) = synchronized(log) { log += Triple(url, headers, json) }.let { answer(url, json) }
        override suspend fun get(url: String, headers: Map<String, String>) = synchronized(log) { log += Triple(url, headers, "") }.let { answer(url, "") }
    }

    private fun reply(pages: Int, perPage: Int = 3): String {
        val inner = buildJsonObject {
            put("pages", buildJsonArray {
                for (k in 1..pages) add(buildJsonObject {
                    put("page", k); put("page_kind", "questions"); put("dark_background", false); put("watermark_present", false)
                    put("fragments", buildJsonArray {
                        for (q in 0 until perPage) add(buildJsonObject {
                            put("order", q); put("role", "question"); put("number", k * 10 + q); put("label", "${k * 10 + q}.")
                            put("box", buildJsonArray { listOf(100 * q, 50, 100 * q + 90, 950).forEach { add(JsonPrimitive(it)) } })
                            put("continues_previous", false); put("continues_on_next", false); put("lang", "en")
                            put("has_figure", false); put("has_math", false); put("has_table", false); put("confidence", 0.9)
                        })
                    })
                })
            })
        }.toString()
        return buildJsonObject {
            put("candidates", buildJsonArray { add(buildJsonObject { put("content", buildJsonObject { put("parts", buildJsonArray { add(buildJsonObject { put("text", inner) }) }) }) }) })
        }.toString()
    }

    @Test fun `key travels only in the header, never in the URL or body`() = runBlocking {
        val http = FakeHttp { _, _ -> HttpResponse(200, reply(2)) }
        val r = GeminiClient(http, { p -> byteArrayOf(p.toByte(), 1, 2) }).layout(key, "gemini-x-flash", listOf(4, 5))
        assertEquals(200, r.status)
        val (url, headers, body) = http.log.single()
        assertEquals("https://generativelanguage.googleapis.com/v1beta/models/gemini-x-flash:generateContent", url)
        assertFalse(key in url); assertFalse(key in body)
        assertEquals(key, headers["x-goog-api-key"])
        // PAGE k -> page index, model stamped
        assertEquals(setOf(4, 5), r.pages.keys)
        assertEquals(20, r.pages[5]!!.fragments[0].number) // page index 5 was sent as PAGE 2
        assertEquals("gemini-x-flash", r.pages[4]!!.model)
        assertTrue(body.contains("\"PAGE 1\"") && body.contains("\"PAGE 2\"") && body.contains("responseSchema"))
    }

    @Test fun `400 thinking is retried without thinkingConfig once`() = runBlocking {
        val http = FakeHttp { _, body -> if ("thinkingConfig" in body) HttpResponse(400, "thinking is not supported") else HttpResponse(200, reply(1)) }
        val c = GeminiClient(http, { byteArrayOf(1) })
        assertEquals(200, c.layout(key, "m", listOf(0)).status)
        assertEquals(2, http.log.size)
        assertEquals(200, c.layout(key, "m", listOf(1)).status)
        assertEquals(3, http.log.size) // remembered per model
    }

    @Test fun `truncated JSON is unreadable, not a crash`() = runBlocking {
        val http = FakeHttp { _, _ -> HttpResponse(200, reply(1).dropLast(40)) }
        val r = GeminiClient(http, { byteArrayOf(1) }).layout(key, "m", listOf(0))
        assertTrue(r.unreadable)
    }

    @Test fun `client and scheduler together, all keys, cache`() = runBlocking {
        val keys = (1..3).map { "AIzaFAKE_TEST_KEY_00000000000000000$it" }
        val http = FakeHttp { _, body -> HttpResponse(200, reply(if (body.contains("PAGE 2")) 2 else 1)) }
        val lanes = KeyLanes(keys, listOf("m-flash"), 8)
        val dir = File(Fixtures.outDir, "cache").apply { deleteRecursively() }
        val cache = LayoutCache(dir)
        val got = ConcurrentHashMap<Int, PageLayout>()
        val jpeg = { p: Int -> "page$p".encodeToByteArray() }
        val left = SliceScheduler(lanes, GeminiClient(http, { jpeg(it) }), { p, l -> got[p] = l; cache.put(jpeg(p), l) }).run(SliceScheduler.plan((0 until 7).toList(), 2))
        assertTrue(left.isEmpty()); assertEquals(7, got.size)
        assertEquals(4, http.log.size)
        assertEquals(got[3], cache.get(jpeg(3)))
        assertNull(cache.get("other".encodeToByteArray()))
        // keys never reach the URLs
        assertTrue(http.log.none { (u, _, b) -> keys.any { it in u || it in b } })
    }

    @Test fun `lite cache entries are soft`() {
        val cache = LayoutCache(File(Fixtures.outDir, "cache2").apply { deleteRecursively() })
        cache.put("a".encodeToByteArray(), PageLayout(model = "gemini-z-flash-lite"))
        assertNull(cache.get("a".encodeToByteArray(), acceptSoft = false))
        assertEquals("gemini-z-flash-lite", cache.get("a".encodeToByteArray())!!.model)
    }

    @Test fun `key check maps statuses to calm messages`() = runBlocking {
        suspend fun check(status: Int, body: String) = GeminiClient.checkKey(FakeHttp { _, _ -> HttpResponse(status, body) }, key)
        val ok = check(200, """{"models":[{"name":"models/gemini-9-flash","outputTokenLimit":65536,"supportedGenerationMethods":["generateContent"]}]}""")
        assertTrue(ok is KeyCheck.Ok && ok.models.single().name == "gemini-9-flash")
        assertEquals(KeyCheck.Invalid, check(400, "API key not valid"))
        assertTrue(check(400, "User location is not supported for the API use.") is KeyCheck.Problem)
        assertTrue(check(0, "") is KeyCheck.Problem)
    }

    @Test fun `model choice keeps user order, else newest flash first and lite last`() {
        val avail = listOf("gemini-2.5-flash", "gemini-3.1-flash-lite", "gemini-3.5-flash", "gemini-3.5-pro", "gemini-3.5-flash-image", "text-embedding-004")
            .map { ModelInfo(it, 8192, true) }
        assertEquals(listOf("gemini-3.5-flash", "gemini-2.5-flash", "gemini-3.1-flash-lite"), GeminiProtocol.chooseModels(emptyList(), avail))
        assertEquals(listOf("gemini-2.5-flash"), GeminiProtocol.chooseModels(listOf("nope", "gemini-2.5-flash"), avail))
    }

    @Test fun `schema matches the legacy required fields`() {
        val s = Json.parseToJsonElement(GeminiProtocol.SCHEMA.toString()).jsonObject
        assertTrue(s.toString().contains("\"continues_on_next\"") && s.toString().contains("\"page_kind\""))
        assertTrue(GeminiProtocol.PROMPT.startsWith("You are a document layout analyser"))
    }
}
