package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.Connection
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Migrations are a list of SQL plus `PRAGMA user_version` — the same mechanism katcher uses.
 * A framework would buy nothing here: the schema is owned by one binary and nobody else writes
 * to this file.
 *
 * Runs before the engine starts. A server that opened its port ahead of a ready schema would
 * answer the first requests with errors.
 */
public suspend fun ISQLite.migrateDb() {
    // WAL and NORMAL are the right trade for logs: at worst the last commit is lost on power
    // failure, and write throughput differs by a multiple.
    //
    // `journal_mode` is the only one of the three that a single call settles: it is written into the
    // database header, so every connection that opens the file inherits it (and sqlx4k sets it again
    // on each connect anyway). `foreign_keys` sqlx turns on for every connection itself. What is
    // left is `synchronous`, and a pragma sent through a pool reaches the one connection the pool
    // happened to hand out — see [pinSynchronousOnEveryConnection].
    execute("PRAGMA journal_mode = WAL;")
    execute("PRAGMA foreign_keys = ON;")

    transaction {
        val current =
            fetchAll("PRAGMA user_version;")
                .getOrNull()
                ?.rows
                ?.getOrNull(0)
                ?.get(0)
                ?.asLong()
                ?.toInt() ?: 0

        for (version in (current + 1)..allMigrations.size) {
            allMigrations[version - 1].forEach { execute(it) }
            execute("PRAGMA user_version = $version;")
        }
    }
}

/**
 * Puts `PRAGMA synchronous = NORMAL` on **every** connection the pool may open, not on the one that
 * answered first.
 *
 * `synchronous` is connection state, and sqlx4k offers no hook that runs on connect — `after_connect`
 * inside the driver sets `journal_mode` and nothing else, and the connection URL accepts only the
 * four parameters SQLite defines for URI filenames. So `db.execute("PRAGMA …")` lands wherever the
 * pool sends it, and the other connections keep SQLite's default of FULL: an fsync per commit on
 * nine tenths of the writes, while the comment above claims otherwise. Measured with a probe of six
 * concurrent transactions on a pool of six: `sync=1` on one of them, `sync=2` on five.
 *
 * Every connection is taken out of the pool at once — `acquire()` rather than a transaction, because
 * SQLite refuses the pragma inside one ("Safety level may not be changed inside a transaction") —
 * and each is released only after all of them have been handed out, so the pool has to open a
 * separate connection for each instead of serving them in turn with the same one.
 *
 * **This pins the pool as it is at start-up, and nothing more.** A connection closed later — by
 * `TRACY_DB_IDLE_TIMEOUT_SECONDS`, or by a pool that reaps — comes back as a fresh one with FULL,
 * and this function will not have been called again. That is the argument for leaving the idle
 * timeout unset by default, and the reason this is a workaround rather than a fix: the fix belongs
 * in sqlx4k, as a hook that runs on connect.
 */
public suspend fun ISQLite.pinSynchronousOnEveryConnection(maxConnections: Int) {
    require(maxConnections > 0) { "maxConnections must be greater than 0" }
    onEveryConnection(maxConnections) { it.execute("PRAGMA synchronous = NORMAL;").getOrThrow() }
}

/**
 * Runs [block] once on each of [count] connections, with all of them held at the same time.
 *
 * Holding them all is the whole mechanism: a pool asked for connections one after another answers
 * with the same one, and anything set on it would look like it had been set everywhere.
 */
internal suspend fun <T> ISQLite.onEveryConnection(
    count: Int,
    block: suspend (Connection) -> T,
): List<T> =
    coroutineScope {
        val allHeld = CompletableDeferred<Unit>()
        val counter = Mutex()
        var held = 0
        (1..count)
            .map {
                async {
                    val connection = acquire().getOrThrow()
                    try {
                        val n = counter.withLock { ++held }
                        if (n == count) allHeld.complete(Unit)
                        allHeld.await()
                        block(connection)
                    } finally {
                        // For a pooled connection this is the release, not a disconnect.
                        connection.close().getOrThrow()
                    }
                }
            }.awaitAll()
    }
