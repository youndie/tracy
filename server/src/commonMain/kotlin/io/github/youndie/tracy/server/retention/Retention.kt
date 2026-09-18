package io.github.youndie.tracy.server.retention

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.tracy.server.db.Partitions
import io.github.youndie.tracy.server.db.dayKey
import io.github.youndie.tracy.server.db.executeOrThrow
import kotlinx.serialization.Serializable

/**
 * No defaults on the numbers, on purpose: `TracyJson` is built with `encodeDefaults = false`, so a
 * field that happens to equal its default is left out of the response entirely — and "no log at
 * all" would arrive as a missing key, indistinguishable from a version that does not report it.
 */
@Serializable
public data class RetentionState(
    public val liveDays: List<String>,
    public val oldestDay: String? = null,
    public val databaseBytes: Long,
    /**
     * What is actually occupied inside the file, `(page_count - freelist_count) * page_size`, and
     * the number the size cap is compared against.
     *
     * `DROP TABLE` frees pages into SQLite's free list; without `auto_vacuum` the file keeps them
     * and [databaseBytes] does not move. Comparing the cap against the file therefore made the
     * condition unsatisfiable, and eviction dropped day after day until one was left — every time
     * it ran. Freed pages are reused by the next writes, so bounding what is used is what stops the
     * file growing; the file itself keeps its high-water mark, which is the standing price of
     * `DROP TABLE` over `VACUUM` (research D6).
     */
    public val usedBytes: Long,
    /**
     * The write-ahead log, which [databaseBytes] does not include: it is `page_count * page_size`,
     * and pages still in the log belong to neither number. M-137 watched a 931 MB log sit next to a
     * 187 MB database and report nothing at all here.
     */
    public val walBytes: Long,
    public val maxBytes: Long,
    /** How many days were dropped to stay under the cap since the server started. */
    public val evictedDays: Int,
)

/**
 * Retention is `DROP TABLE` of whole days, which is the only reason the partitions are daily.
 *
 * A large `DELETE` in SQLite leaves fragmentation and needs `VACUUM`; dropping a table is
 * constant. Both policies — age and the size cap — reduce to the same operation, and the first
 * version of the docs had monthly slices that could serve neither (research D6).
 */
public class Retention(
    private val db: ISQLite,
    /**
     * The same instance the write path uses, and it has to be the same one: eviction drops a day's
     * tables, and a cache that still believes they exist skips the `CREATE TABLE` for every late
     * record that lands in that day (see [Partitions.drop]).
     */
    private val partitions: Partitions,
    /** The size of the write-ahead log, which SQLite reports through neither pragma used here. */
    private val walBytes: () -> Long,
    private val retentionDays: Int,
    private val countsRetentionDays: Int,
    private val maxBytes: Long,
    private val clock: () -> Long,
) {
    private var evicted = 0

    public suspend fun enforce(): RetentionState {
        dropOlderThan(retentionDays)
        dropCountsOlderThan(countsRetentionDays)
        evictUntilUnderCap()
        return state()
    }

    public suspend fun state(): RetentionState =
        TransactionContext.withCurrent(db) {
            val days = liveDays(this)
            RetentionState(
                liveDays = days,
                oldestDay = days.minOrNull(),
                databaseBytes = databaseBytes(this),
                usedBytes = usedBytes(this),
                walBytes = walBytes(),
                maxBytes = maxBytes,
                evictedDays = evicted,
            )
        }

    private suspend fun dropOlderThan(days: Int) {
        val cutoff = dayKey(clock() - days * 86_400_000L)
        TransactionContext.withCurrent(db) {
            liveDays(this).filter { it < cutoff }.forEach { dropDay(this, it) }
        }
    }

    private suspend fun dropCountsOlderThan(days: Int) {
        val cutoff = clock() - days * 86_400_000L
        TransactionContext.withCurrent(db) {
            executeOrThrow(
                Statement
                    .create("DELETE FROM template_count WHERE minute < :cutoff")
                    .apply { bind("cutoff", cutoff) },
            )
        }
    }

    /**
     * The cap is hard. A log collector that filled the node's disk is an outage it caused itself,
     * so the oldest day goes rather than the newest write being refused.
     */
    private suspend fun evictUntilUnderCap() {
        while (true) {
            val freedSomething =
                TransactionContext.withCurrent(db) {
                    val days = liveDays(this)
                    if (days.size <= 1) return@withCurrent false
                    val before = usedBytes(this)
                    if (before <= maxBytes) return@withCurrent false
                    dropDay(this, days.first())
                    evicted++
                    // A drop that frees nothing means the number being compared is not the one the
                    // drop moves, and dropping the next day cannot help either. This loop emptied
                    // the database exactly that way: it compared `page_count`, which a `DROP TABLE`
                    // never lowers, so the condition stayed true until a single day was left.
                    usedBytes(this) < before
                }
            if (!freedSomething) return
        }
    }

    private suspend fun liveDays(executor: TransactionContext): List<String> =
        executor
            .fetchAll(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'log_entry_%' ORDER BY name",
            ).getOrThrow()
            .rows
            .map { it.get(0).asString().removePrefix("log_entry_") }

    private suspend fun dropDay(
        executor: TransactionContext,
        day: String,
    ) {
        // References may outlive bodies, but not the other way round: dropping a day takes every
        // table the day owns, so nothing is left pointing at a table that no longer exists. Which
        // tables those are is [Partitions]' business, and so is forgetting the day afterwards.
        partitions.drop(executor, day)
    }

    private suspend fun usedBytes(executor: TransactionContext): Long =
        databaseBytes(executor) - executor.pragma("freelist_count") * executor.pragma("page_size")

    /** The file: free pages included, because they are in it whether or not anything uses them. */
    private suspend fun databaseBytes(executor: TransactionContext): Long =
        executor.pragma("page_count") * executor.pragma("page_size")

    private suspend fun TransactionContext.pragma(name: String): Long =
        fetchAll("PRAGMA $name;")
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()
}
