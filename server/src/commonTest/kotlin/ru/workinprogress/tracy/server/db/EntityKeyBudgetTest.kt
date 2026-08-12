package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EntityKeyBudgetTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-budget-${Random.nextLong()}.db")

    private suspend fun ISQLite.scalar(sql: String): Long? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private fun record(
        seq: Long,
        value: String,
    ) = LogRecord(
        ts = day,
        seq = seq,
        level = Level.INFO,
        logger = "L",
        message = "order touched",
        fields = mapOf("orderId" to JsonPrimitive(value)),
        traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
        indexed = listOf("orderId"),
    )

    private fun repo(
        db: ISQLite,
        budget: EntityKeyBudget,
    ) = IngestRepository(db, budget = budget, clock = { day })

    @Test
    fun `a key within budget is not suppressed`() =
        runTest {
            val db = freshDb()
            val budget = EntityKeyBudget(db, refsPerMinute = 100, suppressedTtlMillis = 1_000_000, clock = { day })

            repo(db, budget).write(
                BatchHeader("orders-api", "pod-a", null, 1),
                (1L..10L).map { record(it, "order-$it") },
            )

            assertEquals(10, db.scalar("SELECT count(*) FROM entity_ref_20260801"))
            assertEquals(emptyList(), budget.suppressedFor("orders-api"))
        }

    @Test
    fun `a key over budget trips and stops being indexed`() =
        runTest {
            val db = freshDb()
            val budget = EntityKeyBudget(db, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { day })

            repo(db, budget).write(
                BatchHeader("orders-api", "pod-a", null, 1),
                (1L..50L).map { record(it, "order-$it") },
            )

            val stored = db.scalar("SELECT count(*) FROM entity_ref_20260801") ?: 0
            assertTrue(stored in 1..10, "expected the breaker to stop the flood, stored $stored")
            assertEquals(listOf("orderId"), budget.suppressedFor("orders-api"))
        }

    @Test
    fun `the latch survives a restart of the server`() =
        runTest {
            val path = "/tmp/tracy-budget-restart-${Random.nextLong()}.db"
            val first = openDatabase(path)
            val budget = EntityKeyBudget(first, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { day })
            repo(first, budget).write(
                BatchHeader("orders-api", "pod-a", null, 1),
                (1L..50L).map { record(it, "order-$it") },
            )

            // A fresh process with fresh in-memory state, same file.
            val second = openDatabase(path)
            val restarted =
                EntityKeyBudget(second, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { day })

            // In a cluster a restart happens on its own. A breaker that re-arms on every deploy
            // is the same as no breaker (research D15).
            assertEquals(listOf("orderId"), restarted.suppressedFor("orders-api"))
        }

    @Test
    fun `the next minute does not release the latch`() =
        runTest {
            val db = freshDb()
            var now = day
            val budget = EntityKeyBudget(db, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { now })
            val repository = IngestRepository(db, budget = budget, clock = { now })

            repository.write(BatchHeader("orders-api", "pod-a", null, 1), (1L..50L).map { record(it, "o$it") })
            val afterFirst = db.scalar("SELECT count(*) FROM entity_ref_20260801") ?: 0

            now += 60_000
            repository.write(BatchHeader("orders-api", "pod-a", null, 2), (51L..80L).map { record(it, "o$it") })

            // Releasing every minute would index the first N references of each minute and drop
            // the rest — partial data that looks complete.
            assertEquals(afterFirst, db.scalar("SELECT count(*) FROM entity_ref_20260801"))
        }

    @Test
    fun `unsuppress releases the key and is idempotent`() =
        runTest {
            val db = freshDb()
            val budget = EntityKeyBudget(db, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { day })
            repo(db, budget).write(
                BatchHeader("orders-api", "pod-a", null, 1),
                (1L..50L).map { record(it, "order-$it") },
            )

            assertTrue(budget.unsuppress("orderId"))
            assertEquals(emptyList(), budget.suppressedFor("orders-api"))
            assertTrue(budget.unsuppress("orderId"), "a repeat must not be an error")
        }

    @Test
    fun `unsuppressing an unknown key reports that it is unknown`() =
        runTest {
            val db = freshDb()
            val budget = EntityKeyBudget(db, refsPerMinute = 5, suppressedTtlMillis = 1_000_000, clock = { day })

            assertTrue(!budget.unsuppress("neverSeen"))
        }

    @Test
    fun `a silent key expires`() =
        runTest {
            val db = freshDb()
            var now = day
            val budget = EntityKeyBudget(db, refsPerMinute = 5, suppressedTtlMillis = 1_000, clock = { now })
            IngestRepository(db, budget = budget, clock = { now })
                .write(BatchHeader("orders-api", "pod-a", null, 1), (1L..50L).map { record(it, "o$it") })

            now += 10_000
            budget.expireStale()

            // Safe only because nothing arrived under that key in the meantime: expiry removes a
            // stale row, it never re-enables a key that is still firing.
            assertEquals(emptyList(), budget.suppressedFor("orders-api"))
        }
}
