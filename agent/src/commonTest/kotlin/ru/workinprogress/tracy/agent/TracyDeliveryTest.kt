package ru.workinprogress.tracy.agent

import io.ktor.http.HttpStatusCode
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.wire.IngestHeaders
import ru.workinprogress.tracy.wire.Level
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The delivery loop, driven a step at a time against a real socket.
 *
 * What matters here is not that a batch goes out — [SenderSocketTest] covers the wire — but what
 * happens when it does not. `202` is the only signal that says the records now exist somewhere
 * else, so a batch that failed on anything weaker has to still be here afterwards.
 */
class TracyDeliveryTest {
    private class Capture {
        val producedHeaders = mutableListOf<String?>()
        val bodies = mutableListOf<String>()
        var calls = 0
    }

    private fun config(endpoint: String) =
        AgentConfig(
            service = "orders-api",
            apiKey = "tr_live_key",
            endpoint = endpoint,
            instanceId = "i1",
            sampleRate = 1.0,
        )

    /** Answers each call from [replies], repeating the last one once the list runs out. */
    private suspend fun withServer(
        replies: List<Pair<HttpStatusCode, String>>,
        block: suspend (port: Int, capture: Capture) -> Unit,
    ) {
        val capture = Capture()
        val server =
            embeddedServer(CIO, port = 0) {
                routing {
                    post("/ingest") {
                        val index = minOf(capture.calls, replies.size - 1)
                        capture.calls++
                        capture.producedHeaders += call.request.header(IngestHeaders.PRODUCED)
                        capture.bodies += call.receiveText()
                        val (status, body) = replies[index]
                        call.respondText(body, io.ktor.http.ContentType.Application.Json, status)
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
                capture,
            )
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    private fun agentFor(config: AgentConfig): TracyAgent =
        TracyAgent(config, clock = { 1785542400000L }, random = { 0.0 })

    @Test
    fun `nothing buffered means nothing sent`() =
        runTest {
            withServer(listOf(HttpStatusCode.Accepted to "{}")) { port, capture ->
                val config = config("http://127.0.0.1:$port/")

                assertNull(TracyDelivery(agentFor(config), config).flushOnce())
                // Not merely "no result": no request either. An empty flush must not cost a call.
                assertEquals(0, capture.calls)
            }
        }

    @Test
    fun `an accepted batch leaves the buffer`() =
        runTest {
            withServer(listOf(HttpStatusCode.Accepted to """{"accepted":1}""")) { port, _ ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("boom")
                val delivery = TracyDelivery(agent, config)

                assertIs<SendResult.Accepted>(delivery.flushOnce())
                assertNull(delivery.flushOnce())
            }
        }

    @Test
    fun `a retriable failure keeps the batch and sends it again`() =
        runTest {
            val replies =
                listOf(
                    HttpStatusCode.ServiceUnavailable to """{"error":"unavailable"}""",
                    HttpStatusCode.Accepted to """{"accepted":1}""",
                )
            withServer(replies) { port, _ ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("keep me")
                val delivery = TracyDelivery(agent, config)

                assertIs<SendResult.Retriable>(delivery.flushOnce())
                // The buffer is empty now — the batch is held by the loop, not lost by it.
                val second = delivery.flushOnce()

                assertIs<SendResult.Accepted>(second)
                assertEquals(1, second.accepted)
            }
        }

    @Test
    fun `a rejected batch is dropped and counted`() =
        runTest {
            withServer(listOf(HttpStatusCode.BadRequest to """{"error":"bad"}""")) { port, _ ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("doomed")
                val delivery = TracyDelivery(agent, config)

                assertIs<SendResult.Rejected>(delivery.flushOnce())

                // Dropped rather than retried forever: a 400 that is retried blocks everything
                // queued behind it, which turns one bad batch into total silence.
                assertNull(delivery.flushOnce())
                assertTrue(delivery.rejected > 0)
            }
        }

    @Test
    fun `malformed lines reported by the server are surfaced`() =
        runTest {
            withServer(listOf(HttpStatusCode.Accepted to """{"accepted":0,"malformed":3}""")) { port, _ ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("fine")
                val delivery = TracyDelivery(agent, config)

                delivery.flushOnce()

                // The decoder counted these so an agent had a reason to notice; before M7 the
                // count never left the server, and a wholly rejected batch looked like success.
                assertEquals(3, delivery.malformed)
            }
        }

    @Test
    fun `suppressed keys from the response stop the refs at the source`() =
        runTest {
            val body = """{"accepted":1,"suppressedKeys":["orderId"]}"""
            withServer(listOf(HttpStatusCode.Accepted to body)) { port, _ ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("first")
                val delivery = TracyDelivery(agent, config)

                delivery.flushOnce()

                // The control channel of research D15 reaches the agent, so the refs stop being
                // produced rather than being produced and dropped on arrival.
                assertEquals(setOf("orderId"), agent.suppressedKeys())
            }
        }

    @Test
    fun `counters are taken once per batch and not once per attempt`() =
        runTest {
            val replies =
                listOf(
                    HttpStatusCode.ServiceUnavailable to """{"error":"unavailable"}""",
                    HttpStatusCode.Accepted to """{"accepted":1}""",
                )
            withServer(replies) { port, capture ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("one")
                val delivery = TracyDelivery(agent, config)

                delivery.flushOnce()
                delivery.flushOnce()

                assertEquals(2, capture.producedHeaders.size)
                // Counters reset when read. Reading them again on the retry would report a second
                // batch's worth of produced bytes for a batch that was never new — and produced
                // bytes are the number the storage estimate is checked against.
                assertEquals(capture.producedHeaders[0], capture.producedHeaders[1])
            }
        }

    @Test
    fun `a dropped batch does not come back with the next one`() =
        runTest {
            val replies =
                listOf(
                    HttpStatusCode.PayloadTooLarge to """{"error":"too large"}""",
                    HttpStatusCode.Accepted to """{"accepted":1}""",
                )
            withServer(replies) { port, capture ->
                val config = config("http://127.0.0.1:$port/")
                val agent = agentFor(config)
                agent.logger("L").error("dropped")
                val delivery = TracyDelivery(agent, config)
                delivery.flushOnce()

                agent.logger("L").error("fresh")
                val second = delivery.flushOnce()

                assertIs<SendResult.Accepted>(second)
                assertTrue("dropped" in capture.bodies[0], capture.bodies[0])
                // The rejected record must not ride along on the next batch: a 413 that keeps
                // its batch makes every following batch bigger, which is how one rejection
                // becomes permanent.
                assertTrue("dropped" !in capture.bodies[1], capture.bodies[1])
                assertTrue("fresh" in capture.bodies[1], capture.bodies[1])
            }
        }
}
