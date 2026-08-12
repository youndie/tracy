package ru.workinprogress.tracy.server.db

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Hand-rolled calendar arithmetic, so it gets its own tests against known values.
 *
 * This is not paranoia: the first version of the repository test assumed a timestamp was in 2026
 * when it was in 2025, and every failure it produced looked like a database problem. A wrong day
 * key does not fail loudly — it writes into a partition nobody reads.
 */
class PartitionsTest {
    @Test
    fun `known instants map to known days`() {
        assertEquals("19700101", dayKey(0))
        assertEquals("19700101", dayKey(86_399_999))
        assertEquals("19700102", dayKey(86_400_000))
        assertEquals("20240101", dayKey(1_704_067_200_000))
        assertEquals("20250801", dayKey(1_754_049_600_000))
        assertEquals("20260801", dayKey(1_785_542_400_000))
    }

    @Test
    fun `leap days are handled`() {
        assertEquals("20240229", dayKey(1_709_164_800_000))
        assertEquals("20240301", dayKey(1_709_251_200_000))
    }

    @Test
    fun `2100 is not a leap year`() {
        // The century rule is the part hand-rolled arithmetic usually gets wrong.
        assertEquals("21000101", dayKey(4_102_444_800_000))
    }

    @Test
    fun `a day boundary is exclusive at the end`() {
        val dayStart = 1_785_542_400_000
        assertEquals("20260801", dayKey(dayStart))
        assertEquals("20260801", dayKey(dayStart + 86_399_999))
        assertEquals("20260802", dayKey(dayStart + 86_400_000))
    }
}
