package io.github.youndie.tracy.agent

import io.github.youndie.tracy.wire.LogRecord
import io.github.youndie.tracy.wire.Span
import io.github.youndie.tracy.wire.TraceParent
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A trace that starts in an app (research-clients K2): the person's action is the root, and the
 * service it calls continues the same trace.
 */
class ActionTest {
    private fun agent(sampleRate: Double) =
        TracyAgent(
            config =
                AgentConfig(
                    service = "app",
                    apiKey = "k",
                    endpoint = "http://x",
                    instanceId = "i",
                    sampleRate = sampleRate,
                ),
            clock = { 1754049600000L },
            random = { 0.5 },
        )

    @Test
    fun `an action opens a trace and its outgoing call carries it`() =
        runTest {
            val agent = agent(sampleRate = 1.0)
            var seen: String? = null
            val server =
                embeddedServer(CIO, port = 0) {
                    routing {
                        get("/orders") {
                            seen = call.request.header(TraceParent.HEADER)
                            call.respondText("ok")
                        }
                    }
                }
            server.start(wait = false)
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                HttpClient { install(TracyClient) { this.agent = agent } }.use { http ->
                    agent.action("checkout.submit") { http.get("http://127.0.0.1:$port/orders") }
                }
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
            }

            val header = assertNotNull(TraceParent.parse(seen), "the call left without a traceparent")
            val spans = agent.drainBatch().filterIsInstance<Span>()
            val root = spans.single { it.parentSpanId == null }

            assertEquals("checkout.submit", root.name)
            assertEquals(root.traceId, header.traceId, "the service has to continue the app's trace")
            assertTrue(header.sampled, "a head decision to keep travels downstream")
            // The client span hangs off the action, and the callee off the client span.
            val call = spans.single { it.parentSpanId == root.spanId }
            assertEquals(call.spanId, header.parentId)
        }

    @Test
    fun `records inside an action belong to its trace`() =
        runTest {
            val agent = agent(sampleRate = 1.0)

            agent.action("screen.open") { agent.logger("Screen").info("opened") }

            val lines = agent.drainBatch()
            val root = lines.filterIsInstance<Span>().single()
            assertEquals(root.traceId, lines.filterIsInstance<LogRecord>().single().traceId)
        }

    @Test
    fun `a failed action is kept even when the head said drop`() =
        runTest {
            val agent = agent(sampleRate = 0.0)

            assertFailsWith<IllegalStateException> {
                agent.action("checkout.submit") {
                    agent.logger("Checkout").info("submitting")
                    error("declined")
                }
            }

            val lines = agent.drainBatch()
            assertEquals(1, lines.filterIsInstance<Span>().single().error)
            assertEquals(1, lines.filterIsInstance<LogRecord>().size, "the tail decision keeps a failure")
        }

    @Test
    fun `a healthy action the head dropped is not rolled for again at the tail`() =
        runTest {
            // The first roll is the head's and drops (0.5 against a rate of 0.4); a second roll at
            // the tail would come up 0.1 and keep. Kept records here would mean the configured share
            // of healthy traces is quietly doubled.
            val rolls = ArrayDeque(listOf(0.5, 0.1))
            val agent =
                TracyAgent(
                    config =
                        AgentConfig(
                            service = "app",
                            apiKey = "k",
                            endpoint = "http://x",
                            instanceId = "i",
                            sampleRate = 0.4,
                        ),
                    clock = { 1754049600000L },
                    random = { rolls.removeFirst() },
                )

            agent.action("screen.open") { agent.logger("Screen").info("opened") }

            assertTrue(agent.drainBatch().none { it is LogRecord || it is Span })
            assertEquals(1, rolls.size, "exactly one roll, at the head")
        }

    @Test
    fun `inside an existing trace an action is a span of it`() =
        runTest {
            val agent = agent(sampleRate = 1.0)

            agent.action("outer") {
                val outer = assertNotNull(currentCoroutineContext()[TracyTraceContext])
                agent.action("inner") {
                    assertEquals(outer.traceId, currentCoroutineContext()[TracyTraceContext]?.traceId)
                }
            }

            val spans = agent.drainBatch().filterIsInstance<Span>()
            assertEquals(1, spans.map { it.traceId }.distinct().size)
            assertNull(spans.single { it.name == "outer" }.parentSpanId)
            assertNotNull(spans.single { it.name == "inner" }.parentSpanId)
        }
}
