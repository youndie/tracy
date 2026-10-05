package io.github.youndie.tracy.server

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.tracy.server.db.IngestRepository
import io.github.youndie.tracy.server.ingest.IngestBatchUseCase
import io.github.youndie.tracy.server.query.QueryRepository
import io.github.youndie.tracy.wire.Level
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Self-observation, checked by the data appearing rather than by the code running.
 *
 * A disabled agent and a working one look identical from the outside — that is the whole failure
 * mode this milestone is about, and the only proof that separates them is a record that can be
 * read back.
 */
class SelfObservationTest {
    private val day = 1785542400000L

    private fun freshDb(): ISQLite = openDatabase("/tmp/tracy-self-${Random.nextLong()}.db")

    private fun observation(db: ISQLite) =
        SelfObservation(
            acceptBatch = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day }),
            service = "tracy-server",
            instanceId = "pod-a",
            release = "0.1.0",
            clock = { day + 1 },
        )

    private fun observation(
        db: ISQLite,
        runId: String,
    ) = SelfObservation(
        acceptBatch = IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day }),
        service = "tracy-server",
        instanceId = "pod-a",
        release = "0.1.0",
        clock = { day + 1 },
        runId = runId,
    )

    @Test
    fun `tracy appears in its own list of services`() =
        runTest {
            val db = freshDb()

            observation(db).log(Level.INFO, "Boot", "tracy started")

            val services = QueryRepository(db, clock = { day }).listServices()
            assertEquals(listOf("tracy-server"), services.map { it.name })
        }

    @Test
    fun `the record is readable back through the ordinary query path`() =
        runTest {
            val db = freshDb()
            observation(db).log(Level.INFO, "Retention", "retention swept", mapOf("liveDays" to "3"))

            val found = QueryRepository(db, clock = { day }).searchLogs(since = 0, until = Long.MAX_VALUE)

            assertEquals(1, found.items.size)
            assertEquals("retention swept", found.items.first().message)
            assertEquals(listOf("liveDays"), found.items.first().fieldKeys)
        }

    @Test
    fun `sequence numbers advance so records keep their order`() =
        runTest {
            val db = freshDb()
            val self = observation(db)

            self.log(Level.INFO, "Boot", "first")
            self.log(Level.INFO, "Boot", "second")

            val found = QueryRepository(db, clock = { day }).searchLogs(since = 0, until = Long.MAX_VALUE)
            assertEquals(listOf("first", "second"), found.items.map { it.message })
        }

    @Test
    fun `a restarted process in the same pod keeps its own logs`() =
        runTest {
            val db = freshDb()

            // Two processes with one HOSTNAME: a container restarted inside the same pod. Each
            // starts its sequence from zero, so only the run tells their batches apart (#66).
            observation(db).log(Level.INFO, "Boot", "first start")
            observation(db).log(Level.INFO, "Boot", "second start")

            val found = QueryRepository(db, clock = { day }).searchLogs(since = 0, until = Long.MAX_VALUE)
            assertEquals(listOf("first start", "second start"), found.items.map { it.message }.sorted())
        }

    @Test
    fun `without a run the restarted process is dropped as a duplicate`() =
        runTest {
            val db = freshDb()

            // The control for the test above: the same two starts keyed the old way lose the
            // second one, so that test can tell the fix from its absence.
            observation(db, runId = "").log(Level.INFO, "Boot", "first start")
            observation(db, runId = "").log(Level.INFO, "Boot", "second start")

            val found = QueryRepository(db, clock = { day }).searchLogs(since = 0, until = Long.MAX_VALUE)
            assertEquals(listOf("first start"), found.items.map { it.message })
        }

    @Test
    fun `a credential in tracy's own message is redacted like anyone else's`() =
        runTest {
            val db = freshDb()

            // Being the server is not an exemption: whatever survives redaction lands in the
            // template table, which is handed to agents as trusted text (research 1.10).
            observation(db).log(Level.WARN, "Boot", "upstream https://user:hunter2@example.com failed")

            val message =
                QueryRepository(db, clock = { day })
                    .searchLogs(since = 0, until = Long.MAX_VALUE)
                    .items
                    .first()
                    .message
            assertTrue("hunter2" !in message, message)
        }

    @Test
    fun `its own events are countable and not only searchable`() =
        runTest {
            val db = freshDb()
            val self = observation(db)

            self.log(Level.INFO, "Retention", "retention swept")
            self.log(Level.INFO, "Retention", "retention swept")

            val stats = QueryRepository(db, clock = { day }).templateStats(since = 0, until = Long.MAX_VALUE)

            // Found by pointing a real MCP client at the deployed server: the records showed up
            // in search_logs and `top_templates` answered nothing, so "how often does retention
            // sweep" read as *never* for events that had just happened. Counters are what that
            // tool reads, and the agent fills them for every other service.
            assertEquals(1, stats.items.size, "expected one template, got ${stats.items}")
            assertEquals(2L, stats.items.first().count)
        }

    @Test
    fun `a write failure does not propagate`() =
        runTest {
            val db = freshDb()
            // A repository pointed at a database that was never migrated: every write throws.
            val broken =
                SelfObservation(
                    acceptBatch =
                        IngestBatchUseCase(
                            IngestRepository(openBrokenDatabase(), clock = { day }),
                            clock = { day },
                        ),
                    service = "tracy-server",
                    instanceId = "pod-a",
                    release = null,
                    clock = { day },
                )

            // No exception: a server that cannot write its own line still has to accept
            // everyone else's.
            broken.log(Level.INFO, "Boot", "started")

            assertEquals(0, QueryRepository(db, clock = { day }).listServices().size)
        }

    private fun openBrokenDatabase(): ISQLite =
        io.github.smyrgeorge.sqlx4k.sqlite
            .sqlite("/tmp/tracy-self-broken-${Random.nextLong()}.db")
}
