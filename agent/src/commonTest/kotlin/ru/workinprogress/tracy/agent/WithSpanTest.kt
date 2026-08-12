package ru.workinprogress.tracy.agent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class WithSpanTest {
    private fun agent() =
        TracyAgent(
            config = AgentConfig(service = "s", apiKey = "k", endpoint = "http://x", instanceId = "i"),
            clock = { 1754049600000L },
        )

    @Test
    fun `a manual span becomes a child of the request`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) {
                withSpan("persistOrder", agent) { 42 }
            }

            val span = trace.takePending().filterIsInstance<Span>().single()
            assertEquals("persistOrder", span.name)
            assertEquals(SpanKind.INTERNAL, span.kind)
            assertEquals("00f0", span.parentSpanId)
            assertNotNull(span.durationMs)
        }

    @Test
    fun `the block result is returned`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            val result = withContext(trace) { withSpan("compute", agent) { "value" } }

            assertEquals("value", result)
        }

    @Test
    fun `a failing block marks the span and the trace and rethrows`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            assertFailsWith<IllegalStateException> {
                withContext(trace) { withSpan("persistOrder", agent) { error("nope") } }
            }

            val span = trace.takePending().filterIsInstance<Span>().single()
            assertEquals(1, span.error)
            assertTrue(trace.hasProblem, "a failed span must make the trace worth keeping")
        }

    @Test
    fun `outside a request it is a no-op that still runs the block`() =
        runTest {
            val agent = agent()
            var ran = false

            // No parent to attach to. That is research 1.3 showing through, not a bug: the span
            // has nowhere to belong, and swallowing the block would be far worse.
            val result =
                withSpan("orphan", agent) {
                    ran = true
                    "done"
                }

            assertEquals("done", result)
            assertTrue(ran)
            assertTrue(agent.drainBatch().filterIsInstance<Span>().isEmpty())
        }

    @Test
    fun `nested spans keep the request as their parent`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withContext(trace) {
                withSpan("outer", agent) {
                    withSpan("inner", agent) { }
                }
            }

            val spans = trace.takePending().filterIsInstance<Span>()
            assertEquals(2, spans.size)
            // Both hang off the request span: nesting would need the context to be replaced per
            // span, which is deliberately not done — unattributed time is shown, not invented.
            assertTrue(spans.all { it.parentSpanId == "00f0" })
        }
}
