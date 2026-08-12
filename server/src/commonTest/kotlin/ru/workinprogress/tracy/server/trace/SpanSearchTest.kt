package ru.workinprogress.tracy.server.trace

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * M-45 — finding slow requests without knowing a trace id.
 *
 * Neither half of the stack could answer this before: `get_trace` needs the id, and metrik knows
 * the distribution of durations per route but not which request was which.
 */
class SpanSearchTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-spans-${Random.nextLong()}.db")

    private fun span(
        traceSuffix: Int,
        service: String,
        name: String,
        duration: Int,
        error: Int? = null,
    ) = Span(
        traceId = "4bf92f3577b34da6a3ce929d0e0e" + traceSuffix.toString().padStart(4, '0'),
        spanId = "00f067aa0ba9" + traceSuffix.toString().padStart(4, '0'),
        name = name,
        kind = SpanKind.SERVER,
        ts = day + traceSuffix,
        durationMs = duration,
        status = if (error == 1) 500 else 200,
        error = error,
    )

    private suspend fun seed(db: ISQLite) {
        val repo = IngestRepository(db, clock = { day })
        repo.write(
            BatchHeader("orders-api", "pod-a", "1.0", 1),
            listOf(
                span(1, "orders-api", "POST /orders", 40),
                span(2, "orders-api", "POST /orders", 2400),
                span(3, "orders-api", "GET /health", 1),
                span(4, "orders-api", "POST /orders", 900, error = 1),
            ),
        )
        repo.write(
            BatchHeader("billing", "pod-b", "1.0", 1),
            listOf(span(5, "billing", "POST /charge", 3200)),
        )
    }

    @Test
    fun `slow spans come back ordered by duration`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result =
                SpanSearchRepository(db)
                    .search(minDurationMs = 500, since = day - 1000, until = day + 100_000)

            assertEquals(listOf(3200, 2400, 900), result.hits.map { it.durationMs })
        }

    @Test
    fun `a hit carries the trace id so the next step is get_trace`() =
        runTest {
            val db = freshDb()
            seed(db)

            val slowest =
                SpanSearchRepository(db)
                    .search(minDurationMs = 3000, since = day - 1000, until = day + 100_000)
                    .hits
                    .single()

            // The point of this entry point: it hands over the key the rest of the product uses.
            assertEquals(32, slowest.traceId.length)
            assertEquals("billing", slowest.service)
        }

    @Test
    fun `filtering by service and name narrows the search`() =
        runTest {
            val db = freshDb()
            seed(db)
            val repository = SpanSearchRepository(db)

            val byService = repository.search(service = "billing", since = day - 1000, until = day + 100_000)
            val byName = repository.search(name = "GET /health", since = day - 1000, until = day + 100_000)

            assertEquals(1, byService.hits.size)
            assertEquals(1, byName.hits.size)
            assertEquals("GET /health", byName.hits.single().name)
        }

    @Test
    fun `errors can be singled out`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result =
                SpanSearchRepository(db)
                    .search(onlyErrors = true, since = day - 1000, until = day + 100_000)

            assertEquals(1, result.hits.size)
            assertTrue(result.hits.single().error)
        }

    @Test
    fun `the window excludes what is outside it`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = SpanSearchRepository(db).search(since = day + 50_000, until = day + 100_000)

            assertTrue(result.hits.isEmpty())
        }

    @Test
    fun `the result says it is a sample`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = SpanSearchRepository(db).search(since = day - 1000, until = day + 100_000)

            // Spans follow the trace decision, so this is a sample of slow requests and not the
            // full set. Saying so is the difference between a useful answer and a wrong one.
            assertTrue(result.sampled)
        }

    @Test
    fun `a limit truncates and admits it`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = SpanSearchRepository(db).search(since = day - 1000, until = day + 100_000, limit = 2)

            assertEquals(2, result.hits.size)
            assertTrue(result.truncated)
        }
}
