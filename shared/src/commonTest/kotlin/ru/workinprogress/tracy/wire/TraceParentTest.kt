package ru.workinprogress.tracy.wire

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceParentTest {
    private val valid = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

    @Test
    fun `parses the example from the specification`() {
        val p = assertNotNull(TraceParent.parse(valid))

        assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", p.traceId)
        assertEquals("00f067aa0ba902b7", p.parentId)
        assertTrue(p.sampled)
    }

    @Test
    fun `round trips`() {
        assertEquals(valid, assertNotNull(TraceParent.parse(valid)).toHeader())
    }

    @Test
    fun `sampled flag is the least significant bit only`() {
        assertTrue(assertNotNull(TraceParent.parse(valid.dropLast(2) + "01")).sampled)
        assertTrue(assertNotNull(TraceParent.parse(valid.dropLast(2) + "03")).sampled)
        assertTrue(!assertNotNull(TraceParent.parse(valid.dropLast(2) + "00")).sampled)
        // Other bits are reserved and must not be read as sampled.
        assertTrue(!assertNotNull(TraceParent.parse(valid.dropLast(2) + "02")).sampled)
    }

    @Test
    fun `all zero identifiers are invalid`() {
        assertNull(TraceParent.parse("00-${"0".repeat(32)}-00f067aa0ba902b7-01"))
        assertNull(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-${"0".repeat(16)}-01"))
    }

    @Test
    fun `version ff is forbidden and unknown versions are not parsed`() {
        assertNull(TraceParent.parse("ff-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
        assertNull(TraceParent.parse("01-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
    }

    @Test
    fun `wrong widths are invalid`() {
        assertNull(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e473-00f067aa0ba902b7-01"))
        assertNull(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e47366-00f067aa0ba902b7-01"))
        assertNull(TraceParent.parse("00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b-01"))
    }

    @Test
    fun `uppercase hex is rejected by the specification`() {
        assertNull(TraceParent.parse("00-4BF92F3577B34DA6A3CE929D0E0E4736-00f067aa0ba902b7-01"))
    }

    @Test
    fun `malformed input is null rather than an exception`() {
        for (bad in listOf("", "-", "00", "garbage", "00-x-y-z", valid.replace('-', '_'))) {
            assertNull(TraceParent.parse(bad), "should not parse: $bad")
        }
        assertNull(TraceParent.parse(null))
    }

    @Test
    fun `trailing content is not accepted for version 00`() {
        assertNull(TraceParent.parse("$valid-extra"))
    }

    @Test
    fun `child header keeps the trace and replaces the parent`() {
        val p = assertNotNull(TraceParent.parse(valid))

        assertEquals(
            "00-4bf92f3577b34da6a3ce929d0e0e4736-aaaabbbbccccdddd-01",
            p.childHeader("aaaabbbbccccdddd"),
        )
    }

    @Test
    fun `child header can force the sampled bit downstream`() {
        val p = assertNotNull(TraceParent.parse(valid.dropLast(2) + "00"))

        assertTrue(p.childHeader("aaaabbbbccccdddd", sampled = true).endsWith("-01"))
    }

    @Test
    fun `generated identifiers are valid by our own parser`() {
        repeat(200) {
            val header = TraceParent.newTrace(sampled = true).toHeader()
            assertNotNull(TraceParent.parse(header), "generated an unparsable header: $header")
        }
    }

    @Test
    fun `generation never yields the forbidden all zero value`() {
        // Seeded so the assertion is about the guard, not about luck.
        val random = Random(1)
        repeat(500) {
            val p = TraceParent.newTrace(random = random)
            assertTrue(p.traceId.any { c -> c != '0' })
            assertTrue(p.parentId.any { c -> c != '0' })
        }
    }
}
