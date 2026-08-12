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
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.NdJson
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M-29: the sender is exercised against a **real socket**, not a fake.
 *
 * The sender is built to swallow its own failures, so a fake would confirm everything except the
 * single thing that can break without anyone noticing. In metrik two such components stayed silent
 * in production for months with green tests behind them; that is the reason this file exists.
 */
class SenderSocketTest {
    private class Capture {
        var body: String? = null
        val headers = mutableMapOf<String, String>()
        var calls = 0
    }

    private fun config(
        endpoint: String,
        release: String? = "1.4.212",
    ) = AgentConfig(
        service = "orders-api",
        apiKey = "tr_live_key",
        endpoint = endpoint,
        instanceId = "orders-api-7d9f8-x2k1",
        release = release,
    )

    private fun records(n: Int) =
        (1..n).map {
            LogRecord(
                ts = 1754049600000 + it,
                seq = it.toLong(),
                level = Level.INFO,
                logger = "OrdersRouting",
                message = "order created",
            )
        }

    private suspend fun withServer(
        status: HttpStatusCode,
        responseBody: String,
        block: suspend (port: Int, capture: Capture) -> Unit,
    ) {
        val capture = Capture()
        val server =
            embeddedServer(CIO, port = 0) {
                routing {
                    post("/ingest") {
                        capture.calls++
                        capture.body = call.receiveText()
                        for (
                        name in
                        listOf(
                            IngestHeaders.KEY,
                            IngestHeaders.SERVICE,
                            IngestHeaders.INSTANCE,
                            IngestHeaders.RELEASE,
                            IngestHeaders.SEQ,
                            IngestHeaders.DROPPED,
                            IngestHeaders.PRODUCED,
                        )
                        ) {
                            call.request.header(name)?.let { capture.headers[name] = it }
                        }
                        call.respondText(responseBody, status = status)
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
            block(port, capture)
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 500)
        }
    }

    @Test
    fun `a batch reaches a real server intact`() =
        runTest {
            withServer(HttpStatusCode.Accepted, """{"accepted":3,"suppressedKeys":[]}""") { port, capture ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    val result = sender.send(records(3), seq = 4218)

                    val accepted = assertIs<SendResult.Accepted>(result)
                    assertEquals(3, accepted.accepted)
                    assertEquals(1, capture.calls)

                    // The body must survive the wire and parse back into the same records.
                    val decoded = NdJson.decodeBatch(capture.body.orEmpty())
                    assertEquals(0, decoded.malformed)
                    assertEquals(3, decoded.lines.size)
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `every header the protocol requires is actually sent`() =
        runTest {
            withServer(HttpStatusCode.Accepted, """{"accepted":1}""") { port, capture ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    sender.send(records(1), seq = 7, counters = BufferCounters(dropped = 5, producedBytes = 900))

                    assertEquals("tr_live_key", capture.headers[IngestHeaders.KEY])
                    assertEquals("orders-api", capture.headers[IngestHeaders.SERVICE])
                    assertEquals("orders-api-7d9f8-x2k1", capture.headers[IngestHeaders.INSTANCE])
                    assertEquals("1.4.212", capture.headers[IngestHeaders.RELEASE])
                    assertEquals("7", capture.headers[IngestHeaders.SEQ])
                    assertEquals("5", capture.headers[IngestHeaders.DROPPED])
                    assertEquals("900", capture.headers[IngestHeaders.PRODUCED])
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `suppressed keys come back from the response`() =
        runTest {
            val body = """{"accepted":1,"suppressedKeys":["ip","requestId"]}"""
            withServer(HttpStatusCode.Accepted, body) { port, _ ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    val accepted = assertIs<SendResult.Accepted>(sender.send(records(1), seq = 1))

                    // This is the only channel the server's decision travels through (research D15).
                    assertEquals(listOf("ip", "requestId"), accepted.suppressedKeys)
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `an unauthorised batch is not retried`() =
        runTest {
            withServer(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""") { port, _ ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    val result = sender.send(records(1), seq = 1)

                    assertIs<SendResult.Rejected>(result)
                    assertEquals(401, result.status)
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `a service unavailable answer keeps the batch for later`() =
        runTest {
            withServer(HttpStatusCode.ServiceUnavailable, """{"error":"unavailable"}""") { port, _ ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    assertIs<SendResult.Retriable>(sender.send(records(1), seq = 1))
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `nothing listening is retriable and never throws`() =
        runTest {
            // The port is deliberately closed. This is the shape of the failure that stayed
            // invisible in metrik for months.
            val sender = Sender(config("http://127.0.0.1:1"))
            try {
                val result = sender.send(records(1), seq = 1)

                assertIs<SendResult.Retriable>(result)
            } finally {
                sender.close()
            }
        }

    @Test
    fun `a hostname is resolved by the engine itself`() =
        runTest {
            // metrik had to write a getaddrinfo shim because ktor-network sockets do not resolve
            // names on Kotlin/Native. libcurl resolves them itself, so the shim should be
            // unnecessary here — verified rather than assumed.
            withServer(HttpStatusCode.Accepted, """{"accepted":1}""") { port, capture ->
                val sender = Sender(config("http://localhost:$port"))
                try {
                    val result = sender.send(records(1), seq = 1)

                    assertIs<SendResult.Accepted>(result)
                    assertEquals(1, capture.calls)
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `an empty batch does not touch the network`() =
        runTest {
            withServer(HttpStatusCode.Accepted, """{"accepted":0}""") { port, capture ->
                val sender = Sender(config("http://127.0.0.1:$port"))
                try {
                    assertIs<SendResult.Accepted>(sender.send(emptyList(), seq = 1))

                    assertEquals(0, capture.calls)
                } finally {
                    sender.close()
                }
            }
        }

    @Test
    fun `backoff grows and then stops growing`() {
        val backoff = Backoff()

        val delays = (0..10).map { backoff.delayFor(it).inWholeSeconds }

        assertEquals(listOf(1L, 2, 4, 8, 16, 32), delays.take(6))
        assertTrue(delays.all { it <= 60 }, "backoff must not grow without a ceiling")
    }
}
