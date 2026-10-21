package app.papertoslides.core

import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.model.PageLayout
import app.papertoslides.core.model.RawFragment
import app.papertoslides.core.scheduler.ApiResult
import app.papertoslides.core.scheduler.GeminiApi
import app.papertoslides.core.scheduler.Slice
import app.papertoslides.core.scheduler.SliceScheduler
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.ConcurrentHashMap

@OptIn(ExperimentalCoroutinesApi::class)
class SliceSchedulerTest {
    private val keys = (1..4).map { "AIzaFAKEKEY_number_${it}_0000000000" }
    private fun page(p: Int) = PageLayout(page = p, fragments = List(3) { RawFragment(role = "question", number = p * 3 + it, box = listOf(0, 0, 100, 100)) })
    private fun ok(pages: List<Int>) = ApiResult(200, "", pages.associateWith { page(it) })

    private fun TestScope.lanes(n: Int = 4, models: List<String> = listOf("m1", "m2")) =
        KeyLanes(keys.take(n), models, 8, { currentTime })

    private fun TestScope.scheduler(lanes: KeyLanes, api: GeminiApi, store: MutableMap<Int, PageLayout>) =
        SliceScheduler(lanes, api, { p, l -> store[p] = l })

    @Test fun `B15 four keys finish 11 slices in about 3 rounds`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val s = scheduler(lanes(), { _, _, pages -> delay(2000); ok(pages) }, store)
        val left = s.run(SliceScheduler.plan((0 until 22).toList(), 2))
        assertTrue(left.isEmpty())
        assertEquals(22, store.size)
        assertTrue("took ${currentTime} ms", currentTime in 5_000..7_000)
        assertEquals(11, s.requestsSent)
    }

    @Test fun `B2 one invalid key, others carry on`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val api = GeminiApi { key, _, pages -> delay(1000); if (key == keys[0]) ApiResult(400, "API key not valid") else ok(pages) }
        val left = scheduler(lanes(), api, store).run(SliceScheduler.plan((0 until 8).toList()))
        assertTrue(left.isEmpty()); assertEquals(8, store.size)
    }

    @Test fun `B4 per-minute 429 slice is taken by another key`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        var first = true
        val api = GeminiApi { _, _, pages ->
            delay(500)
            if (first) { first = false; ApiResult(429, """"retryDelay": "30s"""") } else ok(pages)
        }
        val left = scheduler(lanes(2, listOf("m1")), api, store).run(SliceScheduler.plan((0 until 4).toList()))
        assertTrue(left.isEmpty()); assertEquals(4, store.size)
        assertTrue(currentTime < 10_000)
    }

    @Test fun `B6 503 moves to the next model`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val models = mutableListOf<String>()
        val api = GeminiApi { _, model, pages -> delay(100); models += model; if (model == "m1") ApiResult(503, "overloaded") else ok(pages) }
        val left = scheduler(lanes(1), api, store).run(SliceScheduler.plan(listOf(0, 1, 2, 3)))
        assertTrue(left.isEmpty()); assertEquals(4, store.size)
        assertEquals(listOf("m1", "m2", "m2"), models)
    }

    @Test fun `all keys dead returns leftovers and does not hang`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val api = GeminiApi { _, _, _ -> delay(100); ApiResult(429, "PerDay quota") }
        val left = scheduler(lanes(2), api, store).run(SliceScheduler.plan((0 until 6).toList()))
        assertEquals(listOf(0, 1, 2, 3, 4, 5), left.flatMap { it.pages }.sorted())
        assertTrue(store.isEmpty())
    }

    @Test fun `B8 unreadable JSON retries, splits, then hands the page to the next tier`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val api = GeminiApi { _, _, pages -> delay(100); if (1 in pages) ApiResult(200, "", unreadable = true) else ok(pages) }
        val left = scheduler(lanes(1), api, store).run(listOf(Slice(listOf(0, 1))))
        assertEquals(listOf(listOf(1)), left.map { it.pages })
        assertEquals(setOf(0), store.keys)
    }

    @Test fun `B9 skipped or incomplete page is re-run alone`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val calls = mutableListOf<List<Int>>()
        val api = GeminiApi { _, _, pages ->
            delay(100); calls += pages
            if (pages.size > 1) ApiResult(200, "", mapOf(pages[0] to page(pages[0]),
                pages[1] to PageLayout(pageKind = "questions", fragments = listOf(RawFragment(number = 1, box = listOf(0, 0, 9, 9))))))
            else ok(pages)
        }
        val left = scheduler(lanes(1), api, store).run(listOf(Slice(listOf(4, 5))))
        assertTrue(left.isEmpty())
        assertEquals(listOf(listOf(4, 5), listOf(5)), calls)
        assertEquals(3, store[5]!!.questionCount)
    }

    @Test fun `B13 key removed mid-run stops its worker, slices re-queued`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val l = lanes(2)
        val api = GeminiApi { _, _, pages -> delay(1000); ok(pages) }
        val job = launch { advanceTimeBy(1500); l.removeKey(keys[0]) }
        val left = scheduler(l, api, store).run(SliceScheduler.plan((0 until 12).toList()))
        job.join()
        assertTrue(left.isEmpty()); assertEquals(12, store.size)
    }

    @Test fun `cancel stops promptly and keeps completed pages`() = runTest {
        val store = ConcurrentHashMap<Int, PageLayout>()
        val api = GeminiApi { _, _, pages -> delay(1000); ok(pages) }
        val job = launch { scheduler(lanes(2), api, store).run(SliceScheduler.plan((0 until 20).toList())) }
        advanceTimeBy(1500)
        job.cancel(); job.join()
        assertEquals(4, store.size)
    }

    @Test fun `plan grows slices when budget is short and keeps runs consecutive`() {
        assertEquals(listOf(listOf(0, 1), listOf(2), listOf(5, 6)), SliceScheduler.plan(listOf(0, 1, 2, 5, 6)).map { it.pages })
        assertEquals(3, SliceScheduler.plan((0 until 12).toList(), 2, budget = 4).first().pages.size)
        assertEquals(4, SliceScheduler.plan((0 until 40).toList(), 2, budget = 2).first().pages.size)
    }
}
