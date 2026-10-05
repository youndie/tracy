package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement

/**
 * `execute` that fails loudly.
 *
 * sqlx4k answers with `Result<Long>` and never throws, so a statement whose result is discarded
 * fails in complete silence. On the write path that is not a lost error message, it is a lost
 * record: the transaction runs on to `markBatch`, commits, and the route answers `202` — which the
 * protocol defines as "stored", and on which the agent drops the batch. The records are gone and
 * marked as delivered, so even a re-send is refused as a duplicate.
 *
 * Every write goes through this instead of `execute`, and the name is the point: a call that has to
 * be checked looks different from one that does not, and a copy-pasted `execute(` is visible in a
 * diff. Reads keep `.getOrThrow()` at the call site, where the row is unpacked anyway.
 */
internal suspend fun QueryExecutor.executeOrThrow(statement: Statement): Long = execute(statement).getOrThrow()

internal suspend fun QueryExecutor.executeOrThrow(sql: String): Long = execute(sql).getOrThrow()
