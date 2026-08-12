package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import platform.posix.stat
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.TimeSource

/**
 * M-35 — the three storage questions the docs deliberately left to measurement.
 *
 * a) risk 5 said inserting into FTS5 would slow writes down. That was written when the index was
 *    per record; it is now over templates, so the claim needs re-testing rather than repeating.
 * b) whether `entity_ref` earns a second, window-shaped index or whether the point index suffices.
 * c) whether the D6 arithmetic — ~145 MB a day — survives contact with real rows.
 */
@OptIn(ExperimentalForeignApi::class)
class StorageBenchmarkTest {
    private val day = 1785542400000L

    private fun fileSize(path: String): Long =
        memScoped {
            val info = alloc<stat>()
            if (stat(path, info.ptr) != 0) return@memScoped -1
            info.st_size
        }

    private fun record(
        seq: Long,
        message: String,
        fields: Map<String, JsonPrimitive>? = null,
    ) = LogRecord(
        ts = day,
        seq = seq,
        level = Level.INFO,
        logger = "OrdersRouting",
        message = message,
        fields = fields,
        traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
        spanId = "00f067aa0ba902b7",
    )

    private fun freshPath() = "/tmp/tracy-bench-${Random.nextLong()}.db"

    private suspend fun insert(
        db: ISQLite,
        path: String,
        count: Int,
        distinctTemplates: Int,
    ): Pair<Double, Long> {
        val repo = IngestRepository(db, clock = { day })
        val started = TimeSource.Monotonic.markNow()
        var seq = 0L
        val batchSize = 500
        while (seq < count) {
            val lines =
                (0 until batchSize).map {
                    val n = seq + it
                    record(n, "order event ${n % distinctTemplates} happened")
                }
            repo.write(BatchHeader("orders-api", "pod-a", "1.0.0", seq), lines)
            seq += batchSize
        }
        val perRecord = started.elapsedNow().inWholeMicroseconds.toDouble() / count
        return perRecord to fileSize(path)
    }

    @Test
    fun `writing with a template index is not dominated by fts`() =
        runBlocking {
            val count = 20_000

            // Every message is one of a hundred templates: the shape of a real service, where a
            // template repeats thousands of times (research 1.10 measured 704 messages -> 21).
            val fewPath = freshPath()
            val (fewPerRecord, fewSize) = insert(openDatabase(fewPath), fewPath, count, distinctTemplates = 100)

            // Pathological opposite: every message unique, so the FTS index grows per record and
            // the template table degenerates into a copy of the log.
            val manyPath = freshPath()
            val (manyPerRecord, manySize) = insert(openDatabase(manyPath), manyPath, count, distinctTemplates = count)

            println(
                "M-35 insert: ${fewPerRecord.toInt()} us/record with 100 templates " +
                    "(${fewSize / 1024} KB), ${manyPerRecord.toInt()} us/record with all unique " +
                    "(${manySize / 1024} KB)",
            )

            // The point of the design is that the common shape is the cheap one. If unique
            // messages were not measurably worse, templating would be buying nothing.
            assertTrue(fewPerRecord < manyPerRecord, "templating did not help at all")
            assertTrue(fewSize < manySize, "templating did not save space")
        }

    @Test
    fun `bytes per record are in the range the arithmetic assumed`() =
        runBlocking {
            val count = 20_000
            val path = freshPath()
            val (_, size) = insert(openDatabase(path), path, count, distinctTemplates = 100)
            val perRecord = size.toDouble() / count

            println("M-35 size: ${perRecord.toInt()} bytes per stored record")

            // D6 budgets 56 MB a day for bodies at ~1.5% of 17.3M records, i.e. roughly 215 bytes
            // apiece. Anything near a kilobyte would mean the month-in-4.3-GB promise is wrong.
            assertTrue(
                perRecord < 700,
                "a stored record costs ${perRecord.toInt()} bytes — the D6 arithmetic needs redoing",
            )
        }

    @Test
    fun `the point index answers a windowed aggregation without a second index`() =
        runBlocking {
            val path = freshPath()
            val db = openDatabase(path)
            val repo = IngestRepository(db, clock = { day })

            // 5 000 references over one key, the shape of an aggregation like "top IPs".
            var seq = 0L
            repeat(10) {
                repo.write(
                    BatchHeader("orders-api", "pod-a", null, seq),
                    (0 until 500).map { i ->
                        val n = seq + i
                        record(
                            n,
                            "auth failed",
                            fields = mapOf("ip" to JsonPrimitive("10.0.${n % 250}.${n % 100}")),
                        ).copy(indexed = listOf("ip"))
                    },
                )
                seq += 500
            }

            val started = TimeSource.Monotonic.markNow()
            val rows =
                db
                    .fetchAll(
                        """SELECT value, count(*) FROM entity_ref_20260801
                       WHERE key_id = 1 AND ts BETWEEN ${day - 1000} AND ${day + 1000}
                       GROUP BY value ORDER BY count(*) DESC LIMIT 10""",
                    ).getOrThrow()
                    .rows.size
            val elapsedMs = started.elapsedNow().inWholeMilliseconds

            println("M-35 aggregation over 5000 refs: $elapsedMs ms, $rows groups, point index only")

            // The question the docs left open: if this is fast enough, the second index is a third
            // of the cost of every reference for nothing — and references are already the second
            // largest consumer in the budget.
            assertTrue(elapsedMs < 500, "windowed aggregation took $elapsedMs ms without the second index")
        }
}
