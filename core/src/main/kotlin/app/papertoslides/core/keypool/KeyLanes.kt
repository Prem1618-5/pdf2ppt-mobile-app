package app.papertoslides.core.keypool

import kotlinx.serialization.Serializable
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

fun interface Clock { fun nowMs(): Long }

object SystemClock : Clock { override fun nowMs() = System.currentTimeMillis() }

sealed interface Pick {
    data class Go(val model: String) : Pick
    data class Wait(val ms: Long) : Pick
    data object Dead : Pick
}

enum class Outcome { OK, INVALID, EXHAUSTED, COOLING, BUSY, MISSING, LOCATION, ERROR }

enum class LaneKind { OK, COOLING, EXHAUSTED }

@Serializable
data class LaneState(var kind: LaneKind = LaneKind.OK, var until: Long = 0)

/** Health of one key, stored under a hash of the key (never the key itself). */
@Serializable
data class KeyHealth(
    var invalid: Boolean = false,
    val lanes: MutableMap<String, LaneState> = mutableMapOf(),
    var calls: Int = 0,
    var error: String? = null,
)

/** Shared across jobs so a key that ran dry is skipped next time (legacy module-level registry). */
@Serializable
class HealthRegistry(val keys: MutableMap<String, KeyHealth> = mutableMapOf()) {
    @Synchronized fun of(hash: String) = keys.getOrPut(hash) { KeyHealth() }
}

@Serializable
data class KeyStat(val masked: String, val state: String, val calls: Int, val error: String?, val untilMs: Long?)

/**
 * Port of legacy `keypool.py` for one-worker-per-key scheduling. A lane is (key, model) with state
 * ok / cooling / exhausted; a key may be invalid; a model may be missing (404) or busy (5xx) for everyone.
 */
class KeyLanes(
    keys: List<String>,
    models: List<String>,
    rpmPerKey: Int = 8,
    private val clock: Clock = SystemClock,
    private val registry: HealthRegistry = HealthRegistry(),
) {
    private val keys = keys.filter { it.isNotEmpty() }.distinct().toMutableList()
    val models = models.toList()
    private val rpm = maxOf(1, rpmPerKey)
    private val recent = HashMap<String, ArrayDeque<Long>>()
    private val modelBusyUntil = HashMap<String, Long>()
    private val modelMissing = HashSet<String>()
    private val removed = HashSet<String>()
    @Volatile var locationBlocked = false
        private set

    @Synchronized fun liveKeys(): List<String> = keys.filter { it !in removed && !rec(it).invalid }

    /** B13: the user deleted a key while a job runs; its worker stops at the next pick. */
    @Synchronized fun removeKey(key: String) { removed += key }

    private fun rec(key: String) = registry.of(khash(key))
    private fun lane(key: String, model: String) = rec(key).lanes.getOrPut(model) { LaneState() }

    /** Best model THIS key may use now, a wait, or dead. Does not count a request. */
    @Synchronized fun pickFor(key: String, betterOnly: Boolean = false): Pick {
        if (locationBlocked || key in removed || rec(key).invalid) return Pick.Dead
        val now = clock.nowMs()
        var soonest: Long? = null
        val window = recent.getOrPut(key) { ArrayDeque() }
        while (window.isNotEmpty() && now - window.first() >= 60_000) window.removeFirst()
        for (model in models) {
            if (model in modelMissing || (betterOnly && "lite" in model)) continue
            val l = lane(key, model)
            if (l.kind == LaneKind.EXHAUSTED && l.until > now) continue // dead for today
            var wait = maxOf(l.until - now, (modelBusyUntil[model] ?: 0) - now, 0)
            if (window.size >= rpm) wait = maxOf(wait, 60_000 - (now - window.first()))
            if (wait <= 0) return Pick.Go(model)
            soonest = if (soonest == null) wait else minOf(soonest, wait)
        }
        return if (soonest == null) Pick.Dead else Pick.Wait(soonest)
    }

    /** The request is being sent now: it counts against this key's per-minute window. */
    @Synchronized fun markSent(key: String, model: String) {
        recent.getOrPut(key) { ArrayDeque() }.addLast(clock.nowMs())
        rec(key).calls++
    }

    /** Feed back the HTTP outcome of one request (legacy `report`). status 0 = network failure. */
    @Synchronized fun report(key: String, model: String, status: Int, body: String = ""): Outcome {
        val now = clock.nowMs()
        val r = rec(key)
        val l = lane(key, model)
        val low = body.lowercase()
        if (status == 200) { l.kind = LaneKind.OK; l.until = 0; r.error = null; return Outcome.OK }
        if (status == 400 && "location is not supported" in low) {
            locationBlocked = true
            r.error = "Gemini is not available in your region"
            return Outcome.LOCATION
        }
        if (status in setOf(400, 401, 403) && ("api key" in low || "api_key" in low || "permission" in low || "unauthenticated" in low)) {
            r.invalid = true; r.error = "invalid key"; return Outcome.INVALID
        }
        if (status == 404) { modelMissing += model; return Outcome.MISSING }
        if (status == 429) {
            val retry = parseRetry(body)
            val daily = "perday" in low.replace("_", "").replace(" ", "") || "limit: 0" in low || (retry != null && retry > 600)
            if (daily) {
                l.kind = LaneKind.EXHAUSTED
                l.until = if (retry != null && retry > 600) now + (retry * 1000).toLong() else nextReset(now)
                r.error = "daily quota used on $model"
                return Outcome.EXHAUSTED
            }
            l.kind = LaneKind.COOLING
            l.until = now + ((retry ?: 20.0) * 1000).toLong() + 1000
            r.error = "rate limited (per minute)"
            return Outcome.COOLING
        }
        if (status == 0 || status in 500..504) { modelBusyUntil[model] = now + 20_000; return Outcome.BUSY }
        l.kind = LaneKind.COOLING; l.until = now + 10_000
        return Outcome.ERROR
    }

    @Synchronized fun stats(): List<KeyStat> {
        val now = clock.nowMs()
        return keys.map { k ->
            val r = rec(k)
            val lanes = models.filter { it !in modelMissing }.map { r.lanes[it] ?: LaneState() }
            val exhausted = lanes.isNotEmpty() && lanes.all { it.kind == LaneKind.EXHAUSTED && it.until > now }
            val state = when {
                k in removed -> "removed"
                r.invalid -> "invalid"
                exhausted -> "exhausted"
                lanes.any { it.kind == LaneKind.OK || it.until <= now } -> "ok"
                else -> "cooling"
            }
            val until = when (state) {
                "exhausted" -> lanes.minOf { it.until }
                "cooling" -> lanes.minOf { it.until }
                else -> null
            }
            KeyStat(mask(k), state, r.calls, r.error, until)
        }
    }

    companion object {
        private val PT: ZoneId = ZoneId.of("America/Los_Angeles")
        private val RETRY_DELAY = Regex(""""retryDelay"\s*:\s*"(\d+(?:\.\d+)?)s"""")
        private val RETRY_IN = Regex("""retry in\s+(?:(\d+)h)?\s*(?:(\d+)m)?\s*(?:(\d+(?:\.\d+)?)s)?""")
        private val SPLIT = Regex("""[\s,;]+""")

        fun mask(key: String) = if (key.isNotEmpty()) "…" + key.takeLast(4) else "…"

        fun khash(key: String): String =
            MessageDigest.getInstance("SHA-256").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }.take(16)

        /** Free-tier daily quotas reset at midnight Pacific time. */
        fun nextReset(nowMs: Long): Long {
            val now = ZonedDateTime.ofInstant(Instant.ofEpochMilli(nowMs), PT)
            return now.plusDays(1).toLocalDate().atStartOfDay(PT).plusSeconds(5).toInstant().toEpochMilli()
        }

        /** Seconds from Google's hints: `"retryDelay": "37s"` or `retry in 16h41m51.3s`. */
        fun parseRetry(msg: String): Double? {
            RETRY_DELAY.find(msg)?.let { return it.groupValues[1].toDouble() }
            val m = RETRY_IN.find(msg) ?: return null
            val (h, mi, s) = m.destructured
            if (h.isEmpty() && mi.isEmpty() && s.isEmpty()) return null
            return (h.toDoubleOrNull() ?: 0.0) * 3600 + (mi.toDoubleOrNull() ?: 0.0) * 60 + (s.toDoubleOrNull() ?: 0.0)
        }

        /** Pasted keys: spaces, quotes, commas, duplicates, newlines -> distinct keys of length >= 20. */
        fun splitKeys(text: String?): List<String> {
            val out = mutableListOf<String>()
            for (raw in (text ?: "").split(SPLIT)) {
                val k = raw.trim().trim('"', '\'')
                if (k.length >= 20 && k !in out) out += k
            }
            return out
        }
    }
}
