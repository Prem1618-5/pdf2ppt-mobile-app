package app.papertoslides.core.scheduler

import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.keypool.Outcome
import app.papertoslides.core.keypool.Pick
import app.papertoslides.core.model.PageLayout
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** One request attempt's result. `pages` is keyed by page index (not by position in the slice). */
data class ApiResult(
    val status: Int,
    val body: String = "",
    val pages: Map<Int, PageLayout> = emptyMap(),
    /** 200 but the output could not be read (truncated / blocked JSON). */
    val unreadable: Boolean = false,
)

/** ONE attempt with one key and one model; key and model rotation live in the scheduler. */
fun interface GeminiApi {
    suspend fun layout(key: String, model: String, pages: List<Int>): ApiResult
}

class Slice(val pages: List<Int>, var tries: Int = 0)

/** Live progress of the T2 stage, for the UI. */
fun interface SchedulerListener { fun onEvent(event: String, pagesDone: Int) }

/**
 * Parallel key scheduler (context §5.2): one coroutine per key, one request in flight per key, a shared queue,
 * work stealing, results stored by page index. Never loses a slice and never hangs: every slice is either done
 * or returned as a leftover for the next tier.
 */
class SliceScheduler(
    private val lanes: KeyLanes,
    private val api: GeminiApi,
    private val store: suspend (page: Int, layout: PageLayout) -> Unit,
    private val awaitNetwork: suspend () -> Boolean = { true },
    private val listener: SchedulerListener = SchedulerListener { _, _ -> },
    private val maxTries: Int = 3,
) {
    private val sent = AtomicInteger(0)
    val requestsSent get() = sent.get()

    suspend fun run(slices: List<Slice>): List<Slice> = coroutineScope {
        val queue = Channel<Slice>(Channel.UNLIMITED)
        val pending = AtomicInteger(slices.size)
        val failed = ConcurrentLinkedQueue<Slice>()
        val done = AtomicInteger(0)
        slices.forEach { queue.trySend(it) }
        if (slices.isEmpty()) queue.close()
        val finished = CompletableDeferred<Unit>()
        fun settled() { if (pending.decrementAndGet() == 0) { queue.close(); finished.complete(Unit) } }
        fun requeue(s: Slice) { queue.trySend(s) }

        lanes.liveKeys().map { key ->
            launch {
                while (isActive) {
                    if (queue.isClosedForReceive) return@launch
                    when (val pick = lanes.pickFor(key)) {
                        is Pick.Dead -> return@launch
                        // sleep until this key may send again, or until all work is done
                        is Pick.Wait -> withTimeoutOrNull(pick.ms.coerceIn(200, 30_000)) { finished.await() }
                        is Pick.Go -> {
                            val s = queue.receiveCatching().getOrNull() ?: return@launch
                            if (!awaitNetwork()) { requeue(s); return@launch }
                            lanes.markSent(key, pick.model)
                            sent.incrementAndGet()
                            val r = api.layout(key, pick.model, s.pages)
                            when (lanes.report(key, pick.model, r.status, r.body)) {
                                Outcome.OK -> if (r.unreadable) {
                                    onError(s, failed, ::requeue, pending)?.let { settled() }
                                } else {
                                    val redo = mutableListOf<Int>()
                                    for (p in s.pages) {
                                        val lay = r.pages[p]
                                        when {
                                            lay == null -> redo += p // model skipped a page: re-run it alone
                                            s.pages.size > 1 && looksIncomplete(lay) -> redo += p
                                            else -> { store(p, lay); done.incrementAndGet() }
                                        }
                                    }
                                    if (redo.isNotEmpty()) {
                                        if (s.pages.size == 1) {
                                            onError(s, failed, ::requeue, pending)?.let { settled() }
                                        } else {
                                            pending.addAndGet(redo.size)
                                            redo.forEach { requeue(Slice(listOf(it), s.tries)) }
                                            settled()
                                        }
                                    } else settled()
                                    listener.onEvent("ok", done.get())
                                }
                                Outcome.ERROR -> onError(s, failed, ::requeue, pending)?.let { settled() }
                                else -> {
                                    // not the slice's fault: another key (or this one later) takes it
                                    requeue(s)
                                    listener.onEvent("retry", done.get())
                                }
                            }
                        }
                    }
                }
            }
        }.joinAll()

        val left = mutableListOf<Slice>().apply { addAll(failed) }
        while (true) left += queue.tryReceive().getOrNull() ?: break
        queue.close()
        left
    }

    /**
     * Per-slice recovery ladder: retry -> split a multi-page slice into single pages -> give up (next tier).
     * Returns Unit when the slice is settled (given up or replaced), null when it was re-queued as is.
     */
    private fun onError(s: Slice, failed: ConcurrentLinkedQueue<Slice>, requeue: (Slice) -> Unit, pending: AtomicInteger): Unit? {
        s.tries++
        if (s.tries < maxTries) { requeue(s); return null }
        if (s.pages.size > 1) {
            pending.addAndGet(s.pages.size)
            s.pages.forEach { requeue(Slice(listOf(it), 0)) }
            return Unit
        }
        failed += s
        return Unit
    }

    companion object {
        /** A page full of questions that came back with <= 1 question inside a batch is re-run alone. */
        fun looksIncomplete(p: PageLayout) =
            p.pageKind in setOf("questions", "mixed") && p.questionCount <= 1

        /**
         * Slices of consecutive pages. Starts at [slicePages] (owner default 2); grows up to 4 when the known remaining
         * daily budget is smaller than the number of slices needed.
         */
        fun plan(pages: List<Int>, slicePages: Int = 2, budget: Int? = null): List<Slice> {
            var size = slicePages.coerceIn(1, 4)
            if (budget != null && budget > 0) {
                while (size < 4 && (pages.size + size - 1) / size > budget) size++
            }
            // consecutive runs only, so continuations stay together
            val runs = mutableListOf<MutableList<Int>>()
            for (p in pages.sorted()) {
                if (runs.isNotEmpty() && runs.last().last() == p - 1) runs.last() += p else runs += mutableListOf(p)
            }
            return runs.flatMap { run -> run.chunked(size).map { Slice(it) } }
        }
    }
}
