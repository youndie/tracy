package ru.workinprogress.tracy.server.retention

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable
import ru.workinprogress.tracy.server.db.dayKey

@Serializable
public data class RetentionState(
    public val liveDays: List<String>,
    public val oldestDay: String? = null,
    public val databaseBytes: Long,
    public val maxBytes: Long,
    /** How many days were dropped to stay under the cap since the server started. */
    public val evictedDays: Int = 0,
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
            execute(
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
            val over =
                TransactionContext.withCurrent(db) {
                    val days = liveDays(this)
                    if (days.size <= 1) return@withCurrent false
                    if (databaseBytes(this) <= maxBytes) return@withCurrent false
                    dropDay(this, days.first())
                    evicted++
                    true
                }
            if (!over) return
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
        // References may outlive bodies, but not the other way round: dropping a day takes all
        // three tables so nothing is left pointing at a table that no longer exists.
        listOf("log_entry_$day", "span_$day", "entity_ref_$day").forEach {
            executor.execute("DROP TABLE IF EXISTS $it")
        }
    }

    private suspend fun databaseBytes(executor: TransactionContext): Long {
        val pageCount =
            executor
                .fetchAll("PRAGMA page_count;")
                .getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong()
        val pageSize =
            executor
                .fetchAll("PRAGMA page_size;")
                .getOrThrow()
                .rows
                .first()
                .get(0)
                .asLong()
        return pageCount * pageSize
    }
}
