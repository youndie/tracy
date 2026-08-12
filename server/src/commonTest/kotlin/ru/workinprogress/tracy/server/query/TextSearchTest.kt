package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Text search over templates.
 *
 * The index holds one row per distinct message shape rather than one per record (research D5), so
 * what these tests pin down is not only that search works but what it searches: the developer's
 * template, never the values a caller supplied.
 */
class TextSearchTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-fts-${Random.nextLong()}.db")

    private suspend fun seed(db: ISQLite) {
        IngestRepository(db, clock = { day }).write(
            BatchHeader("orders-api", "pod-a", "1.0", 1),
            listOf(
                LogRecord(day + 1, 1, Level.WARN, "Payments", "payment gateway timeout"),
                // Interpolated, and therefore marked untrusted — that is the contract, and it is
                // what sends the message through the normalizer on the way into the index.
                LogRecord(day + 2, 2, Level.INFO, "Orders", "order 12345 created", untrusted = 1),
                LogRecord(day + 3, 3, Level.ERROR, "Payments", "payment declined by issuer"),
            ),
        )
    }

    @Test
    fun `a substring of the template finds its records`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = QueryRepository(db).searchLogs(since = 0, until = Long.MAX_VALUE, query = "payment")

            assertEquals(2, result.items.size)
            assertTrue(result.items.all { "payment" in it.message })
        }

    @Test
    fun `a substring inside a word matches too`() =
        runTest {
            val db = freshDb()
            seed(db)

            // What the trigram tokenizer buys: no prefix anchoring, so `ateway` hits.
            val result = QueryRepository(db).searchLogs(since = 0, until = Long.MAX_VALUE, query = "ateway")

            assertEquals(1, result.items.size)
        }

    @Test
    fun `text that matches nothing yields nothing rather than everything`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result = QueryRepository(db).searchLogs(since = 0, until = Long.MAX_VALUE, query = "kubernetes")

            // A filter that matches no template must not silently fall through to an unfiltered
            // read — that failure looks like a working search returning the whole window.
            assertEquals(0, result.items.size)
        }

    @Test
    fun `fts syntax in the query is text and not syntax`() =
        runTest {
            val db = freshDb()
            seed(db)
            val repository = QueryRepository(db)

            // Each of these is a parse error or an operator if passed through raw. Bound as a
            // phrase they are ordinary characters, so the worst case is an empty result.
            for (hostile in listOf("payment OR order", "payment*", "\"payment", "payment NEAR/2 gateway", "(payment)")) {
                val result = repository.searchLogs(since = 0, until = Long.MAX_VALUE, query = hostile)

                assertEquals(0, result.items.size, "`$hostile` should match nothing, not throw and not match all")
            }
        }

    @Test
    fun `an interpolated value is not searchable text but its shape is`() =
        runTest {
            val db = freshDb()
            seed(db)
            val repository = QueryRepository(db)

            // "order 12345 created" is indexed as "order <num> created": the number is gone. This
            // is the contract rather than a limitation to work around — an index over values would
            // grow with volume instead of with the number of distinct shapes (research D5).
            assertEquals(0, repository.searchLogs(since = 0, until = Long.MAX_VALUE, query = "12345").items.size)
            assertEquals(1, repository.searchLogs(since = 0, until = Long.MAX_VALUE, query = "order").items.size)
        }

    @Test
    fun `a query shorter than a trigram is refused rather than answered empty`() =
        runTest {
            val db = freshDb()
            seed(db)

            val failure =
                assertFailsWith<IllegalArgumentException> {
                    QueryRepository(db).searchLogs(since = 0, until = Long.MAX_VALUE, query = "up")
                }

            assertTrue("3 characters" in failure.message.orEmpty(), failure.message.orEmpty())
        }

    @Test
    fun `text search composes with the other filters`() =
        runTest {
            val db = freshDb()
            seed(db)

            val result =
                QueryRepository(db).searchLogs(
                    since = 0,
                    until = Long.MAX_VALUE,
                    query = "payment",
                    level = Level.ERROR,
                )

            assertEquals(1, result.items.size)
            assertEquals(Level.ERROR, result.items.first().level)
        }
}
