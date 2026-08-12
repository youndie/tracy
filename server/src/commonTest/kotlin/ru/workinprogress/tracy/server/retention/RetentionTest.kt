package ru.workinprogress.tracy.server.retention

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.test.runTest
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.db.dayKey
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.TemplateCount
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RetentionTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-retention-${Random.nextLong()}.db")

    private suspend fun ISQLite.scalar(sql: String): Long? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private suspend fun writeDay(
        db: ISQLite,
        offsetDays: Int,
        seq: Long,
        count: Int = 5,
    ) {
        val ts = day + offsetDays * 86_400_000L
        IngestRepository(db, clock = { ts }).write(
            BatchHeader("orders-api", "pod-a", "1.0", seq),
            (1..count).map {
                LogRecord(ts = ts + it, seq = seq * 1000 + it, level = Level.INFO, logger = "L", message = "order created")
            },
        )
    }

    @Test
    fun `days older than the retention window are dropped whole`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1)
            writeDay(db, 5, 2)
            writeDay(db, 40, 3)
            val now = day + 40 * 86_400_000L

            val state =
                Retention(db, retentionDays = 30, countsRetentionDays = 90, maxBytes = Long.MAX_VALUE, clock = { now })
                    .enforce()

            // DROP TABLE, not DELETE: constant time, no fragmentation, no VACUUM (research D6).
            // Only the day within the window survives; the two beyond it are gone whole.
            assertEquals(listOf(dayKey(now)), state.liveDays)
        }

    @Test
    fun `dropping a day takes its spans and references with it`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1)
            val now = day + 100 * 86_400_000L

            Retention(db, retentionDays = 30, countsRetentionDays = 90, maxBytes = Long.MAX_VALUE, clock = { now })
                .enforce()

            for (prefix in listOf("log_entry_", "span_", "entity_ref_")) {
                assertEquals(
                    0,
                    db.scalar("SELECT count(*) FROM sqlite_master WHERE name = '${prefix}20260801'"),
                    "$prefix was left behind, pointing at a table that no longer exists",
                )
            }
        }

    @Test
    fun `the size cap evicts the oldest day`() =
        runTest {
            val db = freshDb()
            repeat(4) { writeDay(db, it, (it + 1).toLong(), count = 200) }
            val before = Retention(db, 30, 90, Long.MAX_VALUE, clock = { day }).state()

            val state =
                Retention(db, retentionDays = 30, countsRetentionDays = 90, maxBytes = before.databaseBytes / 2, clock = { day })
                    .enforce()

            // A collector that filled the node's disk is an outage it caused itself: the oldest
            // day goes rather than the newest write being refused.
            assertTrue(state.evictedDays > 0, "nothing was evicted")
            assertTrue(state.liveDays.size < 4)
        }

    @Test
    fun `eviction never removes the last day`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1, count = 100)

            val state = Retention(db, retentionDays = 30, countsRetentionDays = 90, maxBytes = 1, clock = { day }).enforce()

            // Dropping everything would leave a collector that collects nothing; the cap is a
            // budget, not a reason to become useless.
            assertEquals(1, state.liveDays.size)
        }

    @Test
    fun `counters outlive bodies`() =
        runTest {
            val db = freshDb()
            IngestRepository(db, clock = { day }).write(
                BatchHeader("orders-api", "pod-a", "1.0", 1),
                listOf(
                    LogRecord(ts = day, seq = 1, level = Level.INFO, logger = "L", message = "order created"),
                    TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 40_000),
                ),
            )
            val now = day + 40 * 86_400_000L

            Retention(db, retentionDays = 30, countsRetentionDays = 90, maxBytes = Long.MAX_VALUE, clock = { now })
                .enforce()

            // Bodies are gone, the frequency is not: counters are tiny and are wanted precisely
            // on the long horizon (research D13).
            assertEquals(40_000, db.scalar("SELECT count FROM template_count"))
        }

    @Test
    fun `state reports what an operator needs`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1)

            val state = Retention(db, 30, 90, 4L * 1024 * 1024 * 1024, clock = { day }).state()

            assertEquals("20260801", state.oldestDay)
            assertTrue(state.databaseBytes > 0)
            assertEquals(4L * 1024 * 1024 * 1024, state.maxBytes)
        }
}
