package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable

@Serializable
public data class EntityTouch(
    public val ts: Long,
    public val service: String,
    public val instance: String,
    public val traceId: String? = null,
    /**
     * Null when the body was sampled away. Not a degenerate case — it is the normal one for a
     * successful request, and the entire reason references exist (research D12). A reader must be
     * able to tell "touched here, body not kept" from "did not happen".
     */
    public val entryId: Long? = null,
)

@Serializable
public data class EntityTimeline(
    public val key: String,
    public val value: String,
    public val touches: List<EntityTouch>,
    public val truncated: Boolean = false,
    /** Set when indexing of this key was switched off: everything after it is unknown, not absent. */
    public val suppressedSince: Long? = null,
)

@Serializable
public data class EntityValueCount(
    public val value: String,
    public val count: Long,
)

@Serializable
public data class EntityTopResult(
    public val key: String,
    public val values: List<EntityValueCount>,
    public val suppressedSince: Long? = null,
)

/** Raised when a key was never marked `indexed = true` anywhere. */
public class UnknownEntityKey(
    public val key: String,
    public val indexed: List<String>,
) : Exception("key is not indexed: $key")

/**
 * The other axis of the product: support brings an `orderId`, not a `traceId`, and the lifetime of
 * an entity is split across traces by design — created in one, processed by a worker an hour later
 * in another (research D12).
 */
public class EntityRepository(
    private val db: ISQLite,
) {
    public suspend fun timeline(
        key: String,
        value: String,
        since: Long,
        until: Long,
        limit: Int = 200,
    ): EntityTimeline =
        TransactionContext.withCurrent(db) {
            requireIndexed(this, key)

            val touches = mutableListOf<EntityTouch>()
            var seen = 0
            for (day in refPartitions(this)) {
                val rows =
                    fetchAll(
                        Statement
                            .create(
                                """SELECT r.ts, v.name, i.name, lower(hex(r.trace_id)), r.entry_id
                               FROM entity_ref_$day r
                               JOIN entity_key k ON k.id = r.key_id
                               JOIN service v ON v.id = r.service_id
                               JOIN instance i ON i.id = r.instance_id
                               WHERE k.name = :key AND r.value = :value
                                 AND r.ts BETWEEN :since AND :until
                               ORDER BY r.ts LIMIT ${limit + 1}""",
                            ).apply {
                                bind("key", key)
                                bind("value", value)
                                bind("since", since)
                                bind("until", until)
                            },
                    ).getOrThrow().rows

                seen += rows.size
                for (row in rows) {
                    if (touches.size >= limit) continue
                    touches +=
                        EntityTouch(
                            ts = row.get(0).asLong(),
                            service = row.get(1).asString(),
                            instance = row.get(2).asString(),
                            traceId = row.get(3).asStringOrNull()?.takeIf { it.isNotEmpty() },
                            entryId = row.get(4).asLongOrNull(),
                        )
                }
            }

            EntityTimeline(
                key = key,
                value = value,
                touches = touches.sortedBy { it.ts },
                truncated = seen > touches.size,
                suppressedSince = suppressedSince(this, key),
            )
        }

    public suspend fun top(
        key: String,
        since: Long,
        until: Long,
        limit: Int = 20,
    ): EntityTopResult =
        TransactionContext.withCurrent(db) {
            requireIndexed(this, key)

            val counts = mutableMapOf<String, Long>()
            for (day in refPartitions(this)) {
                fetchAll(
                    Statement
                        .create(
                            """SELECT r.value, count(*) FROM entity_ref_$day r
                           JOIN entity_key k ON k.id = r.key_id
                           WHERE k.name = :key AND r.ts BETWEEN :since AND :until
                           GROUP BY r.value""",
                        ).apply {
                            bind("key", key)
                            bind("since", since)
                            bind("until", until)
                        },
                ).getOrThrow().rows.forEach { row ->
                    val value = row.get(0).asString()
                    counts[value] = (counts[value] ?: 0) + row.get(1).asLong()
                }
            }

            EntityTopResult(
                key = key,
                values =
                    counts.entries
                        .sortedByDescending { it.value }
                        .take(limit)
                        .map { EntityValueCount(it.key, it.value) },
                suppressedSince = suppressedSince(this, key),
            )
        }

    /**
     * An unknown key is an error and not an empty result. Empty reads as "that never happened",
     * which is precisely the wrong conclusion when the truth is "nobody ever indexed this".
     */
    private suspend fun requireIndexed(
        executor: TransactionContext,
        key: String,
    ) {
        val known =
            executor
                .fetchAll(
                    Statement.create("SELECT 1 FROM entity_key WHERE name = :key").apply { bind("key", key) },
                ).getOrThrow()
                .rows
                .isNotEmpty()
        if (known) return

        val all =
            executor
                .fetchAll("SELECT name FROM entity_key ORDER BY name")
                .getOrThrow()
                .rows
                .map { it.get(0).asString() }
        throw UnknownEntityKey(key, all)
    }

    private suspend fun suppressedSince(
        executor: TransactionContext,
        key: String,
    ): Long? =
        executor
            .fetchAll(
                Statement
                    .create(
                        """SELECT min(s.since) FROM entity_key_suppressed s
                   JOIN entity_key k ON k.id = s.key_id WHERE k.name = :key""",
                    ).apply { bind("key", key) },
            ).getOrThrow()
            .rows
            .firstOrNull()
            ?.get(0)
            ?.asLongOrNull()

    private suspend fun refPartitions(executor: TransactionContext): List<String> =
        executor
            .fetchAll(
                "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'entity_ref_%' ORDER BY name",
            ).getOrThrow()
            .rows
            .map { it.get(0).asString().removePrefix("entity_ref_") }
}
