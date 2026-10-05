package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor

/** `20260812` — the suffix of every daily table. */
public fun dayKey(epochMillis: Long): String {
    val days = epochMillis / 86_400_000L
    var year = 1970
    var remaining = days
    while (true) {
        val length = if (isLeap(year)) 366 else 365
        if (remaining < length) break
        remaining -= length
        year++
    }
    val monthLengths = intArrayOf(31, if (isLeap(year)) 29 else 28, 31, 30, 31, 30, 31, 31, 30, 31, 30, 31)
    var month = 0
    while (remaining >= monthLengths[month]) {
        remaining -= monthLengths[month]
        month++
    }
    val day = remaining + 1
    return year.toString() + pad2(month + 1) + pad2(day.toInt())
}

private fun isLeap(year: Int): Boolean = (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun pad2(value: Int): String = if (value < 10) "0$value" else value.toString()

/**
 * Creates a day's tables on first use and remembers that it did.
 *
 * The cache matters: `CREATE TABLE IF NOT EXISTS` is cheap but not free, and this would otherwise
 * run for every batch of every service all day long.
 */
public class Partitions {
    private val known = mutableSetOf<String>()

    public suspend fun ensure(
        executor: QueryExecutor,
        day: String,
    ) {
        if (day in known) return
        // Remembered only after the DDL is known to have worked. A day cached on a failed
        // `CREATE TABLE` is a day whose every write goes to a table that does not exist, for as
        // long as the process lives.
        partitionDdl(day).forEach { executor.executeOrThrow(it) }
        known += day
    }

    /**
     * Drops a day and forgets it in the same breath.
     *
     * Eviction used to live in `Retention` and this cache knew nothing about it, so a dropped day
     * stayed "created" for the rest of the process: [ensure] skipped the DDL, and every late
     * record for that day — a retry across midnight, a backlog after an outage, a skewed clock —
     * was inserted into a table that no longer existed.
     */
    public suspend fun drop(
        executor: QueryExecutor,
        day: String,
    ) {
        partitionTables(day).forEach { executor.executeOrThrow("DROP TABLE IF EXISTS $it") }
        known -= day
    }

    public fun knownDays(): Set<String> = known.toSet()
}
