package app.papertoslides.core

import app.papertoslides.core.keypool.KeyLanes
import app.papertoslides.core.keypool.Outcome
import app.papertoslides.core.keypool.Pick
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class KeyLanesTest {
    private var now = 1_800_000_000_000L
    private val k1 = "AIzaFAKEKEY_one_000000000001"
    private val k2 = "AIzaFAKEKEY_two_000000000002"
    private fun lanes(rpm: Int = 8) = KeyLanes(listOf(k1, k2), listOf("m-flash", "m-flash-lite"), rpm, { now })

    @Test fun `200 ok`() { assertEquals(Outcome.OK, lanes().report(k1, "m-flash", 200, "")) }

    @Test fun `B2 invalid key is skipped`() {
        val l = lanes()
        assertEquals(Outcome.INVALID, l.report(k1, "m-flash", 400, """{"error":{"message":"API key not valid. API_KEY_INVALID"}}"""))
        assertEquals(Pick.Dead, l.pickFor(k1))
        assertEquals(listOf(k2), l.liveKeys())
    }

    @Test fun `B4 per-minute 429 cools only that lane`() {
        val l = lanes()
        assertEquals(Outcome.COOLING, l.report(k1, "m-flash", 429, """"retryDelay": "37s""""))
        assertEquals(Pick.Go("m-flash-lite"), l.pickFor(k1))
        assertEquals(Pick.Go("m-flash"), l.pickFor(k2))
        now += 39_000
        assertEquals(Pick.Go("m-flash"), l.pickFor(k1))
    }

    @Test fun `B5 per-day 429 retires lane until midnight Pacific`() {
        val l = lanes()
        assertEquals(Outcome.EXHAUSTED, l.report(k1, "m-flash", 429, "Quota exceeded GenerateRequestsPerDayPerProjectPerModel-FreeTier"))
        assertEquals(Outcome.EXHAUSTED, l.report(k1, "m-flash-lite", 429, "limit: 0"))
        assertEquals(Pick.Dead, l.pickFor(k1))
        val st = l.stats().first()
        assertEquals("exhausted", st.state)
        val reset = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(st.untilMs!!), ZoneId.of("America/Los_Angeles"))
        assertEquals(0, reset.hour); assertEquals(0, reset.minute)
        assertEquals(Outcome.EXHAUSTED, lanes().report(k2, "m-flash", 429, "Please retry in 16h41m51.3s"))
    }

    @Test fun `B6 503 makes the model busy for every key`() {
        val l = lanes()
        assertEquals(Outcome.BUSY, l.report(k1, "m-flash", 503, "high demand"))
        assertEquals(Pick.Go("m-flash-lite"), l.pickFor(k2))
        now += 21_000
        assertEquals(Pick.Go("m-flash"), l.pickFor(k2))
    }

    @Test fun `B11 404 marks the model missing`() {
        val l = lanes()
        assertEquals(Outcome.MISSING, l.report(k1, "m-flash", 404, ""))
        assertEquals(Pick.Go("m-flash-lite"), l.pickFor(k2))
    }

    @Test fun `B12 location not supported is not an invalid key`() {
        val l = lanes()
        assertEquals(Outcome.LOCATION, l.report(k1, "m-flash", 400, "User location is not supported for the API use."))
        assertTrue(l.stats().none { it.state == "invalid" })
        assertEquals(Pick.Dead, l.pickFor(k2))
    }

    @Test fun `sliding rpm window makes the key wait`() {
        val l = lanes(rpm = 2)
        l.markSent(k1, "m-flash"); now += 1000; l.markSent(k1, "m-flash")
        val p = l.pickFor(k1)
        assertTrue(p is Pick.Wait && p.ms in 58_000..59_000)
        now += 59_001
        assertTrue(l.pickFor(k1) is Pick.Go)
    }

    @Test fun `parseRetry both formats`() {
        assertEquals(37.0, KeyLanes.parseRetry("""{"retryDelay": "37s"}""")!!, 0.0)
        assertEquals(16 * 3600 + 41 * 60 + 51.3, KeyLanes.parseRetry("Please retry in 16h41m51.3s")!!, 0.01)
        assertEquals(null, KeyLanes.parseRetry("nothing"))
    }

    @Test fun `B10 split keys`() {
        val raw = " \"AIzaFAKEKEY_one_000000000001\", 'AIzaFAKEKEY_two_000000000002'\nAIzaFAKEKEY_one_000000000001; short\n"
        assertEquals(listOf(k1, k2), KeyLanes.splitKeys(raw))
    }

    @Test fun `mask and hash never expose the key`() {
        assertEquals("…0001", KeyLanes.mask(k1))
        assertEquals(16, KeyLanes.khash(k1).length)
        assertTrue(k1 !in KeyLanes.khash(k1))
    }
}
