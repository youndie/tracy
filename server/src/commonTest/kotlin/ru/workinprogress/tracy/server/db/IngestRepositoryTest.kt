package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.ingest.IngestBatchUseCase
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.ExceptionInfo
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TemplateCount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Exercised against a real SQLite file rather than a fake.
 *
 * The things that can break here — FTS5 being compiled in, `unhex`, an upsert on a composite key,
 * a partition created on first use — are all properties of the engine. A fake would confirm the
 * Kotlin and none of the SQL.
 */
class IngestRepositoryTest {
    private val day = 1785542400000L // 2026-08-01 UTC, fixed so the partition name is predictable

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-test-${Random.nextLong()}.db")

    private fun header(
        seq: Long = 1,
        release: String? = "1.4.212",
    ) = BatchHeader(service = "orders-api", instance = "orders-api-1", release = release, seq = seq)

    private fun record(
        seq: Long = 1,
        message: String = "order created",
        level: Level = Level.INFO,
        traceId: String? = "4bf92f3577b34da6a3ce929d0e0e4736",
        fields: Map<String, JsonPrimitive>? = null,
        indexed: List<String>? = null,
        exception: ExceptionInfo? = null,
        untrusted: Int? = null,
    ) = LogRecord(
        ts = day + seq,
        seq = seq,
        level = level,
        logger = "OrdersRouting",
        message = message,
        untrusted = untrusted,
        fields = fields,
        traceId = traceId,
        spanId = "00f067aa0ba902b7",
        exception = exception,
        indexed = indexed,
    )

    private suspend fun ISQLite.scalar(sql: String): Long? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private suspend fun ISQLite.text(sql: String): String? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asStringOrNull()

    @Test
    fun `a batch is stored and counted`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            val result = repo(header(), listOf(record(1), record(2, message = "order paid")))

            assertEquals(2, result.accepted)
            assertTrue(!result.duplicate)
            assertEquals(2, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `the partition is created on first use`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            assertNull(
                db
                    .scalar("SELECT count(*) FROM sqlite_master WHERE name = 'log_entry_20260801'")
                    ?.takeIf { it > 0 },
            )

            repo(header(), listOf(record()))

            assertEquals(
                1,
                db.scalar("SELECT count(*) FROM sqlite_master WHERE name = 'log_entry_20260801'"),
            )
        }

    @Test
    fun `a structured message is stored once as a template`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(1), (1L..50L).map { record(it) })

            // Fifty records, one template, and no per-row copy of the text (research D5).
            assertEquals(1, db.scalar("SELECT count(*) FROM log_template"))
            assertEquals(50, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            assertEquals(0, db.scalar("SELECT count(*) FROM log_entry_20260801 WHERE raw_message IS NOT NULL"))
        }

    @Test
    fun `an interpolated message keeps its raw text`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record(message = "order 8123 not found", untrusted = 1)))

            assertEquals(
                "order 8123 not found",
                db.text("SELECT raw_message FROM log_entry_20260801"),
            )
            assertEquals(1, db.scalar("SELECT untrusted FROM log_entry_20260801"))
        }

    @Test
    fun `templates are searchable through fts`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record(message = "payment provider rejected")))

            // The trigram tokenizer is what makes a substring query use the index. This is the
            // single fact the whole storage design rests on (research 1.1).
            //
            // The index is contentless (`content=''`), so it yields rowids and not text — that is
            // the point of contentless_delete, and the text is joined back from log_template.
            val found =
                db.text(
                    """SELECT t.text FROM template_fts f JOIN log_template t ON t.id = f.rowid
                       WHERE template_fts MATCH '"provider rej"'""",
                )
            assertEquals("payment provider rejected", found)
        }

    @Test
    fun `a trace id is stored as bytes and reads back as hex`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record()))

            assertEquals(16, db.scalar("SELECT length(trace_id) FROM log_entry_20260801"))
            assertEquals(
                "4bf92f3577b34da6a3ce929d0e0e4736",
                db.text("SELECT lower(hex(trace_id)) FROM log_entry_20260801"),
            )
        }

    @Test
    fun `a redelivered batch changes nothing`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })
            val lines =
                listOf(
                    record(),
                    TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 7),
                )

            repo(header(seq = 4218), lines)
            val second = repo(header(seq = 4218), lines)

            assertTrue(second.duplicate)
            assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            // A duplicate would not add a counter row, it would inflate the number in it — which
            // is exactly the failure idempotency exists to prevent.
            assertEquals(7, db.scalar("SELECT count FROM template_count"))
        }

    @Test
    fun `counters from different instances are summed`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })
            val counter = TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 5)

            repo(BatchHeader("orders-api", "pod-a", "1.0", 1), listOf(counter))
            repo(BatchHeader("orders-api", "pod-b", "1.0", 1), listOf(counter))

            assertEquals(1, db.scalar("SELECT count(*) FROM template_count"))
            assertEquals(10, db.scalar("SELECT count FROM template_count"))
        }

    @Test
    fun `counters keep releases apart`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })
            val counter = TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 3)

            repo(BatchHeader("orders-api", "pod-a", "1.0.0", 1), listOf(counter))
            repo(BatchHeader("orders-api", "pod-a", "1.0.1", 2), listOf(counter))

            // Without this dimension "did it get worse after the deploy" cannot be answered.
            assertEquals(2, db.scalar("SELECT count(*) FROM template_count"))
        }

    @Test
    fun `a record with a marked field produces a reference bound to it`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(
                header(),
                listOf(record(fields = mapOf("orderId" to JsonPrimitive("12345")), indexed = listOf("orderId"))),
            )

            assertEquals(1, db.scalar("SELECT count(*) FROM entity_ref_20260801"))
            assertEquals(
                1,
                db.scalar("SELECT count(*) FROM entity_ref_20260801 WHERE entry_id IS NOT NULL"),
            )
        }

    @Test
    fun `a standalone reference is stored without a body`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(
                header(),
                listOf(EntityRef(traceId = "4bf92f3577b34da6a3ce929d0e0e4736", key = "orderId", value = "12345", ts = day)),
            )

            // The normal case for a successful request, not a degenerate one (research D12).
            assertEquals(1, db.scalar("SELECT count(*) FROM entity_ref_20260801"))
            assertNull(db.scalar("SELECT entry_id FROM entity_ref_20260801"))
            assertEquals(0, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `a span is stored in the same transaction as its records`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(
                header(),
                listOf(
                    record(),
                    Span(
                        traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                        spanId = "00f067aa0ba902b7",
                        name = "POST /orders",
                        kind = SpanKind.SERVER,
                        ts = day,
                        durationMs = 412,
                        status = 200,
                    ),
                ),
            )

            assertEquals(1, db.scalar("SELECT count(*) FROM span_20260801"))
            assertEquals("server", db.text("SELECT kind FROM span_20260801"))
            assertEquals(412, db.scalar("SELECT duration_ms FROM span_20260801"))
        }

    @Test
    fun `an unterminated span keeps a null duration`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(
                header(),
                listOf(
                    Span("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", name = "POST /orders", kind = SpanKind.SERVER, ts = day),
                ),
            )

            assertNull(db.scalar("SELECT duration_ms FROM span_20260801"))
        }

    @Test
    fun `the exception class is interned`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })
            val exception = ExceptionInfo("NoTransformationFoundException", "no transformation", "at ...")

            repo(header(), (1L..10L).map { record(it, level = Level.ERROR, exception = exception) })

            assertEquals(1, db.scalar("SELECT count(*) FROM exception_class"))
            assertEquals(
                "NoTransformationFoundException",
                db.text("SELECT name FROM exception_class"),
            )
        }

    @Test
    fun `records of different days land in different partitions`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record(), record(2).copy(ts = day + 86_400_000L)))

            assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260801"))
            assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260802"))
        }

    @Test
    fun `clock skew is recorded rather than corrected`() =
        runTest {
            val db = freshDb()
            // The server clock is ten seconds ahead of the source.
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day + 10_000 }), clock = { day + 10_000 })

            repo(header(), listOf(record(seq = 1)))

            val skew = db.scalar("SELECT clock_skew_ms FROM instance")
            assertTrue(skew != null && skew >= 9_000, "skew was $skew")
            // The stored timestamp is still the source one: silently "fixing" it would reorder
            // cause and effect across pods without saying so (research risk 6).
            assertEquals(day + 1, db.scalar("SELECT ts FROM log_entry_20260801"))
        }

    @Test
    fun `the service and instance are created on first sight`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record()))

            assertEquals("orders-api", db.text("SELECT name FROM service"))
            assertEquals("orders-api-1", db.text("SELECT name FROM instance"))
        }

    @Test
    fun `fields are stored as json`() =
        runTest {
            val db = freshDb()
            val repo = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

            repo(header(), listOf(record(fields = mapOf("orderId" to JsonPrimitive("12345")))))

            assertEquals(
                "12345",
                db.text("SELECT json_extract(fields, '$.orderId') FROM log_entry_20260801"),
            )
        }
}
