package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

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
    execute("PRAGMA journal_mode = WAL;")
    execute("PRAGMA synchronous = NORMAL;")
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
