package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable
import ru.workinprogress.tracy.server.db.dayKey
import ru.workinprogress.tracy.wire.Level

@Serializable
public data class LogHit(
    public val entryId: Long,
    public val ts: Long,
    public val service: String,
    public val instance: String,
    public val level: Level,
    public val logger: String,
    public val templateId: Long,
    public val message: String,
    public val untrusted: Boolean = false,
    public val traceId: String? = null,
    public val fieldKeys: List<String> = emptyList(),
    public val redacted: List<String> = emptyList(),
)

@Serializable
public data class LogSearchResult(
    public val items: List<LogHit>,
    public val truncated: Boolean = false,
    public val remaining: Int = 0,
    /**
     * What the window actually contains. A reader who takes this for the full stream will draw
     * conclusions from absence, and absence here means "not kept" rather than "did not happen"
     * (research D7).
     */
    public val note: String =
        "Sampled: everything at WARN and above plus a share of INFO bodies. " +
            "For exact frequencies use /api/templates.",
)

@Serializable
public data class TemplatePoint(
    public val minute: Long,
    public val count: Long,
)

@Serializable
public data class TemplateStat(
    public val templateId: Long,
    public val text: String,
    public val level: Level,
    public val count: Long,
    public val release: String? = null,
    public val series: List<TemplatePoint> = emptyList(),
)

@Serializable
public data class TemplateStatsResult(
    public val items: List<TemplateStat>,
    /** Counted by the agent before sampling: the one exact number tracy has (research D13). */
    public val exact: Boolean = true,
)

/**
 * Reads across the daily partitions.
 *
 * There is no index on time alone and none is needed: the partition *is* the time filter, and rows
 * within a day land in insertion order, which is time order (research D5).
 */
public class QueryRepository(
    private val db: ISQLite,
    private val maxLimit: Int = 200,
) {
    public suspend fun searchLogs(
        service: String? = null,
        instance: String? = null,
        level: Level? = null,
        since: Long,
        until: Long,
        templateId: Long? = null,
        exceptionClass: String? = null,
        traceId: String? = null,
        entityKey: String? = null,
        entityValue: String? = null,
        limit: Int = 100,
    ): LogSearchResult =
        TransactionContext.withCurrent(db) {
            val capped = limit.coerceIn(1, maxLimit)
            val items = mutableListOf<LogHit>()
            var seen = 0

            for (day in daysBetween(this, since, until)) {
                val sql =
                    buildString {
                        append(
                            """SELECT e.id, e.ts, v.name, i.name, e.level, e.logger, e.template_id,
                                      e.untrusted, e.raw_message, t.text, e.fields, e.redacted,
                                      lower(hex(e.trace_id))
                               FROM log_entry_$day e
                               JOIN service v ON v.id = e.service_id
                               JOIN instance i ON i.id = e.instance_id
                               JOIN log_template t ON t.id = e.template_id """,
                        )
                        if (entityKey != null) {
                            append(
                                """JOIN entity_ref_$day r ON r.entry_id = e.id
                                   JOIN entity_key ek ON ek.id = r.key_id AND ek.name = :entityKey """,
                            )
                        }
                        if (exceptionClass != null) {
                            append("JOIN exception_class x ON x.id = e.exception_class_id AND x.name = :exClass ")
                        }
                        append("WHERE e.ts BETWEEN :since AND :until ")
                        if (service != null) append("AND v.name = :service ")
                        if (instance != null) append("AND i.name = :instance ")
                        if (level != null) append("AND e.level = :level ")
                        if (templateId != null) append("AND e.template_id = :templateId ")
                        if (traceId != null) append("AND e.trace_id = unhex(:traceId) ")
                        if (entityValue != null) append("AND r.value = :entityValue ")
                        append("ORDER BY e.ts, e.seq LIMIT ${capped + 1}")
                    }

                val rows =
                    fetchAll(
                        Statement.create(sql).apply {
                            bind("since", since)
                            bind("until", until)
                            service?.let { bind("service", it) }
                            instance?.let { bind("instance", it) }
                            level?.let { bind("level", it.name) }
                            templateId?.let { bind("templateId", it) }
                            traceId?.let { bind("traceId", it) }
                            entityKey?.let { bind("entityKey", it) }
                            entityValue?.let { bind("entityValue", it) }
                            exceptionClass?.let { bind("exClass", it) }
                        },
                    ).getOrThrow().rows

                seen += rows.size
                for (row in rows) {
                    if (items.size >= capped) continue
                    val untrusted = row.get(7).asIntOrNull() == 1
                    items +=
                        LogHit(
                            entryId = row.get(0).asLong(),
                            ts = row.get(1).asLong(),
                            service = row.get(2).asString(),
                            instance = row.get(3).asString(),
                            level = Level.valueOf(row.get(4).asString()),
                            logger = row.get(5).asString(),
                            templateId = row.get(6).asLong(),
                            message = if (untrusted) row.get(8).asStringOrNull().orEmpty() else row.get(9).asString(),
                            untrusted = untrusted,
                            traceId = row.get(12).asStringOrNull()?.takeIf { it.isNotEmpty() },
                            fieldKeys =
                                row
                                    .get(10)
                                    .asStringOrNull()
                                    ?.jsonKeys()
                                    .orEmpty(),
                            redacted =
                                row
                                    .get(11)
                                    .asStringOrNull()
                                    ?.split(',')
                                    ?.filter { it.isNotBlank() }
                                    .orEmpty(),
                        )
                }
            }

            LogSearchResult(
                items = items,
                truncated = seen > items.size,
                remaining = (seen - items.size).coerceAtLeast(0),
            )
        }

    /**
     * Frequencies come from the agent's counters, never from stored rows. Counting rows would
     * understate by `1/sampleRate` and still look like a fact (research D13).
     */
    public suspend fun templateStats(
        service: String? = null,
        level: Level? = null,
        release: String? = null,
        since: Long,
        until: Long,
        stepMillis: Long? = null,
        limit: Int = 50,
    ): TemplateStatsResult =
        TransactionContext.withCurrent(db) {
            val totals =
                fetchAll(
                    Statement
                        .create(
                            buildString {
                                append("SELECT c.template_id, t.text, c.level, sum(c.count) ")
                                append("FROM template_count c JOIN log_template t ON t.id = c.template_id ")
                                append("JOIN service v ON v.id = c.service_id ")
                                append("WHERE c.minute BETWEEN :since AND :until ")
                                if (service != null) append("AND v.name = :service ")
                                if (level != null) append("AND c.level = :level ")
                                if (release != null) append("AND c.release = :release ")
                                append("GROUP BY c.template_id, c.level ORDER BY sum(c.count) DESC LIMIT $limit")
                            },
                        ).apply {
                            bind("since", since)
                            bind("until", until)
                            service?.let { bind("service", it) }
                            level?.let { bind("level", it.name) }
                            release?.let { bind("release", it) }
                        },
                ).getOrThrow().rows

            val items =
                totals.map { row ->
                    val templateId = row.get(0).asLong()
                    val statLevel = Level.valueOf(row.get(2).asString())
                    TemplateStat(
                        templateId = templateId,
                        text = row.get(1).asString(),
                        level = statLevel,
                        count = row.get(3).asLong(),
                        release = release,
                        series =
                            if (stepMillis == null) {
                                emptyList()
                            } else {
                                series(this, templateId, statLevel, service, since, until, stepMillis)
                            },
                    )
                }

            TemplateStatsResult(items)
        }

    private suspend fun series(
        executor: TransactionContext,
        templateId: Long,
        level: Level,
        service: String?,
        since: Long,
        until: Long,
        stepMillis: Long,
    ): List<TemplatePoint> =
        executor
            .fetchAll(
                Statement
                    .create(
                        buildString {
                            append("SELECT (c.minute / :step) * :step AS bucket, sum(c.count) ")
                            append("FROM template_count c JOIN service v ON v.id = c.service_id ")
                            append("WHERE c.template_id = :template AND c.level = :level ")
                            append("AND c.minute BETWEEN :since AND :until ")
                            if (service != null) append("AND v.name = :service ")
                            append("GROUP BY bucket ORDER BY bucket")
                        },
                    ).apply {
                        bind("step", stepMillis)
                        bind("template", templateId)
                        bind("level", level.name)
                        bind("since", since)
                        bind("until", until)
                        service?.let { bind("service", it) }
                    },
            ).getOrThrow()
            .rows
            .map { TemplatePoint(it.get(0).asLong(), it.get(1).asLong()) }

    private suspend fun daysBetween(
        executor: TransactionContext,
        since: Long,
        until: Long,
    ): List<String> {
        val wanted = mutableSetOf<String>()
        var cursor = since
        while (cursor <= until) {
            wanted += dayKey(cursor)
            cursor += 86_400_000L
        }
        wanted += dayKey(until)

        val existing =
            executor
                .fetchAll(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'log_entry_%'",
                ).getOrThrow()
                .rows
                .map { it.get(0).asString().removePrefix("log_entry_") }
                .toSet()

        return wanted.filter { it in existing }.sorted()
    }
}

private fun String.jsonKeys(): List<String> =
    runCatching {
        ru.workinprogress.tracy.wire.TracyJson
            .parseToJsonElement(this)
            .let { (it as kotlinx.serialization.json.JsonObject).keys.toList() }
    }.getOrDefault(emptyList())
