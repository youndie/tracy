package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.ExceptionInfo
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.TemplateCount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QueryRepositoryTest {
    private val day = 1785542400000L
    private val window = day - 1000 to day + 100_000

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-query-${Random.nextLong()}.db")

    private fun record(
        seq: Long,
        message: String = "order created",
        level: Level = Level.INFO,
        untrusted: Int? = null,
        fields: Map<String, JsonPrimitive>? = null,
        indexed: List<String>? = null,
        exception: ExceptionInfo? = null,
        traceId: String? = "4bf92f3577b34da6a3ce929d0e0e4736",
    ) = LogRecord(
        ts = day + seq,
        seq = seq,
        level = level,
        logger = "OrdersRouting",
        message = message,
        untrusted = untrusted,
        fields = fields,
        traceId = traceId,
        exception = exception,
        indexed = indexed,
    )

    private suspend fun seed(db: ISQLite) {
        val repo = IngestRepository(db, clock = { day })
        repo.write(
            BatchHeader("orders-api", "pod-a", "1.0.0", 1),
            listOf(
                record(1),
                record(2, "payment provider rejected", Level.ERROR, exception = ExceptionInfo("NoTransformationFoundException")),
                record(3, "order 8123 not found", untrusted = 1),
                record(4, fields = mapOf("orderId" to JsonPrimitive("12345")), indexed = listOf("orderId")),
            ),
        )
        repo.write(BatchHeader("billing", "pod-b", "1.0.0", 1), listOf(record(5, "charging card")))
    }

    @Test
    fun `a window returns what is in it`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = QueryRepository(db).searchLogs(since = window.first, until = window.second)

            assertEquals(5, result.items.size)
        }

    @Test
    fun `the result says out loud that it is a sample`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = QueryRepository(db).searchLogs(since = window.first, until = window.second)

            // Without this a reader concludes "it did not happen" from "it was not kept".
            assertTrue("Sampled" in result.note)
        }

    @Test
    fun `filters narrow the search`() =
        runTest {
            val db = freshDb()
            seed(db)
            val repository = QueryRepository(db)

            assertEquals(1, repository.searchLogs(service = "billing", since = window.first, until = window.second).items.size)
            assertEquals(1, repository.searchLogs(level = Level.ERROR, since = window.first, until = window.second).items.size)
            assertEquals(4, repository.searchLogs(instance = "pod-a", since = window.first, until = window.second).items.size)
        }

    @Test
    fun `an exception class is an exact match rather than a substring search`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result =
                QueryRepository(db).searchLogs(
                    exceptionClass = "NoTransformationFoundException",
                    since = window.first,
                    until = window.second,
                )

            assertEquals(1, result.items.size)
            assertEquals(Level.ERROR, result.items.single().level)
        }

    @Test
    fun `a template id leads straight from a frequency to the cases`() =
        runTest {
            val db = freshDb()
            seed(db)
            val repository = QueryRepository(db)
            val templateId =
                repository
                    .searchLogs(level = Level.ERROR, since = window.first, until = window.second)
                    .items
                    .single()
                    .templateId

            val byTemplate = repository.searchLogs(templateId = templateId, since = window.first, until = window.second)

            // This is the jump the docs promise: "what breaks most" to "show me those".
            assertEquals(1, byTemplate.items.size)
        }

    @Test
    fun `an interpolated record is grouped by its masked template`() =
        runTest {
            val db = freshDb()
            seed(db)

            val hit =
                QueryRepository(db)
                    .searchLogs(since = window.first, until = window.second)
                    .items
                    .single { it.untrusted }

            // The record keeps its own text, the template is masked — grouping without losing
            // what actually happened.
            assertEquals("order 8123 not found", hit.message)
        }

    @Test
    fun `field values never appear in a search result`() =
        runTest {
            val db = freshDb()
            seed(db)

            val hit =
                QueryRepository(db)
                    .searchLogs(since = window.first, until = window.second)
                    .items
                    .single { it.fieldKeys.isNotEmpty() }

            assertEquals(listOf("orderId"), hit.fieldKeys)
        }

    @Test
    fun `search can be narrowed by entity value`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result =
                QueryRepository(db).searchLogs(
                    entityKey = "orderId",
                    entityValue = "12345",
                    since = window.first,
                    until = window.second,
                )

            assertEquals(1, result.items.size)
        }

    @Test
    fun `truncation is reported with what is left`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = QueryRepository(db).searchLogs(since = window.first, until = window.second, limit = 2)

            assertEquals(2, result.items.size)
            assertTrue(result.truncated)
            assertTrue(result.remaining > 0)
        }

    @Test
    fun `frequencies come from counters and are exact`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            repo.write(
                BatchHeader("orders-api", "pod-a", "1.0.0", 1),
                listOf(
                    // One stored body, forty thousand occurrences: exactly the gap sampling opens
                    // and counters close (research D13).
                    record(1, "order created"),
                    TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 40_000),
                ),
            )

            val stats = QueryRepository(db).templateStats(since = day - 1000, until = day + 1000)

            assertTrue(stats.exact)
            assertEquals(40_000, stats.items.single().count)
        }

    @Test
    fun `a step turns a total into a series`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            repo.write(
                BatchHeader("orders-api", "pod-a", "1.0.0", 1),
                listOf(
                    TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 10),
                    TemplateCount(windowStart = day + 60_000, template = "order created", level = Level.INFO, count = 30),
                ),
            )

            val stats =
                QueryRepository(db).templateStats(
                    since = day - 1000,
                    until = day + 300_000,
                    stepMillis = 60_000,
                )

            assertEquals(
                listOf(10L, 30L),
                stats.items
                    .single()
                    .series
                    .map { it.count },
            )
        }

    @Test
    fun `releases are kept apart so a deploy can be blamed`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            val counter = TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 5)
            repo.write(BatchHeader("orders-api", "pod-a", "1.0.0", 1), listOf(counter))
            repo.write(BatchHeader("orders-api", "pod-a", "1.0.1", 2), listOf(counter.copy(count = 500)))

            val before = QueryRepository(db).templateStats(release = "1.0.0", since = day - 1000, until = day + 1000)
            val after = QueryRepository(db).templateStats(release = "1.0.1", since = day - 1000, until = day + 1000)

            assertEquals(5, before.items.single().count)
            assertEquals(500, after.items.single().count)
        }
}
