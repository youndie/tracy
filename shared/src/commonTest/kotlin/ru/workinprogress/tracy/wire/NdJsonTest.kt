package ru.workinprogress.tracy.wire

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class NdJsonTest {
    private fun record(
        seq: Long,
        message: String = "user logged in",
    ) = LogRecord(ts = 1754049600000 + seq, seq = seq, level = Level.INFO, logger = "L", message = message)

    private fun span(id: String) =
        Span(
            traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            spanId = id,
            name = "POST /orders",
            kind = SpanKind.SERVER,
            ts = 1,
            durationMs = 4,
        )

    private fun counter() = TemplateCount(windowStart = 1754049600000, template = "user logged in", level = Level.INFO, count = 17)

    @Test
    fun `mixed lines survive a round trip in one stream`() {
        val lines = listOf(record(1), span("00f067aa0ba902b7"), counter(), record(2))

        val decoded = NdJson.decodeBatch(NdJson.encodeBatch(lines))

        assertEquals(0, decoded.malformed)
        assertEquals(lines, decoded.lines)
        assertIs<Span>(decoded.lines[1])
        assertIs<TemplateCount>(decoded.lines[2])
    }

    @Test
    fun `one line per record`() {
        val encoded = NdJson.encodeBatch(listOf(record(1), record(2), record(3)))

        assertEquals(3, encoded.lines().size)
    }

    @Test
    fun `a malformed line is skipped and counted while the rest is kept`() {
        val text =
            listOf(
                NdJson.encodeLine(record(1)),
                "{ this is not json",
                NdJson.encodeLine(record(2)),
            ).joinToString("\n")

        val decoded = NdJson.decodeBatch(text)

        assertEquals(1, decoded.malformed)
        assertEquals(2, decoded.lines.size, "one bad line must not cost the whole batch")
    }

    @Test
    fun `blank lines are not malformed`() {
        val decoded = NdJson.decodeBatch("\n${NdJson.encodeLine(record(1))}\n\n")

        assertEquals(0, decoded.malformed)
        assertEquals(1, decoded.lines.size)
    }

    @Test
    fun `split keeps every batch within the limit`() {
        val lines = (1..200L).map { record(it) }
        val max = 900

        val split = NdJson.splitByBytes(lines, max)

        assertEquals(0, split.oversized)
        assertEquals(lines, split.batches.flatten(), "splitting must not lose or reorder lines")
        for (batch in split.batches) {
            assertTrue(
                NdJson.encodeBatch(batch).encodeToByteArray().size <= max,
                "a batch exceeded the limit",
            )
        }
    }

    @Test
    fun `an oversized line goes alone and is reported rather than dropped`() {
        val huge = record(1, message = "x".repeat(5000))
        val lines = listOf(record(2), huge, record(3))

        val split = NdJson.splitByBytes(lines, 500)

        assertEquals(1, split.oversized)
        assertEquals(lines.size, split.batches.flatten().size, "an oversized line must not vanish")
        assertTrue(split.batches.any { it.size == 1 && it.first() == huge })
    }

    @Test
    fun `empty input yields no batches`() {
        val split = NdJson.splitByBytes(emptyList(), 100)

        assertTrue(split.batches.isEmpty())
        assertEquals(0, split.oversized)
    }
}
