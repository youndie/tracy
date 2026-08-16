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
import kotlin.test.assertTrue

/**
 * The redelivery check must not mistake a new process for a repeat of an old one (M-111).
 *
 * This is written from a live failure rather than from imagination. Three services out of four
 * took the service's public domain from `HOSTNAME`, so every generation of the pod introduced
 * itself under the same instance name. A fresh pod restarts `seq` at zero, every batch matched
 * one already stored, and the server answered `202` — so the agent considered the records
 * delivered and dropped them. Nothing on either side reported it: `lastSeen` kept moving from
 * heartbeats and the agent's own counters read `rejected=0 malformed=0`.
 */
class IdempotencyKeyTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-idem-${Random.nextLong()}.db")

    private suspend fun ISQLite.scalar(sql: String): Long? =
        fetchAll(sql)
            .getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private fun useCase(db: ISQLite) = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })

    private suspend fun send(
        db: ISQLite,
        run: String,
        seq: Long,
        message: String,
    ) = useCase(db)(
        // The same instance name every time: that is the whole point — it is what the chart gave
        // all of them.
        BatchHeader("orders-api", "orders.example.com", null, seq = seq, runId = run),
        listOf(LogRecord(ts = day + seq, seq = seq, level = Level.INFO, logger = "L", message = message)),
    )

    @Test
    fun `a new run is not a redelivery of the old one`() =
        runTest {
            val db = freshDb()

            send(db, run = "pod-generation-1", seq = 1, message = "before the deploy")
            // The pod is replaced. Same instance name, and `seq` starts over.
            val second = send(db, run = "pod-generation-2", seq = 1, message = "after the deploy")

            assertEquals(1, second.accepted)
            assertTrue(!second.duplicate)
            assertEquals(2, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `a genuine retry from the same run is still free`() =
        runTest {
            val db = freshDb()

            send(db, run = "one-run", seq = 1, message = "sent once")
            val again = send(db, run = "one-run", seq = 1, message = "sent once")

            // What the key was built for, and it still holds: the agent that never saw the 202
            // resends, and the resend costs nothing.
            assertEquals(0, again.accepted)
            assertTrue(again.duplicate)
            assertEquals(1, db.scalar("SELECT count(*) FROM log_entry_20260801"))
        }

    @Test
    fun `an agent without a run keeps the old behaviour`() =
        runTest {
            val db = freshDb()

            // Older than 0.2.2: no header, so the run is empty on both batches and the key is
            // effectively (instance, seq) again. Compatibility, not a recommendation.
            send(db, run = "", seq = 1, message = "first")
            val again = send(db, run = "", seq = 1, message = "second")

            assertTrue(again.duplicate)
        }

    @Test
    fun `a skipped batch is counted where someone will see it`() =
        runTest {
            val db = freshDb()

            send(db, run = "one-run", seq = 1, message = "sent once")
            send(db, run = "one-run", seq = 1, message = "sent once")
            send(db, run = "one-run", seq = 1, message = "sent once")

            // The failure was silent for hours because nothing counted it. Two skips here, and
            // the number rides out in /api/services next to producedBytes.
            assertEquals(2, db.scalar("SELECT duplicate_batches FROM instance"))
        }
}
