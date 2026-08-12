package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The support-desk axis: an `orderId` in hand, not a `traceId`, and a lifetime split across
 * traces on purpose (research D12).
 */
class EntityRepositoryTest {
    private val day = 1785542400000L
    private val hourLater = day + 3_600_000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-entity-${Random.nextLong()}.db")

    private fun record(
        seq: Long,
        traceId: String,
        value: String,
    ) = LogRecord(
        ts = day + seq,
        seq = seq,
        level = Level.INFO,
        logger = "L",
        message = "order created",
        fields = mapOf("orderId" to JsonPrimitive(value)),
        traceId = traceId,
        indexed = listOf("orderId"),
    )

    @Test
    fun `an entity is followed across two traces an hour apart`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            val traceA = "4bf92f3577b34da6a3ce929d0e0e4736"
            val traceB = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

            repo.write(BatchHeader("orders-api", "pod-a", null, 1), listOf(record(1, traceA, "12345")))
            repo.write(
                BatchHeader("worker", "pod-w", null, 1),
                listOf(EntityRef(traceId = traceB, key = "orderId", value = "12345", ts = hourLater)),
            )

            val timeline =
                EntityRepository(db).timeline("orderId", "12345", since = day - 1000, until = hourLater + 1000)

            // Two traces, no common trace id — the axis get_trace cannot answer at all.
            assertEquals(2, timeline.touches.size)
            assertEquals(listOf("orders-api", "worker"), timeline.touches.map { it.service })
            assertEquals(listOf(traceA, traceB), timeline.touches.map { it.traceId })
        }

    @Test
    fun `a reference without a body is data rather than nothing`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            repo.write(
                BatchHeader("orders-api", "pod-a", null, 1),
                listOf(EntityRef(traceId = "4bf92f3577b34da6a3ce929d0e0e4736", key = "orderId", value = "12345", ts = day)),
            )

            val touch =
                EntityRepository(db)
                    .timeline("orderId", "12345", since = day - 1000, until = day + 1000)
                    .touches
                    .single()

            // The normal case for a successful request, which is what support asks about.
            assertNull(touch.entryId)
            assertEquals("4bf92f3577b34da6a3ce929d0e0e4736", touch.traceId)
        }

    @Test
    fun `a reference with a body points at it`() =
        runTest {
            val db = freshDb()
            IngestRepository(db, clock = { day })
                .write(BatchHeader("orders-api", "pod-a", null, 1), listOf(record(1, "4bf92f3577b34da6a3ce929d0e0e4736", "12345")))

            val touch =
                EntityRepository(db)
                    .timeline("orderId", "12345", since = day - 1000, until = day + 1000)
                    .touches
                    .single()

            assertTrue(touch.entryId != null)
        }

    @Test
    fun `an unindexed key is an error with the list of real ones`() =
        runTest {
            val db = freshDb()
            IngestRepository(db, clock = { day })
                .write(BatchHeader("orders-api", "pod-a", null, 1), listOf(record(1, "4bf92f3577b34da6a3ce929d0e0e4736", "12345")))

            // An empty result would read as "that never happened" when the truth is "nobody ever
            // indexed this key".
            val failure =
                assertFailsWith<UnknownEntityKey> {
                    EntityRepository(db).timeline("total", "500", since = day - 1000, until = day + 1000)
                }
            assertEquals(listOf("orderId"), failure.indexed)
        }

    @Test
    fun `top values are counted for an investigation`() =
        runTest {
            val db = freshDb()
            val repo = IngestRepository(db, clock = { day })
            repo.write(
                BatchHeader("orders-api", "pod-a", null, 1),
                (1L..10L).map { EntityRef("4bf92f3577b34da6a3ce929d0e0e4736", "ip", if (it <= 7) "10.0.0.1" else "10.0.0.2", day + it) },
            )

            val top = EntityRepository(db).top("ip", since = day - 1000, until = day + 1000)

            assertEquals(listOf("10.0.0.1" to 7L, "10.0.0.2" to 3L), top.values.map { it.value to it.count })
        }

    @Test
    fun `a window excludes what is outside it`() =
        runTest {
            val db = freshDb()
            IngestRepository(db, clock = { day })
                .write(BatchHeader("orders-api", "pod-a", null, 1), listOf(record(1, "4bf92f3577b34da6a3ce929d0e0e4736", "12345")))

            val timeline =
                EntityRepository(db).timeline("orderId", "12345", since = day + 50_000, until = day + 60_000)

            assertTrue(timeline.touches.isEmpty())
        }
}
