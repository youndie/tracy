package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.ConnectionPool
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.smyrgeorge.sqlx4k.sqlite.sqlite
import io.github.youndie.tracy.server.openDatabase
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path.Companion.toPath
import okio.SYSTEM
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `synchronous` is connection state, and a pragma sent through a pool reaches one connection.
 *
 * The number this asserts is `1` — NORMAL. SQLite's default is `2`, FULL, which is an fsync per
 * commit; before this test existed, nine connections out of ten had it (M-138). Reading it back
 * from **every** connection is the whole point, so the test holds them all at once the same way the
 * production path does: a pool serving these one after another would hand out the same connection
 * and pass while nothing was fixed.
 */
class SynchronousPragmaTest {
    private companion object {
        const val NORMAL = 1L
        const val POOL = 4
    }

    @Test
    fun `every connection in the pool reports NORMAL`() =
        runTest {
            val db = openDatabase("/tmp/tracy-sync-${Random.nextLong()}.db", maxConnections = POOL)

            assertEquals(List(POOL) { NORMAL }, db.synchronousOnEveryConnection(POOL))
        }

    @Test
    fun `a pool nobody pinned keeps the pragma on one connection`() =
        runTest {
            // The control, and the reason the test above means anything: the same pragma, sent the
            // way a driver's documentation suggests, on a pool this repository has not touched. If
            // a future sqlx4k propagates connection state by itself, this fails — and that is the
            // signal that `pinSynchronousOnEveryConnection` can go.
            val path = "/tmp/tracy-sync-control-${Random.nextLong()}.db"
            FileSystem.SYSTEM.write(path.toPath()) { }
            val db =
                sqlite(
                    url = "sqlite://$path",
                    options =
                        ConnectionPool.Options
                            .builder()
                            .maxConnections(POOL)
                            .build(),
                )
            db.execute("PRAGMA synchronous = NORMAL;").getOrThrow()

            val seen = db.synchronousOnEveryConnection(POOL)

            // At most the one connection that call landed on; the rest keep SQLite's default.
            assertEquals(1, seen.count { it == NORMAL }, "expected one connection to differ: $seen")
        }

    /** What every connection of the pool answers, with all of them held at once. */
    private suspend fun ISQLite.synchronousOnEveryConnection(pool: Int): List<Long> =
        onEveryConnection(pool) { connection ->
            connection
                .fetchAll("PRAGMA synchronous;")
                .getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong()
        }
}
