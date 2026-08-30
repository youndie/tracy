package ru.workinprogress.tracy.server.trace

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.ingest.IngestBatchUseCase
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceNode
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M-43: three services, one trace, assembled the way a real chain arrives — each service sending
 * its own batch, independently, in whatever order.
 *
 * This is the scenario the product exists for (research D1): a `traceId` in hand, and the whole
 * chain out of it.
 */
class TraceEndToEndTest {
    private val day = 1785542400000L
    private val trace = "4bf92f3577b34da6a3ce929d0e0e4736"

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-e2e-${Random.nextLong()}.db")

    private fun span(
        id: String,
        parent: String?,
        name: String,
        kind: SpanKind,
        ts: Long,
        duration: Int?,
        status: Int? = 200,
        error: Int? = null,
    ) = Span(
        traceId = trace,
        spanId = id,
        parentSpanId = parent,
        name = name,
        kind = kind,
        ts = ts,
        durationMs = duration,
        status = status,
        error = error,
    )

    private fun record(
        seq: Long,
        spanId: String?,
        message: String,
        level: Level = Level.INFO,
        fields: Map<String, JsonPrimitive>? = null,
    ) = LogRecord(
        ts = day + seq,
        seq = seq,
        level = level,
        logger = "L",
        message = message,
        fields = fields,
        traceId = trace,
        spanId = spanId,
    )

    private suspend fun IngestBatchUseCase.send(
        service: String,
        seq: Long,
        lines: List<BatchLine>,
    ) = this(BatchHeader(service, "$service-pod", "1.0.0", seq), lines)

    private fun TraceNode.find(name: String): TraceNode? =
        if (this.name ==
            name
        ) {
            this
        } else {
            children.firstNotNullOfOrNull { it.find(name) }
        }

    @Test
    fun `three services become one tree`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            // Deliberately out of order: B reports before A, which is what happens when the
            // callee finishes first and the caller is still writing its response.
            repo.send(
                "billing",
                1,
                listOf(
                    span("bbbbbbbbbbbbbbbb", "cccccccccccccccc", "POST /charge", SpanKind.SERVER, day + 12, 348),
                    span(
                        "dddddddddddddddd",
                        "bbbbbbbbbbbbbbbb",
                        "GET https://provider/pay",
                        SpanKind.CLIENT,
                        day + 14,
                        300,
                    ),
                    record(2, "bbbbbbbbbbbbbbbb", "charging card"),
                ),
            )
            repo.send(
                "orders-api",
                1,
                listOf(
                    span("aaaaaaaaaaaaaaaa", null, "POST /orders", SpanKind.SERVER, day, 412),
                    span(
                        "cccccccccccccccc",
                        "aaaaaaaaaaaaaaaa",
                        "GET https://billing/charge",
                        SpanKind.CLIENT,
                        day + 10,
                        350,
                    ),
                    record(1, "aaaaaaaaaaaaaaaa", "order accepted"),
                    record(3, "aaaaaaaaaaaaaaaa", "payment provider rejected", Level.ERROR),
                ),
            )

            val view = TraceRepository(db).load(trace)

            val root = view.roots.single()
            assertEquals("POST /orders", root.name)
            assertEquals("orders-api", root.service)

            val toBilling = assertNotNull(root.find("GET https://billing/charge"))
            val billing = toBilling.children.single()
            assertEquals("billing", billing.service)
            assertEquals("POST /charge", billing.name)

            // The chain reassembles across services from independent batches, which is the whole
            // claim of the product.
            assertEquals(listOf("order accepted", "payment provider rejected"), root.logs.map { it.message })
            assertEquals(listOf("charging card"), billing.logs.map { it.message })
        }

    @Test
    fun `a service that stored nothing shows as a lost link`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            // The provider is not instrumented at all: B calls it and nothing comes back.
            repo.send(
                "billing",
                1,
                listOf(
                    span("bbbbbbbbbbbbbbbb", null, "POST /charge", SpanKind.SERVER, day, 348),
                    span(
                        "dddddddddddddddd",
                        "bbbbbbbbbbbbbbbb",
                        "GET https://provider/pay",
                        SpanKind.CLIENT,
                        day + 2,
                        300,
                    ),
                ),
            )

            val view = TraceRepository(db).load(trace)
            val call =
                view.roots
                    .single()
                    .children
                    .single()

            // "The provider was not called" would be the wrong conclusion, and an empty subtree
            // is exactly what invites it.
            assertTrue(call.noRemoteData)
            assertEquals(300, call.durationMs)
        }

    @Test
    fun `unattributed time surfaces where nobody instrumented`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo.send(
                "orders-api",
                1,
                listOf(
                    span("aaaaaaaaaaaaaaaa", null, "POST /orders", SpanKind.SERVER, day, 412),
                    span(
                        "cccccccccccccccc",
                        "aaaaaaaaaaaaaaaa",
                        "GET https://billing/charge",
                        SpanKind.CLIENT,
                        day + 10,
                        350,
                    ),
                ),
            )

            // 412 minus 350: the database call nobody wrapped in withSpan.
            assertEquals(
                62,
                TraceRepository(db)
                    .load(trace)
                    .roots
                    .single()
                    .unattributedMs,
            )
        }

    @Test
    fun `an unterminated span survives and is marked`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo.send(
                "orders-api",
                1,
                listOf(span("aaaaaaaaaaaaaaaa", null, "POST /orders", SpanKind.SERVER, day, null, status = null)),
            )

            assertTrue(
                TraceRepository(db)
                    .load(trace)
                    .roots
                    .single()
                    .unterminated,
            )
        }

    @Test
    fun `records without a span stay at trace level`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo.send(
                "orders-api",
                1,
                listOf(
                    span("aaaaaaaaaaaaaaaa", null, "POST /orders", SpanKind.SERVER, day, 412),
                    record(5, null, "written from non-suspend code"),
                ),
            )

            val view = TraceRepository(db).load(trace)
            assertEquals(1, view.looseLogs.size)
            assertEquals("written from non-suspend code", view.looseLogs.single().message)
        }

    @Test
    fun `a trace spanning midnight is assembled from both partitions`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })
            val nextDay = day + 86_400_000L

            repo.send(
                "orders-api",
                1,
                listOf(
                    span(
                        "aaaaaaaaaaaaaaaa",
                        null,
                        "POST /orders",
                        SpanKind.SERVER,
                        day + 86_399_000,
                        3000,
                    ),
                ),
            )
            repo.send(
                "billing",
                1,
                listOf(
                    span("bbbbbbbbbbbbbbbb", "aaaaaaaaaaaaaaaa", "POST /charge", SpanKind.SERVER, nextDay + 500, 200),
                ),
            )

            // Daily slicing is what makes retention a DROP TABLE; a trace that crosses midnight
            // must not pay for it by falling in half.
            val root = TraceRepository(db).load(trace).roots.single()
            assertEquals("POST /charge", root.children.single().name)
        }

    @Test
    fun `an unknown trace is an empty answer rather than an error`() =
        runTest {
            val db = freshDb()

            val view = TraceRepository(db).load("0123456789abcdef0123456789abcdef")

            assertTrue(view.roots.isEmpty())
            assertTrue(view.looseLogs.isEmpty())
        }

    @Test
    fun `field values do not leak into the tree`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo.send(
                "orders-api",
                1,
                listOf(
                    span("aaaaaaaaaaaaaaaa", null, "POST /orders", SpanKind.SERVER, day, 10),
                    record(1, "aaaaaaaaaaaaaaaa", "order created", fields = mapOf("orderId" to JsonPrimitive("12345"))),
                ),
            )

            val line =
                TraceRepository(db)
                    .load(trace)
                    .roots
                    .single()
                    .logs
                    .single()
            // Structure now, values on request: the same split the MCP contract makes into two
            // phases (research D8).
            assertEquals(listOf("orderId"), line.fieldKeys)
        }
}
