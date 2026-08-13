package ru.workinprogress.tracy.server.query

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.koin.ktor.plugin.Koin
import ru.workinprogress.tracy.server.ServerConfig
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.server.serverModule
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.TracyJson
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ServiceSummaryTest {
    private val day = 1785542400000L

    @Test
    fun `produced and stored are reported separately`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-${Random.nextLong()}.db")
            IngestRepository(db, clock = { day }).write(
                // The service produced a megabyte and dropped most of it; two records survived.
                BatchHeader("orders-api", "pod-a", "1.0", 1, producedBytes = 1_000_000, dropped = 42),
                (1L..2L).map {
                    LogRecord(ts = day + it, seq = it, level = Level.INFO, logger = "L", message = "order created")
                },
            )

            val server =
                embeddedServer(CIO, port = 0) {
                    // The same container production uses: the test now covers the wiring too,
                    // not only the handler.
                    install(Koin) { modules(serverModule(testConfig(), db)) }
                    install(Resources)
                    routing { queryRoutes() }
                }
            server.start(wait = false)
            val client = HttpClient()
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val body = client.get("http://127.0.0.1:$port/api/services").bodyAsText()
                val summaries = TracyJson.decodeFromString<List<ServiceSummary>>(body)

                val summary = summaries.single()
                // The gap between these two is the answer to "who is noisy". Reporting only what
                // was stored would report tracy's sampling policy back at the operator.
                assertEquals(1_000_000, summary.producedBytes)
                assertEquals(2, summary.storedRecords)
                assertEquals(1, summary.instances)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
            }
        }

    @Test
    fun `references are counted per key`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-refs-${Random.nextLong()}.db")
            IngestRepository(db, clock = { day }).write(
                BatchHeader("orders-api", "pod-a", "1.0", 1),
                (1L..5L).map {
                    LogRecord(
                        ts = day + it,
                        seq = it,
                        level = Level.INFO,
                        logger = "L",
                        message = "order touched",
                        fields = mapOf("orderId" to JsonPrimitive("order-$it")),
                        traceId = "4bf92f3577b34da6a3ce929d0e0e47" + it.toString().padStart(2, '0'),
                        indexed = listOf("orderId"),
                    )
                },
            )

            val server =
                embeddedServer(CIO, port = 0) {
                    // The same container production uses: the test now covers the wiring too,
                    // not only the handler.
                    install(Koin) { modules(serverModule(testConfig(), db)) }
                    install(Resources)
                    routing { queryRoutes() }
                }
            server.start(wait = false)
            val client = HttpClient()
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val summaries =
                    TracyJson.decodeFromString<List<ServiceSummary>>(
                        client.get("http://127.0.0.1:$port/api/services").bodyAsText(),
                    )

                // A key quietly filling the database shows up here before the disk does
                // (research risk 7).
                assertEquals(5, summaries.single().entityRefs["orderId"])
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
            }
        }

    @Test
    fun `an unindexed key is rejected with the real ones listed`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-key-${Random.nextLong()}.db")
            IngestRepository(db, clock = { day }).write(
                BatchHeader("orders-api", "pod-a", "1.0", 1),
                listOf(
                    LogRecord(
                        ts = day,
                        seq = 1,
                        level = Level.INFO,
                        logger = "L",
                        message = "order touched",
                        fields = mapOf("orderId" to JsonPrimitive("1")),
                        traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                        indexed = listOf("orderId"),
                    ),
                ),
            )

            val server =
                embeddedServer(CIO, port = 0) {
                    // The same container production uses: the test now covers the wiring too,
                    // not only the handler.
                    install(Koin) { modules(serverModule(testConfig(), db)) }
                    install(Resources)
                    routing { queryRoutes() }
                }
            server.start(wait = false)
            val client = HttpClient()
            try {
                val port =
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port
                val response =
                    client.get("http://127.0.0.1:$port/api/entities/total/500?since=0&until=${day + 1000}")

                assertEquals(400, response.status.value)
                val body = response.bodyAsText()
                // An empty 200 would read as "that never happened".
                assertTrue("not indexed" in body && "orderId" in body, body)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
            }
        }
}

private fun testConfig() = ServerConfig(httpPort = 0, dbPath = "unused", ingestKey = "k")
