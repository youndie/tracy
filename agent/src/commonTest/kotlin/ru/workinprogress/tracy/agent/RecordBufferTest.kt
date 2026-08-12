package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RecordBufferTest {
    private fun record(
        seq: Long,
        message: String = "user logged in",
    ) = LogRecord(ts = seq, seq = seq, level = Level.INFO, logger = "L", message = message)

    @Test
    fun `records come out in the order they went in`() {
        val buffer = RecordBuffer(maxBytes = 1_000_000)

        repeat(10) { buffer.offer(record(it.toLong())) }

        val out = buffer.drain(1_000_000).filterIsInstance<LogRecord>().map { it.seq }
        assertEquals((0L..9L).toList(), out)
    }

    @Test
    fun `the limit is in bytes so one fat record cannot be traded for many small ones`() {
        // A record carrying a stack trace is worth dozens of ordinary ones; a count-based limit
        // would not bound memory at all.
        val buffer = RecordBuffer(maxBytes = 1000)

        val accepted = buffer.offer(record(1, message = "x".repeat(5000)))

        assertTrue(!accepted)
        assertEquals(1, buffer.dropped)
    }

    @Test
    fun `overflow drops and counts rather than growing`() {
        val buffer = RecordBuffer(maxBytes = 500)

        var accepted = 0
        repeat(50) { if (buffer.offer(record(it.toLong()))) accepted++ }

        assertTrue(accepted in 1..49, "expected some to fit and some to be dropped")
        assertEquals((50 - accepted).toLong(), buffer.dropped)
        assertTrue(buffer.pending <= 500)
    }

    @Test
    fun `draining frees the accounted bytes`() {
        val buffer = RecordBuffer(maxBytes = 100_000)
        repeat(20) { buffer.offer(record(it.toLong())) }
        assertTrue(buffer.pending > 0)

        buffer.drain(1_000_000)

        assertEquals(0, buffer.pending)
        // And the buffer accepts again afterwards.
        assertTrue(buffer.offer(record(99)))
    }

    @Test
    fun `drain respects the batch limit and leaves the rest`() {
        val buffer = RecordBuffer(maxBytes = 1_000_000)
        repeat(100) { buffer.offer(record(it.toLong())) }

        val first = buffer.drain(400)
        val rest = buffer.drain(1_000_000)

        assertTrue(first.isNotEmpty() && first.size < 100)
        assertEquals(100, first.size + rest.size, "splitting a drain must not lose records")
    }

    @Test
    fun `produced counts what the service made regardless of what survived`() {
        val buffer = RecordBuffer(maxBytes = 300)

        repeat(20) { buffer.offer(record(it.toLong())) }

        // This is the "who is noisy" number: measured before sampling and before dropping,
        // otherwise it answers "how much did we decide to keep" instead (research D13).
        assertTrue(buffer.produced > buffer.pending)
        assertTrue(buffer.dropped > 0)
    }

    @Test
    fun `taking counters resets them`() {
        val buffer = RecordBuffer(maxBytes = 200)
        repeat(20) { buffer.offer(record(it.toLong())) }

        val first = buffer.takeCounters()
        val second = buffer.takeCounters()

        assertTrue(first.dropped > 0 && first.producedBytes > 0)
        assertEquals(0, second.dropped)
        assertEquals(0, second.producedBytes)
    }

    @Test
    fun `offering never throws`() {
        val buffer = RecordBuffer(maxBytes = 1)

        // The agent must never surface in the host service's code path, whatever happens here.
        repeat(100) { buffer.offer(record(it.toLong(), message = "y".repeat(100))) }

        assertEquals(100, buffer.dropped)
    }
}
