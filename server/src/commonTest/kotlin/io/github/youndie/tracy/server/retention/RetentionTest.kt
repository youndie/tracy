package io.github.youndie.tracy.server.retention

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.tracy.server.db.BatchHeader
import io.github.youndie.tracy.server.db.IngestRepository
import io.github.youndie.tracy.server.db.Partitions
import io.github.youndie.tracy.server.db.dayKey
import io.github.youndie.tracy.server.ingest.IngestBatchUseCase
import io.github.youndie.tracy.server.openDatabase
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.LogRecord
import io.github.youndie.tracy.wire.TemplateCount
import kotlinx.coroutines.test.runTest
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
        partitions: Partitions = Partitions(),
    ) {
        val ts = day + offsetDays * 86_400_000L
        IngestBatchUseCase(IngestRepository(db, partitions = partitions, clock = { ts }), clock = { ts })(
            BatchHeader("orders-api", "pod-a", "1.0", seq),
            (1..count).map {
                LogRecord(
                    ts = ts + it,
                    seq = seq * 1000 + it,
                    level = Level.INFO,
                    logger = "L",
                    message = "order created",
                )
            },
        )
    }

    /** One batch, with its marker, at a chosen time. [records] = 0 writes a marker and nothing else. */
    private suspend fun writeBatchAt(
        db: ISQLite,
        ts: Long,
        seq: Long,
        records: Int = 1,
        partitions: Partitions = Partitions(),
    ) = IngestBatchUseCase(IngestRepository(db, partitions = partitions, clock = { ts }), clock = { ts })(
        BatchHeader("orders-api", "pod-a", "1.0", seq),
        (1..records).map {
            LogRecord(ts = ts + it, seq = seq * 1000 + it, level = Level.INFO, logger = "L", message = "order created")
        },
    )

    @Test
    fun `days older than the retention window are dropped whole`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1)
            writeDay(db, 5, 2)
            writeDay(db, 40, 3)
            val now = day + 40 * 86_400_000L

            val state =
                Retention(
                    db,
                    Partitions(),
                    walBytes = { 0 },
                    retentionDays = 30,
                    countsRetentionDays = 90,
                    markersRetentionDays = 2,
                    maxBytes = Long.MAX_VALUE,
                    clock = { now },
                ).enforce()

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

            Retention(
                db,
                Partitions(),
                walBytes = { 0 },
                retentionDays = 30,
                countsRetentionDays = 90,
                markersRetentionDays = 2,
                maxBytes = Long.MAX_VALUE,
                clock = { now },
            ).enforce()

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
            repeat(2) { writeDay(db, it, (it + 1).toLong(), count = 200) }

            // The cap is measured, not guessed: exactly what two days occupy, plus a page of slack
            // for the shared tables. Two of the four days have to go to get back under it — and,
            // just as importantly, only two.
            val twoDays =
                Retention(db, Partitions(), { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { day })
                    .state()
                    .usedBytes + 8192

            repeat(2) { writeDay(db, it + 2, (it + 3).toLong(), count = 200) }

            val state =
                Retention(
                    db,
                    Partitions(),
                    walBytes = { 0 },
                    retentionDays = 30,
                    countsRetentionDays = 90,
                    markersRetentionDays = 2,
                    maxBytes = twoDays,
                    clock = { day },
                ).enforce()

            // A collector that filled the node's disk is an outage it caused itself: the oldest
            // day goes rather than the newest write being refused.
            //
            // Counted, and not "fewer than before". `evictedDays > 0 && liveDays.size < 4` is what
            // this test used to assert, and that passes just as happily when the sweep has taken
            // the database down to a single day — which is what it did, every hour, once the file
            // crossed the cap.
            assertEquals(2, state.evictedDays)
            assertEquals(listOf("20260803", "20260804"), state.liveDays)
            assertTrue(state.usedBytes <= state.maxBytes, "still over the cap after evicting")
        }

    @Test
    fun `eviction never removes the last day`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1, count = 100)

            val state =
                Retention(
                    db,
                    Partitions(),
                    walBytes = { 0 },
                    retentionDays = 30,
                    countsRetentionDays = 90,
                    markersRetentionDays = 2,
                    maxBytes = 1,
                    clock = { day },
                ).enforce()

            // Dropping everything would leave a collector that collects nothing; the cap is a
            // budget, not a reason to become useless.
            assertEquals(1, state.liveDays.size)
        }

    @Test
    fun `counters outlive bodies`() =
        runTest {
            val db = freshDb()
            IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })(
                BatchHeader("orders-api", "pod-a", "1.0", 1),
                listOf(
                    LogRecord(ts = day, seq = 1, level = Level.INFO, logger = "L", message = "order created"),
                    TemplateCount(windowStart = day, template = "order created", level = Level.INFO, count = 40_000),
                ),
            )
            val now = day + 40 * 86_400_000L

            Retention(
                db,
                Partitions(),
                walBytes = { 0 },
                retentionDays = 30,
                countsRetentionDays = 90,
                markersRetentionDays = 2,
                maxBytes = Long.MAX_VALUE,
                clock = { now },
            ).enforce()

            // Bodies are gone, the frequency is not: counters are tiny and are wanted precisely
            // on the long horizon (research D13).
            assertEquals(40_000, db.scalar("SELECT count FROM template_count"))
        }

    @Test
    fun `state reports what an operator needs`() =
        runTest {
            val db = freshDb()
            writeDay(db, 0, 1)

            val state = Retention(db, Partitions(), { 0 }, 30, 90, 2, 4L * 1024 * 1024 * 1024, clock = { day }).state()

            assertEquals("20260801", state.oldestDay)
            assertTrue(state.databaseBytes > 0)
            assertEquals(4L * 1024 * 1024 * 1024, state.maxBytes)
        }

    @Test
    fun `a day dropped by retention is created again for a late record`() =
        runTest {
            val db = freshDb()
            // One cache, the way the container wires it: the write path and eviction share it.
            val partitions = Partitions()
            writeDay(db, 0, 1, partitions = partitions)
            writeDay(db, 40, 2, partitions = partitions)
            val now = day + 40 * 86_400_000L

            Retention(db, partitions, { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { now }).enforce()

            // A record for the dropped day arrives late — a retry across midnight, a backlog after
            // an outage, a clock that runs behind. It has to recreate the partition, not be
            // written into a table that no longer exists.
            writeDay(db, 0, 3, partitions = partitions)

            assertEquals(5, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `eviction forgets the day it dropped`() =
        runTest {
            val db = freshDb()
            val partitions = Partitions()
            writeDay(db, 0, 1, partitions = partitions)
            val now = day + 40 * 86_400_000L

            Retention(db, partitions, { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { now }).enforce()

            // The cache is the thing that decides whether the `CREATE TABLE` runs again, so a day
            // left in it is a day whose every later write goes nowhere.
            assertTrue("20260801" !in partitions.knownDays(), "the dropped day is still cached as created")
        }

    @Test
    fun `the cap counts what is used rather than the file it sits in`() =
        runTest {
            val db = freshDb()
            repeat(3) { writeDay(db, it, (it + 1).toLong(), count = 200) }
            val before = Retention(db, Partitions(), { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { day }).state()

            val state =
                Retention(db, Partitions(), { 0 }, 30, 90, 2, before.usedBytes * 2 / 3, clock = { day }).enforce()

            // `DROP TABLE` gives pages back to SQLite, not to the filesystem: the file keeps its
            // high-water mark and the next writes reuse the free list. So the two numbers have to
            // part company here — and the one the cap reads is the one the drop moves.
            assertTrue(state.usedBytes < state.databaseBytes, "the freed pages are counted as used")
            assertEquals(before.databaseBytes, state.databaseBytes)
            assertTrue(state.usedBytes <= state.maxBytes)
        }

    @Test
    fun `markers older than the horizon are swept and newer ones are kept`() =
        runTest {
            val db = freshDb()
            val old = day
            val fresh = day + 3 * 86_400_000L
            // Two batches an agent could still retry, and two it could not: the marker is not about
            // the age of the records, it is about the age of the question "have I stored this".
            writeBatchAt(db, ts = old, seq = 1)
            writeBatchAt(db, ts = old, seq = 2)
            writeBatchAt(db, ts = fresh, seq = 3)
            writeBatchAt(db, ts = fresh, seq = 4)

            val state =
                Retention(db, Partitions(), { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { fresh })
                    .enforce()

            assertEquals(2, db.scalar("SELECT count(*) FROM ingest_batch"))
            assertEquals(2, state.markersDropped)
        }

    @Test
    fun `a retry inside the horizon is still refused after a sweep`() =
        runTest {
            val db = freshDb()
            val partitions = Partitions()
            writeBatchAt(db, ts = day, seq = 1, partitions = partitions)

            // The sweep runs with the batch well inside the horizon, so its marker has to survive:
            // the whole point of the row is that the agent's repeat costs nothing.
            Retention(db, partitions, { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { day })
                .enforce()

            val again = writeBatchAt(db, ts = day, seq = 1, partitions = partitions)

            assertTrue(again.duplicate, "the marker was swept inside its own horizon")
            assertEquals(0, again.accepted)
            assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `the sweep walks in chunks rather than one statement`() =
        runTest {
            val db = freshDb()
            // More rows than a single chunk, so the loop has to come round again. 10 000 is the
            // chunk, and a test that stopped below it would pass without ever exercising the loop.
            //
            // One batch goes through ingest to create the instance; the rest of the markers are
            // written by one statement. Ten thousand ingest transactions took 44 s on linuxX64 and
            // ran past `runTest`'s minute on a CI runner, and the sweep does not care how a marker
            // got there.
            writeBatchAt(db, ts = day, seq = 1, records = 0)
            db
                .execute(
                    """
                    WITH RECURSIVE n(seq) AS (SELECT 2 UNION ALL SELECT seq + 1 FROM n WHERE seq < 10500)
                    INSERT INTO ingest_batch (instance_id, run, seq, received_at)
                    SELECT (SELECT instance_id FROM ingest_batch), '', seq, $day FROM n;
                    """.trimIndent(),
                ).getOrThrow()
            assertEquals(10_500, db.scalar("SELECT count(*) FROM ingest_batch"))
            val now = day + 3 * 86_400_000L

            val state =
                Retention(db, Partitions(), { 0 }, 30, 90, 2, Long.MAX_VALUE, clock = { now })
                    .enforce()

            assertEquals(10_500, state.markersDropped)
            assertEquals(0, db.scalar("SELECT count(*) FROM ingest_batch"))
        }
}
