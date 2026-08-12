package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite

/**
 * The breaker of research D15.
 *
 * References are exempt from sampling, so a key whose value is unique per request grows the
 * database at the request rate — measured at ~1.3 GB a day at 100 rps against ~145 MB for
 * everything else. Limiting the *number of keys* does not help: eight harmless keys are fine and
 * one bad key is fatal, so what is bounded here is the **rate of references**.
 *
 * Three properties, each of which rules out an easier design:
 *
 * - **the decision lives on the server.** The agent has no durable storage and there are many
 *   instances; twenty pods each deciding separately is not one decision;
 * - **the latch does not reset on its own.** A key that is over budget stays over budget, and a
 *   minute-by-minute retry would index the first N references of every minute and drop the rest —
 *   producing partial data that looks complete, which is the thing the breaker exists to prevent;
 * - **a restart does not clear it.** In a cluster restarts happen by themselves, and a suppressed
 *   key that re-arms on every deploy is the same as no breaker at all.
 *
 * The only automatic release is expiry after a long silence, and that turns nothing back on while
 * the source is still sending — it just removes a stale row once `indexed = true` is gone from the
 * code.
 */
public class EntityKeyBudget(
    private val db: ISQLite,
    private val refsPerMinute: Int,
    private val suppressedTtlMillis: Long,
    private val clock: () -> Long,
) {
    private data class Window(
        var minute: Long,
        var count: Int,
    )

    private val windows = mutableMapOf<Pair<Long, Long>, Window>()
    private val suppressed = mutableMapOf<Long, MutableSet<String>>()
    private var loaded = false

    /** Called inside the ingest transaction, once per reference written. */
    public suspend fun observe(
        executor: QueryExecutor,
        serviceId: Long,
        keyId: Long,
        keyName: String,
        now: Long,
    ) {
        val minute = now / 60_000
        val window = windows.getOrPut(serviceId to keyId) { Window(minute, 0) }
        if (window.minute != minute) {
            window.minute = minute
            window.count = 0
        }
        window.count++

        if (window.count <= refsPerMinute) return
        if (isSuppressed(serviceId, keyName)) return

        executor.execute(
            Statement
                .create(
                    """INSERT INTO entity_key_suppressed (key_id, service_id, since, observed_per_minute, last_seen)
                   VALUES (:key, :service, :now, :rate, :now)
                   ON CONFLICT(key_id, service_id) DO UPDATE SET
                     observed_per_minute = :rate, last_seen = :now""",
                ).apply {
                    bind("key", keyId)
                    bind("service", serviceId)
                    bind("now", now)
                    bind("rate", window.count)
                },
        )
        suppressed.getOrPut(serviceId) { mutableSetOf() } += keyName
    }

    public fun isSuppressed(
        serviceId: Long,
        keyName: String,
    ): Boolean = suppressed[serviceId]?.contains(keyName) == true

    /** Rides on every accepted response, not only on change: a restarted agent learns from its first reply. */
    public suspend fun suppressedFor(service: String): List<String> {
        ensureLoaded()
        return TransactionContext.withCurrent(db) {
            fetchAll(
                Statement
                    .create(
                        """SELECT k.name FROM entity_key_suppressed s
                       JOIN entity_key k ON k.id = s.key_id
                       JOIN service v ON v.id = s.service_id
                       WHERE v.name = :service ORDER BY k.name""",
                    ).apply { bind("service", service) },
            ).getOrThrow().rows.map { it.get(0).asString() }
        }
    }

    /** Explicit release. Not a timer, not a restart — a person deciding (research D15). */
    public suspend fun unsuppress(key: String): Boolean =
        TransactionContext.withCurrent(db) {
            val rows =
                fetchAll(
                    Statement
                        .create("SELECT id FROM entity_key WHERE name = :name")
                        .apply { bind("name", key) },
                ).getOrThrow().rows
            if (rows.isEmpty()) return@withCurrent false

            execute(
                Statement
                    .create(
                        """DELETE FROM entity_key_suppressed
                       WHERE key_id IN (SELECT id FROM entity_key WHERE name = :name)""",
                    ).apply { bind("name", key) },
            )
            suppressed.values.forEach { it.remove(key) }
            true
        }

    /**
     * Removes rows for keys that have gone completely quiet. Safe because it turns nothing back on
     * while traffic continues — the row only expires once nothing has been sent under that key.
     */
    public suspend fun expireStale() {
        val cutoff = clock() - suppressedTtlMillis
        TransactionContext.withCurrent(db) {
            execute(
                Statement
                    .create("DELETE FROM entity_key_suppressed WHERE last_seen < :cutoff")
                    .apply { bind("cutoff", cutoff) },
            )
        }
        loaded = false
        suppressed.clear()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        TransactionContext.withCurrent(db) {
            val rows =
                fetchAll(
                    """SELECT s.service_id, k.name FROM entity_key_suppressed s
                       JOIN entity_key k ON k.id = s.key_id""",
                ).getOrThrow().rows
            suppressed.clear()
            for (row in rows) {
                val serviceId = row.get(0).asLong()
                suppressed.getOrPut(serviceId) { mutableSetOf() } += row.get(1).asString()
            }
        }
        loaded = true
    }
}
