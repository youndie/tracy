package ru.workinprogress.tracy.server.ingest

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.server.ServerConfig
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.IngestHeaders
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.NdJson
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The endpoint over a real socket. The parts worth testing here — header validation, the status
 * codes the protocol promises, and a malformed line not costing the batch — are all things a
 * unit test of the repository cannot see.
 */
class IngestRoutingTest {
    private val day = 1785542400000L

    private fun record(seq: Long) = LogRecord(ts = day, seq = seq, level = Level.INFO, logger = "L", message = "order created")

    private suspend fun ISQLite.scalar(sql: String): Long? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private suspend fun withServer(
        suppressed: List<String> = emptyList(),
        block: suspend (client: HttpClient, port: Int, db: ISQLite) -> Unit,
    ) {
        val db = openDatabase("/tmp/tracy-ingest-${Random.nextLong()}.db")
        val config =
            ServerConfig(httpPort = 0, dbPath = "unused", ingestKey = "tr_live_key", maxBatchBytes = 4096)
        val repository = IngestRepository(db, clock = { day })

        val server =
            embeddedServer(CIO, port = 0) {
                routing { ingestRoutes(config, repository) { suppressed } }
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
                db,
            )
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    private suspend fun HttpClient.send(
        port: Int,
        body: String,
        key: String? = "tr_live_key",
        service: String? = "orders-api",
        instance: String? = "pod-a",
        seq: String? = "1",
    ) = post("http://127.0.0.1:$port/ingest") {
        key?.let { header(IngestHeaders.KEY, it) }
        service?.let { header(IngestHeaders.SERVICE, it) }
        instance?.let { header(IngestHeaders.INSTANCE, it) }
        seq?.let { header(IngestHeaders.SEQ, it) }
        setBody(body)
    }

    @Test
    fun `a valid batch is accepted and stored`() =
        runTest {
            withServer { client, port, db ->
                val response = client.send(port, NdJson.encodeBatch(listOf(record(1), record(2))))

                assertEquals(202, response.status.value)
                assertTrue("\"accepted\":2" in response.bodyAsText())
                assertEquals(2, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            }
        }

    @Test
    fun `a wrong key never reaches the database`() =
        runTest {
            withServer { client, port, db ->
                val response = client.send(port, NdJson.encodeBatch(listOf(record(1))), key = "nope")

                assertEquals(401, response.status.value)
                assertEquals(0, db.scalar("SELECT count(*) FROM service"))
            }
        }

    @Test
    fun `a missing key is rejected`() =
        runTest {
            withServer { client, port, _ ->
                assertEquals(401, client.send(port, "", key = null).status.value)
            }
        }

    @Test
    fun `missing required headers are a bad request`() =
        runTest {
            withServer { client, port, _ ->
                assertEquals(400, client.send(port, "", service = null).status.value)
                assertEquals(400, client.send(port, "", instance = null).status.value)
                assertEquals(400, client.send(port, "", seq = null).status.value)
                assertEquals(400, client.send(port, "", seq = "not-a-number").status.value)
            }
        }

    @Test
    fun `an oversized batch is rejected rather than truncated`() =
        runTest {
            withServer { client, port, _ ->
                val big = NdJson.encodeBatch((1L..200L).map { record(it) })

                assertEquals(413, client.send(port, big).status.value)
            }
        }

    @Test
    fun `a malformed line does not cost the batch`() =
        runTest {
            withServer { client, port, db ->
                val body =
                    listOf(
                        NdJson.encodeLine(record(1)),
                        "{ this is not json",
                        NdJson.encodeLine(record(2)),
                    ).joinToString("\n")

                val response = client.send(port, body)

                // Logs are not a transaction: one bad line is skipped, the rest is stored.
                assertEquals(202, response.status.value)
                assertEquals(2, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            }
        }

    @Test
    fun `suppressed keys ride on the response`() =
        runTest {
            withServer(suppressed = listOf("ip", "requestId")) { client, port, _ ->
                val body = client.send(port, NdJson.encodeBatch(listOf(record(1)))).bodyAsText()

                // The only channel through which the server's decision reaches the agent.
                assertTrue("\"suppressedKeys\":[\"ip\",\"requestId\"]" in body, body)
            }
        }

    @Test
    fun `an empty body is accepted and stores nothing`() =
        runTest {
            withServer { client, port, db ->
                assertEquals(202, client.send(port, "").status.value)
                assertEquals(0, db.scalar("SELECT count(*) FROM sqlite_master WHERE name LIKE 'log_entry_%'"))
            }
        }

    @Test
    fun `a redelivered batch is accepted without duplicating`() =
        runTest {
            withServer { client, port, db ->
                val body = NdJson.encodeBatch(listOf(record(1)))

                client.send(port, body, seq = "7")
                val second = client.send(port, body, seq = "7")

                // 202 again on purpose: the agent did its part, and telling it otherwise would
                // make it retry something already stored.
                assertEquals(202, second.status.value)
                assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            }
        }
}
