package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceParent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TracyClientPluginTest {
    private fun agent() =
        TracyAgent(
            config = AgentConfig(service = "s", apiKey = "k", endpoint = "http://x", instanceId = "i"),
            clock = { 1754049600000L },
        )

    private class Seen {
        var traceparent: String? = null
    }

    private suspend fun withPeer(
        status: HttpStatusCode = HttpStatusCode.OK,
        block: suspend (port: Int, seen: Seen) -> Unit,
    ) {
        val seen = Seen()
        val server =
            embeddedServer(CIO, port = 0) {
                routing {
                    get("/charge") {
                        seen.traceparent = call.request.header(TraceParent.HEADER)
                        call.respondText("ok", status = status)
                    }
                }
            }
        server.start(wait = false)
        try {
            block(
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port,
                seen,
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    private fun client(agent: TracyAgent) = HttpClient { install(TracyClient) { this.agent = agent } }

    @Test
    fun `an outgoing call carries the trace with our span as the parent`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", false)

            withPeer { port, seen ->
                client(agent).use { withContext(trace) { it.get("http://127.0.0.1:$port/charge") } }

                val header = assertNotNull(TraceParent.parse(seen.traceparent))
                val span = trace.takePending().filterIsInstance<Span>().single()

                assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", header.traceId)
                // The callee must hang off this call, not off the request that caused it —
                // that is what turns a flat list of services into a tree.
                assertEquals(span.spanId, header.parentId)
                assertEquals("00f067aa0ba902b7", span.parentSpanId)
                assertEquals(SpanKind.CLIENT, span.kind)
                assertEquals(200, span.status)
            }
        }

    @Test
    fun `only the head decision travels downstream`() =
        runTest {
            val agent = agent()
            // A tail decision cannot reach a call that has already been made (research D7).
            val notSampled = TracyTraceContext("4bf9aaaaaaaaaaaaaaaaaaaaaaaaaaaa", "00f0", sampledUpstream = false)
            val sampled = TracyTraceContext("4bf9bbbbbbbbbbbbbbbbbbbbbbbbbbbb", "00f0", sampledUpstream = true)

            withPeer { port, seen ->
                client(agent).use { c ->
                    withContext(notSampled) { c.get("http://127.0.0.1:$port/charge") }
                    assertEquals(false, TraceParent.parse(seen.traceparent)?.sampled)

                    withContext(sampled) { c.get("http://127.0.0.1:$port/charge") }
                    assertEquals(true, TraceParent.parse(seen.traceparent)?.sampled)
                }
            }
        }

    @Test
    fun `a server error downstream makes the caller keep its trace`() =
        runTest {
            val agent = agent()
            val trace = TracyTraceContext("4bf9", "00f0", sampledUpstream = false)

            withPeer(HttpStatusCode.InternalServerError) { port, _ ->
                client(agent).use { withContext(trace) { it.get("http://127.0.0.1:$port/charge") } }
            }

            // This is how an error climbs the call chain without any flag being propagated down.
            assertTrue(trace.hasProblem)
            assertEquals(
                1,
                trace
                    .takePending()
                    .filterIsInstance<Span>()
                    .single()
                    .error,
            )
        }

    @Test
    fun `outside a trace the plugin does nothing`() =
        runTest {
            val agent = agent()

            withPeer { port, seen ->
                client(agent).use { it.get("http://127.0.0.1:$port/charge") }

                assertNull(seen.traceparent, "inventing a trace nothing else knows about is worse than none")
                assertTrue(agent.drainBatch().filterIsInstance<Span>().isEmpty())
            }
        }

    @Test
    fun `the span name drops the query and redacts the path`() {
        val agent = agent()

        // The docs first wrote this name as the full URL. Real logs showed why that is wrong: the
        // live token found in production sat in a URL path (research 1.10), and a span name is
        // low-cardinality structure that gets read as trusted.
        val name =
            spanName(
                "GET",
                "https://api.telegram.org/bot1234567890:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E/getUpdates?token=abc123456",
                agent,
            )

        assertTrue("AAFqqqZrh" !in name)
        assertTrue("abc123456" !in name)
        assertTrue("api.telegram.org" in name, "the host is what makes the span readable")
        assertTrue("getUpdates" in name)
    }

    @Test
    fun `an ordinary url survives unchanged`() {
        assertEquals(
            "GET https://billing.internal/charge",
            spanName("GET", "https://billing.internal/charge", agent()),
        )
    }
}
