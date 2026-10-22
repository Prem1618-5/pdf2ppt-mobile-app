package app.papertoslides.core

import app.papertoslides.core.GeminiTest.FakeHttp
import app.papertoslides.core.gemini.GeminiClient
import app.papertoslides.core.gemini.HttpResponse
import app.papertoslides.core.gemini.KeyCheck
import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.provider.KeyToken
import app.papertoslides.core.provider.OpenAiClient
import app.papertoslides.core.provider.OpenAiProtocol
import app.papertoslides.core.provider.Providers
import app.papertoslides.core.provider.RoutingApi
import app.papertoslides.core.scheduler.SliceScheduler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

class ProviderTest {
    private val groq = KeyToken.make("groq", "gsk_FAKE_TEST_KEY_000000000000000000000001")

    /** A chat-completions reply carrying [pages] pages of 2 questions, optionally in fences and with float boxes. */
    private fun reply(pages: Int, fenced: Boolean = true): String {
        val inner = """{"pages":[""" + (1..pages).joinToString(",") { k ->
            """{"page":$k,"page_kind":"questions","dark_background":false,"watermark_present":false,"fragments":[""" +
                (0..1).joinToString(",") { q -> """{"order":$q,"role":"question","number":${k * 10 + q},"label":"${k * 10 + q}.","box":[${q * 400}.0,50,${q * 400 + 380},950.0],"continues_previous":false,"continues_on_next":false,"lang":"en","has_figure":false,"has_math":false,"has_table":false,"confidence":0.8}""" } + "]}"
        } + "]}"
        val content = if (fenced) "Here you go:\n```json\n$inner\n```" else inner
        return buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject { put("message", buildJsonObject { put("role", "assistant"); put("content", content) }) }) }) }.toString()
    }

    @Test fun `tokens keep Gemini keys bare and tag the others`() {
        assertEquals("AIzaX", KeyToken.make("gemini", "AIzaX"))
        assertEquals("groq|k", KeyToken.make("groq", "k"))
        assertEquals("groq", KeyToken.providerId(groq)); assertEquals("gemini", KeyToken.providerId("AIzaX"))
        assertEquals("k", KeyToken.secret("nvidia|k")); assertEquals("AIzaX", KeyToken.secret("AIzaX"))
    }

    @Test fun `request uses Bearer header, image parts and never puts the key in URL or body`() = runBlocking {
        val http = FakeHttp { _, _ -> HttpResponse(200, reply(2)) }
        val r = OpenAiClient(http, { byteArrayOf(1, 2) }).layout(groq, "meta-llama/llama-4-scout-17b-16e-instruct", listOf(4, 5))
        assertEquals(200, r.status); assertEquals(setOf(4, 5), r.pages.keys)
        assertEquals("meta-llama/llama-4-scout-17b-16e-instruct", r.pages[4]!!.model)
        assertEquals(listOf(10, 11), r.pages[4]!!.fragments.map { it.number })
        assertEquals(listOf(0, 50, 380, 950).size, r.pages[4]!!.fragments[0].box.size)
        val (url, headers, body) = http.log.single()
        assertEquals("https://api.groq.com/openai/v1/chat/completions", url)
        assertEquals("Bearer gsk_FAKE_TEST_KEY_000000000000000000000001", headers["Authorization"])
        assertTrue("data:image/jpeg;base64," in body && "PAGE 2" in body)
        assertTrue("gsk_FAKE" !in url && "gsk_FAKE" !in body)
    }

    @Test fun `a slice bigger than the image limit is split and merged (NVIDIA takes one image)`() = runBlocking {
        val http = FakeHttp { _, _ -> HttpResponse(200, reply(1, fenced = false)) }
        val key = KeyToken.make("nvidia", "nvapi-FAKE_TEST_KEY_00000000000000000000000000000001")
        val r = OpenAiClient(http, { byteArrayOf(1) }).layout(key, "meta/llama-3.2-90b-vision-instruct", listOf(7, 8))
        assertEquals(2, http.log.size); assertEquals(setOf(7, 8), r.pages.keys)
        assertTrue(http.log.all { it.first.startsWith("https://integrate.api.nvidia.com/v1/") })
    }

    @Test fun `json mode refused once is dropped for that model`() = runBlocking {
        val http = FakeHttp { _, body -> if ("response_format" in body) HttpResponse(400, """{"error":"response_format not supported"}""") else HttpResponse(200, reply(1)) }
        val c = OpenAiClient(http, { byteArrayOf(1) })
        assertEquals(200, c.layout(groq, "m", listOf(0)).status)
        c.layout(groq, "m", listOf(1))
        assertEquals(3, http.log.size) // first: try + retry; second: straight without response_format
    }

    @Test fun `unreadable and error replies are reported, not thrown`() = runBlocking {
        assertTrue(OpenAiClient(FakeHttp { _, _ -> HttpResponse(200, reply(1).dropLast(60)) }, { byteArrayOf(1) }).layout(groq, "m", listOf(0)).unreadable)
        assertEquals(429, OpenAiClient(FakeHttp { _, _ -> HttpResponse(429, "x") }, { byteArrayOf(1) }).layout(groq, "m", listOf(0)).status)
        assertNull(OpenAiProtocol.parseResponse("not json"))
    }

    @Test fun `custom provider uses the user's base URL`() = runBlocking {
        val http = FakeHttp { _, _ -> HttpResponse(200, reply(1)) }
        val key = KeyToken.make("custom", "sk-FAKE_TEST_KEY_00000000000000001")
        OpenAiClient(http, { byteArrayOf(1) }) { p -> if (p.id == "custom") "https://api.example.com/v1/" else p.baseUrl }.layout(key, "m", listOf(0))
        assertEquals("https://api.example.com/v1/chat/completions", http.log.single().first)
    }

    @Test fun `key check maps statuses`() = runBlocking {
        suspend fun check(status: Int, body: String) = OpenAiClient.checkKey(FakeHttp { _, _ -> HttpResponse(status, body) }, Providers.GROQ, Providers.GROQ.baseUrl, "k")
        val (ok, ids) = check(200, """{"data":[{"id":"a/b"},{"id":"c"}]}""")
        assertTrue(ok is KeyCheck.Ok); assertEquals(listOf("a/b", "c"), ids)
        assertEquals(KeyCheck.Invalid, check(401, "").first)
        assertTrue(check(0, "").first is KeyCheck.Problem)
        assertTrue(OpenAiClient.checkKey(FakeHttp { _, _ -> HttpResponse(200, "") }, Providers.CUSTOM, "", "k").first is KeyCheck.Problem)
    }

    @Test fun `model choice - override, listed defaults, vision guess, unknown list`() {
        val p = Providers.GROQ
        assertEquals(listOf("mine"), Providers.chooseModels(p, listOf("mine"), listOf("x")))
        assertEquals(listOf(p.models[1]), Providers.chooseModels(p, emptyList(), listOf(p.models[1], "llama-3.1-8b")))
        assertEquals(listOf("llama-9-vision-x"), Providers.chooseModels(p, emptyList(), listOf("whisper", "llama-9-vision-x", "text-embedding-3")))
        assertEquals(p.models, Providers.chooseModels(p, emptyList(), emptyList()))
        assertEquals(listOf("my-vl"), Providers.chooseModels(Providers.CUSTOM, emptyList(), listOf("z"), " my-vl "))
    }

    @Test fun `hosts allowed are only the providers and the custom https host`() {
        val h = Providers.allowedHosts("https://api.example.com:8443/v1")
        assertTrue("api.groq.com" in h && "integrate.api.nvidia.com" in h && "api.example.com" in h && "generativelanguage.googleapis.com" in h)
        assertEquals(null, Providers.hostOf("http://api.example.com/v1")) // no cleartext
        assertEquals(null, Providers.hostOf("https://evil.com@api.groq.com/"))
    }

    @Test fun `groq style rate limit text drives the lanes`() {
        var now = 0L
        val lanes = KeyLanes(listOf(groq), listOf("g"), 8, { now })
        val daily = """{"error":{"message":"Rate limit reached for model in organization on tokens per day (TPD): Limit 500000. Please try again in 7m12.5s","code":"rate_limit_exceeded"}}"""
        assertEquals(app.papertoslides.core.keypool.Outcome.EXHAUSTED, lanes.report(groq, "g", 429, daily))
        assertEquals(app.papertoslides.core.keypool.Outcome.INVALID, KeyLanes(listOf(groq), listOf("g"), 8, { now }).report(groq, "g", 401, """{"error":"Invalid API Key"}"""))
        assertEquals(432.5, KeyLanes.parseRetry(daily)!!, 0.01)
    }

    @Test fun `mixed keys each use their own provider's models and client`() = runBlocking {
        val gem = "AIzaFAKE_TEST_KEY_0000000000000000001"
        val http = FakeHttp { url, _ ->
            if ("groq" in url) HttpResponse(200, reply(1)) else HttpResponse(500, "down")
        }
        val models = mapOf("groq" to listOf("g-model"), "gemini" to listOf("gem-model"))
        val lanes = KeyLanes(listOf(gem, groq), models.values.flatten(), 8, { 0L }) { k -> models[KeyToken.providerId(k)].orEmpty() }
        val jpeg = { _: Int -> byteArrayOf(1) }
        val got = ConcurrentHashMap<Int, app.papertoslides.core.model.PageLayout>()
        val api = RoutingApi(GeminiClient(http, { jpeg(it) }), OpenAiClient(http, { jpeg(it) }))
        val left = SliceScheduler(lanes, api, { p, l -> got[p] = l }).run(SliceScheduler.plan(listOf(0, 1, 2), 1))
        assertTrue(left.isEmpty()); assertEquals(setOf(0, 1, 2), got.keys)
        assertTrue(http.log.any { "groq" in it.first })
    }
}
