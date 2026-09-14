package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.youndie.tracy.server.openDatabase
import kotlinx.coroutines.test.runTest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The log has to come back down, and the number that says so has to be readable from outside.
 *
 * Both halves matter: M-137 watched a 931 MB write-ahead log next to a 187 MB database, and every
 * number tracy reported about its own size was about the database.
 */
class WalCheckpointTest {
    private fun freshPath(): String = "/tmp/tracy-wal-${Random.nextLong()}.db"

    @Test
    fun `a checkpoint truncates the log and leaves the rows in the database`() =
        runTest {
            val path = freshPath()
            val db = openDatabase(path, maxConnections = 2)
            val wal = WalCheckpoint(db, path)
            db.execute("CREATE TABLE t (id INTEGER PRIMARY KEY, blob TEXT);").getOrThrow()
            repeat(200) {
                db.execute("INSERT INTO t (blob) VALUES ('${"x".repeat(400)}');").getOrThrow()
            }
            assertTrue(wal.walBytes() > 0, "nothing reached the log at all")

            val state = wal.checkpoint()

            // Nothing is reading, so the checkpoint has nothing to wait for.
            assertTrue(!state.busy, "the checkpoint reported busy with no reader in sight")
            assertEquals(0, state.bytes, "the state reports a log that is no longer there")
            assertEquals(0, wal.walBytes(), "the log was not truncated")
            // The rows are the point: a truncated log that lost writes would pass every size check
            // above and be the worst possible outcome.
            assertEquals(
                200,
                db
                    .fetchAll("SELECT count(*) FROM t;")
                    .getOrThrow()
                    .rows
                    .first()
                    .get(0)
                    .asLong(),
            )
        }

    @Test
    fun `a database with no log at all reads as zero rather than failing`() =
        runTest {
            // Not a hypothetical: `/health/retention` asks for this number on a server that has
            // been asked nothing yet, and an exception here would answer a health route with a 500.
            assertEquals(
                0,
                WalCheckpoint(openDatabase(freshPath()), "/tmp/tracy-no-such-${Random.nextLong()}.db").walBytes(),
            )
        }
}
