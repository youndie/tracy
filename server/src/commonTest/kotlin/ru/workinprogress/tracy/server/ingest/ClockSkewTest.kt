package ru.workinprogress.tracy.server.ingest

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
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

/**
 * Telling a slow clock apart from a slow delivery (M-110).
 *
 * These were one subtraction, `received - oldestRecord`, which is their sum. On the stand that
 * read 60 966 ms of "clock skew" for two services — the sender's backoff ceiling to the
 * millisecond, and not a clock at all. A field that exists to keep a multi-pod trace honest about
 * ordering was reporting a retry as a clock difference.
 */
class ClockSkewTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-skew-${Random.nextLong()}.db")

    private suspend fun ISQLite.instance(column: String): Long? =
        fetchAll("SELECT $column FROM instance")
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private suspend fun accept(
        db: ISQLite,
        receivedAt: Long,
        sentAt: Long?,
        recordTs: Long,
    ) {
        IngestBatchUseCase(IngestRepository(db, clock = { receivedAt }), clock = { receivedAt })(
            BatchHeader("orders-api", "pod-a", null, seq = recordTs, sentAt = sentAt),
            listOf(LogRecord(ts = recordTs, seq = 1, level = Level.INFO, logger = "L", message = "m")),
        )
    }

    @Test
    fun `a retry inflates the age and leaves the clock alone`() =
        runTest {
            val db = freshDb()

            // The record was made at `day`, the send failed, and the batch went out sixty seconds
            // later from a clock that agrees with the server's.
            accept(db, receivedAt = day + 60_000, sentAt = day + 60_000, recordTs = day)

            assertEquals(0, db.instance("clock_skew_ms"))
            assertEquals(60_000, db.instance("record_age_ms"))
        }

    @Test
    fun `a clock that runs behind shows up as skew and not as age`() =
        runTest {
            val db = freshDb()

            // Sent immediately by an agent whose clock is ten seconds behind the server's.
            accept(db, receivedAt = day + 10_000, sentAt = day, recordTs = day)

            assertEquals(10_000, db.instance("clock_skew_ms"))
            assertEquals(0, db.instance("record_age_ms"))
        }

    @Test
    fun `both at once are told apart`() =
        runTest {
            val db = freshDb()

            // Clock ten seconds behind, and the batch also waited a minute before going out.
            accept(db, receivedAt = day + 70_000, sentAt = day + 60_000, recordTs = day)

            assertEquals(10_000, db.instance("clock_skew_ms"))
            assertEquals(60_000, db.instance("record_age_ms"))
        }

    @Test
    fun `without the header the clock difference is unknown rather than guessed`() =
        runTest {
            val db = freshDb()

            // An agent older than 0.2.1. The old code would have called this 70 seconds of clock
            // skew; there is no way to know how much of it was the clock, so it claims none.
            accept(db, receivedAt = day + 70_000, sentAt = null, recordTs = day)

            assertEquals(0, db.instance("clock_skew_ms"))
            // The age still has a floor worth showing — it just also carries the clock difference.
            assertEquals(70_000, db.instance("record_age_ms"))
        }
}
