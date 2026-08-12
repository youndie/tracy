package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The plugin is exercised against a real server and real requests: routing, headers and the
 * response status are exactly the parts that a fake would get wrong quietly.
 */
class TracyPluginTest {
    private var now = 1754049600000L

    private fun agent(
        sampleRate: Double = 1.0,
        slowThresholdMs: Long = 60_000,
        random: () -> Double = { 0.0 },
    ) = TracyAgent(
        config =
            AgentConfig(
                service = "orders-api",
                apiKey = "k",
                endpoint = "http://unused",
                instanceId = "i",
                sampleRate = sampleRate,
                slowThreshold = kotlin.time.Duration.parse("${slowThresholdMs}ms"),
            ),
        clock = { now },
        random = random,
    )

    private suspend fun withApp(
        agent: TracyAgent,
        block: suspend (client: HttpClient, port: Int) -> Unit,
    ) {
        val server =
            embeddedServer(CIO, port = 0) {
                install(Tracy) { this.agent = agent }
                routing {
                    get("/users/{id}") {
                        agent.logger("UsersRouting").info("user fetched") {
                            field("userId", call.parameters["id"], indexed = true)
                        }
                        call.respondText("ok")
                    }
                    get("/warns") {
                        agent.logger("L").warn("careful")
                        call.respondText("ok")
                    }
                    get("/boom") { throw IllegalStateException("boom") }
                    get("/slow") {
                        // Real elapsed time: the plugin measures duration with a monotonic clock,
                        // deliberately, so it cannot be fooled by a wall-clock jump. The injected
                        // clock only stamps timestamps.
                        kotlinx.coroutines.delay(120)
                        call.respondText("ok")
                    }
                }
            }
        server.start(wait = false)
        val client = HttpClient()
        try {
            block(
                client,
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port,
            )
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    @Test
    fun `a request produces a server span named after the route template`() =
        runTest {
            val agent = agent()
            withApp(agent) { client, port ->
                client.get("http://127.0.0.1:$port/users/42")
            }

            val span = agent.drainBatch().filterIsInstance<Span>().single()
            // The template, never the path: a raw path explodes cardinality and drags user data
            // into a name that reads as trusted.
            assertEquals("GET /users/{id}", span.name)
            assertEquals(SpanKind.SERVER, span.kind)
            assertEquals(200, span.status)
            assertNotNull(span.durationMs)
        }

    @Test
    fun `records made inside the handler carry the trace of that request`() =
        runTest {
            val agent = agent()
            withApp(agent) { client, port -> client.get("http://127.0.0.1:$port/users/42") }

            val lines = agent.drainBatch()
            val record = lines.filterIsInstance<LogRecord>().single()
            val span = lines.filterIsInstance<Span>().single()

            assertEquals(span.traceId, record.traceId)
            assertEquals(span.spanId, record.spanId)
        }

    @Test
    fun `an incoming traceparent is adopted`() =
        runTest {
            val agent = agent()
            val incoming = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"

            withApp(agent) { client, port ->
                client.get("http://127.0.0.1:$port/users/42") { header("traceparent", incoming) }
            }

            val span = agent.drainBatch().filterIsInstance<Span>().single()
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", span.traceId)
            assertEquals("00f067aa0ba902b7", span.parentSpanId)
        }

    @Test
    fun `an invalid traceparent starts a new trace instead of failing the request`() =
        runTest {
            val agent = agent()
            val allZero = "00-${"0".repeat(32)}-00f067aa0ba902b7-01"

            withApp(agent) { client, port ->
                val response = client.get("http://127.0.0.1:$port/users/42") { header("traceparent", allZero) }
                assertEquals(200, response.status.value)
            }

            val span = agent.drainBatch().filterIsInstance<Span>().single()
            assertTrue(span.traceId != "0".repeat(32))
            assertEquals(null, span.parentSpanId)
        }

    @Test
    fun `a dropped trace keeps warnings`() =
        runTest {
            // Nothing is sampled, but the floor still applies (research D7).
            val agent = agent(sampleRate = 0.0, random = { 1.0 })
            withApp(agent) { client, port -> client.get("http://127.0.0.1:$port/warns") }

            val lines = agent.drainBatch()
            assertEquals(1, lines.filterIsInstance<LogRecord>().size)
            assertEquals(Level.WARN, lines.filterIsInstance<LogRecord>().single().level)
        }

    @Test
    fun `a dropped trace keeps entity references without their bodies`() =
        runTest {
            val agent = agent(sampleRate = 0.0, random = { 1.0 })
            withApp(agent) { client, port -> client.get("http://127.0.0.1:$port/users/42") }

            val lines = agent.drainBatch()
            val refs = lines.filterIsInstance<EntityRef>()

            // The success case is exactly what support asks about, and it lives at 1%. If the
            // reference did not survive here the whole feature would be dead (research D12).
            assertEquals(1, refs.size)
            assertEquals("userId", refs.single().key)
            assertEquals("42", refs.single().value)
            assertTrue(lines.filterIsInstance<LogRecord>().isEmpty(), "the body must be gone")
        }

    @Test
    fun `a dropped trace keeps no span`() =
        runTest {
            val agent = agent(sampleRate = 0.0, random = { 1.0 })
            withApp(agent) { client, port -> client.get("http://127.0.0.1:$port/users/42") }

            // Spans follow the trace: one per request cost ~1.2 GB a day at 100 rps (research D7).
            assertTrue(agent.drainBatch().filterIsInstance<Span>().isEmpty())
        }

    @Test
    fun `a slow request is kept even though it succeeded`() =
        runTest {
            val agent = agent(sampleRate = 0.0, slowThresholdMs = 30, random = { 1.0 })
            withApp(agent) { client, port -> client.get("http://127.0.0.1:$port/slow") }

            assertTrue(
                agent.drainBatch().filterIsInstance<Span>().isNotEmpty(),
                "a slow but successful request is the case sampling would otherwise lose",
            )
        }

    @Test
    fun `a forced request is kept`() =
        runTest {
            val agent = agent(sampleRate = 0.0, random = { 1.0 })
            withApp(agent) { client, port ->
                client.get("http://127.0.0.1:$port/users/42") { header("X-Tracy-Force", "1") }
            }

            assertTrue(agent.drainBatch().filterIsInstance<Span>().isNotEmpty())
        }

    @Test
    fun `a failing handler marks the trace and keeps it`() =
        runTest {
            val agent = agent(sampleRate = 0.0, random = { 1.0 })
            withApp(agent) { client, port ->
                runCatching { client.get("http://127.0.0.1:$port/boom") }
            }

            val span = agent.drainBatch().filterIsInstance<Span>().singleOrNull()
            assertNotNull(span, "a failed request must never be sampled away")
            assertEquals(1, span.error)
        }

    @Test
    fun `route sanitisation strips the selector branch`() {
        // RoutingNode.toString() prints the whole branch, selectors included.
        assertEquals("/users/{id}", sanitizeRoute("/users/{id}/(method:GET)"))
        assertEquals("/health", sanitizeRoute("/health/(method:GET)"))
        assertEquals("/", sanitizeRoute("/(method:GET)"))
        assertEquals("/a/b/c", sanitizeRoute("/a/b/c"))
    }
}
