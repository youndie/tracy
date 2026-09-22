package io.github.youndie.tracy.server.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong

/**
 * Interning for the low-cardinality strings: services, instances, exception classes, entity keys
 * and message templates.
 *
 * Templates are the reason this exists at all. D8 makes a message a repeated constant, so storing
 * it per record would be pure duplication, and indexing it per record produced tens of thousands
 * of identical trigram entries for one phrase (research D5).
 *
 * Every lookup is memoised, because the same handful of ids is needed for every line of every
 * batch and a round trip per line would dominate the write path.
 *
 * **Nothing evicts any of this, and nothing sweeps the rows underneath it.** Retention drops day
 * partitions and trims `template_count` and `ingest_batch`; these five tables are outside it. The
 * bound is therefore the number of distinct names seen since the process started, not any age.
 * There used to be a `clear()` here whose comment promised eviction "when a partition is removed",
 * and it had no caller — the sentence described a sweep nobody had written.
 *
 * Measured on the stand at forty-one days, because a bound worth stating is worth a number:
 * `instance` 232 rows / 16 KB, `log_template` 2 548 rows / 811 KB, `service` 7, `entity_key` 4,
 * `exception_class` 1 — about 835 KB of tables and some 2 800 entries in the maps.
 *
 * Two of the five grow differently, and the difference is the whole of it:
 *
 * - **templates are bounded by the shapes a codebase can write.** 2 548 for seven services is
 *   research D8 holding: messages really are repeated constants, and the set converges;
 * - **instances are bounded by nothing.** The recommended `instanceId` is the pod name
 *   (docs/services/tracy-agent.md), which is unique per generation, so every deploy and every
 *   restart adds a row and an entry that never leave — measured at 5.7 a day across those seven
 *   services. Ten years of that is tens of thousands of rows, which is why this is written down
 *   rather than swept: at that size a sweep would cost more than it saves, and it would have to
 *   decide what to do about ids that live partitions still point at.
 *
 * What the accumulation does damage to is not memory but the summary built over these rows — see
 * `serviceSummaries`, which counts every generation ever seen as an instance.
 */
public class Dictionaries {
    private val services = mutableMapOf<String, Long>()
    private val instances = mutableMapOf<Pair<Long, String>, Long>()
    private val exceptionClasses = mutableMapOf<String, Long>()
    private val entityKeys = mutableMapOf<String, Long>()
    private val templates = mutableMapOf<String, Long>()

    public suspend fun serviceId(
        executor: QueryExecutor,
        name: String,
        now: Long,
    ): Long {
        services[name]?.let { id ->
            executor.executeOrThrow(
                Statement
                    .create("UPDATE service SET last_seen = :now WHERE id = :id")
                    .apply {
                        bind("now", now)
                        bind("id", id)
                    },
            )
            return id
        }
        executor.executeOrThrow(
            Statement
                .create(
                    """INSERT INTO service (name, first_seen, last_seen) VALUES (:name, :now, :now)
                   ON CONFLICT(name) DO UPDATE SET last_seen = :now""",
                ).apply {
                    bind("name", name)
                    bind("now", now)
                },
        )
        val id = selectId(executor, "SELECT id FROM service WHERE name = :name", "name", name)
        services[name] = id
        return id
    }

    public suspend fun instanceId(
        executor: QueryExecutor,
        serviceId: Long,
        name: String,
        now: Long,
        clockSkewMs: Long,
        recordAgeMs: Long,
    ): Long {
        val cached = instances[serviceId to name]
        if (cached == null) {
            executor.executeOrThrow(
                Statement
                    .create(
                        """INSERT INTO instance (service_id, name, last_seen, clock_skew_ms, record_age_ms)
                       VALUES (:service, :name, :now, :skew, :age)
                       ON CONFLICT(service_id, name) DO UPDATE SET
                           last_seen = :now, clock_skew_ms = :skew, record_age_ms = :age""",
                    ).apply {
                        bind("service", serviceId)
                        bind("name", name)
                        bind("now", now)
                        bind("skew", clockSkewMs)
                        bind("age", recordAgeMs)
                    },
            )
            val id =
                selectId(
                    executor,
                    "SELECT id FROM instance WHERE service_id = :service AND name = :name",
                    "name",
                    name,
                ) { bind("service", serviceId) }
            instances[serviceId to name] = id
            return id
        }
        executor.executeOrThrow(
            Statement
                .create(
                    "UPDATE instance SET last_seen = :now, clock_skew_ms = :skew, record_age_ms = :age WHERE id = :id",
                ).apply {
                    bind("now", now)
                    bind("skew", clockSkewMs)
                    bind("age", recordAgeMs)
                    bind("id", cached)
                },
        )
        return cached
    }

    public suspend fun exceptionClassId(
        executor: QueryExecutor,
        name: String,
    ): Long = intern(executor, exceptionClasses, "exception_class", name)

    public suspend fun entityKeyId(
        executor: QueryExecutor,
        name: String,
    ): Long = intern(executor, entityKeys, "entity_key", name)

    /**
     * Interns a template and — only when it is genuinely new — adds it to the FTS index.
     *
     * This is why full-text search left the write path: the index is touched when a template
     * appears for the first time, not on every record.
     */
    public suspend fun templateId(
        executor: QueryExecutor,
        text: String,
    ): Long {
        templates[text]?.let { return it }

        val existing =
            executor
                .fetchAll(
                    Statement.create("SELECT id FROM log_template WHERE text = :text").apply { bind("text", text) },
                ).getOrThrow()
                .rows
                .getOrNull(0)
                ?.get(0)
                ?.asLong()

        if (existing != null) {
            templates[text] = existing
            return existing
        }

        executor.executeOrThrow(
            Statement.create("INSERT INTO log_template (text) VALUES (:text)").apply { bind("text", text) },
        )
        val id = selectId(executor, "SELECT id FROM log_template WHERE text = :text", "text", text)
        executor.executeOrThrow(
            Statement
                .create("INSERT INTO template_fts (rowid, text) VALUES (:id, :text)")
                .apply {
                    bind("id", id)
                    bind("text", text)
                },
        )
        templates[text] = id
        return id
    }

    private suspend fun intern(
        executor: QueryExecutor,
        cache: MutableMap<String, Long>,
        table: String,
        name: String,
    ): Long {
        cache[name]?.let { return it }
        executor.executeOrThrow(
            Statement
                .create("INSERT INTO $table (name) VALUES (:name) ON CONFLICT(name) DO NOTHING")
                .apply { bind("name", name) },
        )
        val id = selectId(executor, "SELECT id FROM $table WHERE name = :name", "name", name)
        cache[name] = id
        return id
    }

    private suspend fun selectId(
        executor: QueryExecutor,
        sql: String,
        paramName: String,
        paramValue: String,
        extra: Statement.() -> Unit = {},
    ): Long =
        executor
            .fetchAll(
                Statement.create(sql).apply {
                    bind(paramName, paramValue)
                    extra()
                },
            ).getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()
}
