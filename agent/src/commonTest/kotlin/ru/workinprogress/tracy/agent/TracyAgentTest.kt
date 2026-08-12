package ru.workinprogress.tracy.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.TemplateCount
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracyAgentTest {
    private var now = 1754049600000L

    private fun agent(
        level: Level = Level.INFO,
        sampleRate: Double = 1.0,
    ) = TracyAgent(
        config =
            AgentConfig(
                service = "orders-api",
                apiKey = "k",
                endpoint = "http://tracy:8080",
                instanceId = "orders-api-1",
                level = level,
                sampleRate = sampleRate,
            ),
        clock = { now },
    )

    @Test
    fun `a record without a request goes straight to the buffer`() =
        runTest {
            val agent = agent()

            agent.logger("OrdersRouting").info("order created") { field("orderId", "12345") }

            val lines = agent.drainBatch().filterIsInstance<LogRecord>()
            assertEquals(1, lines.size)
            assertEquals("order created", lines.single().message)
            assertNull(lines.single().traceId, "there was no request to correlate with")
        }

    @Test
    fun `a record inside a request waits for the tail decision`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) {
                agent.logger("OrdersRouting").info("order created")
            }

            assertTrue(
                agent.drainBatch().filterIsInstance<LogRecord>().isEmpty(),
                "the record must not be sent before the request finished",
            )
            assertEquals(1, trace.takePending().size)
        }

    @Test
    fun `the trace is picked up from the coroutine context`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false)

            withContext(trace) {
                agent.logger("L").info("hello")
            }

            val record = trace.takePending().filterIsInstance<LogRecord>().single()
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", record.traceId)
            assertEquals("00f067aa0ba902b7", record.spanId)
        }

    @Test
    fun `a suppressed record costs nothing`() =
        runTest {
            val agent = agent(level = Level.INFO)
            var blockRan = false

            agent.logger("L").debug("expensive") {
                blockRan = true
                field("payload", "x".repeat(1000))
            }

            assertTrue(!blockRan, "the field block ran for a suppressed record")
            assertTrue(agent.drainBatch().isEmpty())
        }

    @Test
    fun `counters are incremented for records that pass the level`() =
        runTest {
            val agent = agent()

            repeat(3) { agent.logger("L").info("order created") }
            now += TemplateCounters.WINDOW_MS

            val counts = agent.drainBatch().filterIsInstance<TemplateCount>()
            assertEquals(1, counts.size)
            assertEquals(3, counts.single().count)
            assertEquals("order created", counts.single().template)
        }

    @Test
    fun `counters follow the level threshold`() =
        runTest {
            val agent = agent(level = Level.WARN)

            agent.logger("L").info("not logged at all")
            now += TemplateCounters.WINDOW_MS

            assertTrue(
                agent.drainBatch().filterIsInstance<TemplateCount>().isEmpty(),
                "counting what was never logged would break the zero-cost promise",
            )
        }

    @Test
    fun `counters are exempt from the tail decision`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) { agent.logger("L").info("order created") }
            now += TemplateCounters.WINDOW_MS

            // The record itself is still pending the tail decision and may yet be dropped;
            // the count must survive that regardless (research D13).
            val counts = agent.drainBatch().filterIsInstance<TemplateCount>()
            assertEquals(1, counts.single().count)
        }

    @Test
    fun `a secret in the message never reaches the record`() =
        runTest {
            val agent = agent()

            agent
                .logger("TelegramClient")
                .info("GET https://api.telegram.org/bot1234567890:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E/getUpdates")

            val record = agent.drainBatch().filterIsInstance<LogRecord>().single()
            assertTrue("AAFqqqZrh" !in record.message)
            assertTrue(LogRecord.REDACTED_MESSAGE in (record.redacted ?: emptyList()))
        }

    @Test
    fun `the counter template is the redacted one`() =
        runTest {
            val agent = agent()

            agent.logger("L").info("GET https://api.telegram.org/bot1:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E/x")
            now += TemplateCounters.WINDOW_MS

            // Templates outlive bodies and are indexed; a secret in one is worse than in a record.
            val template =
                agent
                    .drainBatch()
                    .filterIsInstance<TemplateCount>()
                    .single()
                    .template
            assertTrue("AAFqqqZrh" !in template)
        }

    @Test
    fun `warnings mark the request as a problem`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) { agent.logger("L").warn("retrying") }

            assertTrue(trace.hasProblem, "a WARN must make the trace worth keeping")
        }

    @Test
    fun `entity references are deduplicated within one trace`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) {
                repeat(3) {
                    agent.logger("L").info("order touched") { field("orderId", "12345", indexed = true) }
                }
            }

            val marked = trace.takePending().filterIsInstance<LogRecord>().count { it.indexed != null }
            assertEquals(1, marked, "one entity in one trace is one reference")
        }

    @Test
    fun `sequence numbers are monotonic`() =
        runTest {
            val agent = agent()

            repeat(5) { agent.logger("L").info("x") }

            val seqs = agent.drainBatch().filterIsInstance<LogRecord>().map { it.seq }
            assertEquals(seqs.sorted(), seqs)
            assertEquals(5, seqs.toSet().size)
        }
}
