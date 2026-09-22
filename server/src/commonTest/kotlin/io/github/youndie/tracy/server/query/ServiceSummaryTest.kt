package io.github.youndie.tracy.server.query

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.tracy.server.ServerConfig
import io.github.youndie.tracy.server.db.BatchHeader
import io.github.youndie.tracy.server.db.IngestRepository
import io.github.youndie.tracy.server.ingest.IngestBatchUseCase
import io.github.youndie.tracy.server.openDatabase
import io.github.youndie.tracy.server.serverModule
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.LogRecord
import io.github.youndie.tracy.wire.TracyJson
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
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ServiceSummaryTest {
    private val day = 1785542400000L

    /** One batch from a named generation, at [ts], whose agent claims to have sent it at [sentAt]. */
    private suspend fun writeBatch(
        db: ISQLite,
        instance: String,
        ts: Long,
        sentAt: Long = ts,
        seq: Long = 1,
    ) = IngestBatchUseCase(IngestRepository(db, clock = { ts }), clock = { ts })(
        BatchHeader("orders-api", instance, "1.0", seq, sentAt = sentAt),
        listOf(LogRecord(ts = ts, seq = seq, level = Level.INFO, logger = "L", message = "order created")),
    )

    @Test
    fun `produced and stored are reported separately`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-${Random.nextLong()}.db")
            IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })(
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

                // Through the real container, so the clock is the machine's: these records were
                // written at a fixed date long past, and nothing has reported since. One generation
                // ever, none of it inside the window, and therefore no clock reading at all —
                // absent rather than zero, because zero would claim the clocks agree.
                assertEquals(1, summary.instancesEverSeen)
                assertEquals(0, summary.instances)
                assertNull(summary.maxClockSkewMs)
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
            }
        }

    @Test
    fun `references are counted per key`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-refs-${Random.nextLong()}.db")
            IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })(
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
            IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })(
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

    @Test
    fun `a dead generation does not raise the skew of a live one`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-skew-${Random.nextLong()}.db")
            // A pod from three weeks ago whose clock was a minute out, and today's pod, whose is
            // five milliseconds out. Both rows stay in `instance` forever; only one is reporting.
            val old = day - 21 * 86_400_000L
            writeBatch(db, instance = "pod-old", ts = old, sentAt = old - 61_000)
            writeBatch(db, instance = "pod-now", ts = day, sentAt = day - 5, seq = 2)

            val summary = QueryRepository(db, clock = { day }).listServices().single()

            // The maximum used to be taken over every generation ever, so it could only rise: a
            // reading left by a deleted pod hid every smaller one behind it, which is exactly what
            // `clock_skew_ms` exists to surface (M-110).
            assertEquals(5, summary.maxClockSkewMs)
            assertEquals(1, summary.instances)
            assertEquals(2, summary.instancesEverSeen)
            assertEquals(24 * 60 * 60 * 1000L, summary.windowMs)
        }

    @Test
    fun `no instance in the window means no reading rather than a zero`() =
        runTest {
            val db = openDatabase("/tmp/tracy-summary-quiet-${Random.nextLong()}.db")
            val old = day - 21 * 86_400_000L
            writeBatch(db, instance = "pod-old", ts = old, sentAt = old - 61_000)

            val summary = QueryRepository(db, clock = { day }).listServices().single()

            // Absent, not zero. A zero here reads as "the clocks agree", and the service has not
            // said anything for three weeks — which `lastSeen` reports, unwindowed, on its own.
            assertNull(summary.maxClockSkewMs)
            assertNull(summary.maxRecordAgeMs)
            assertEquals(0, summary.instances)
            assertEquals(1, summary.instancesEverSeen)
            assertEquals(old, summary.lastSeen)
        }
}

private fun testConfig() = ServerConfig(httpPort = 0, dbPath = "unused", ingestKey = "k")
