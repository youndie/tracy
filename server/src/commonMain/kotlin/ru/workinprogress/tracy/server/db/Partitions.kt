package ru.workinprogress.tracy.server.db

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
        partitionDdl(day).forEach { executor.execute(it) }
        known += day
    }

    public fun forget(day: String) {
        known -= day
    }

    public fun knownDays(): Set<String> = known.toSet()
}
