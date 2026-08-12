package ru.workinprogress.tracy.server.trace

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.json.jsonObject
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceLogLine
import ru.workinprogress.tracy.wire.TraceView
import ru.workinprogress.tracy.wire.TracyJson

/**
 * Reads a trace out of the daily partitions.
 *
 * A trace can straddle midnight, and there is no index that would tell us which day it lives in
 * without looking — so every live partition is asked. That is the cost of the slicing that makes
 * retention a `DROP TABLE`, and it is paid on a query that returns a few hundred rows.
 */
public class TraceRepository(
    private val db: ISQLite,
    private val maxLogs: Int = 500,
) {
    public suspend fun load(traceId: String): TraceView =
        TransactionContext.withCurrent(db) {
            val days = livePartitions(this)

            val spans = mutableListOf<StoredSpan>()
            val logsBySpan = mutableMapOf<String, MutableList<TraceLogLine>>()
            val loose = mutableListOf<TraceLogLine>()
            var seen = 0
            var truncated = false

            for (day in days) {
                spans += loadSpans(this, day, traceId)

                for (line in loadLogs(this, day, traceId)) {
                    seen++
                    if (seen > maxLogs) {
                        truncated = true
                        continue
                    }
                    val spanId = line.second
                    if (spanId == null) {
                        loose += line.first
                    } else {
                        logsBySpan.getOrPut(spanId) { mutableListOf() } += line.first
                    }
                }
            }

            TraceAssembler.assemble(
                traceId = traceId,
                spans = spans,
                logsBySpan = logsBySpan,
                looseLogs = loose,
                truncated = truncated,
                remaining = if (truncated) seen - maxLogs else 0,
            )
        }

    private suspend fun livePartitions(executor: TransactionContext): List<String> =
        executor
            .fetchAll(
                """SELECT name FROM sqlite_master
               WHERE type = 'table' AND name LIKE 'log_entry_%' ORDER BY name""",
            ).getOrThrow()
            .rows
            .map { it.get(0).asString().removePrefix("log_entry_") }

    private suspend fun loadSpans(
        executor: TransactionContext,
        day: String,
        traceId: String,
    ): List<StoredSpan> {
        val exists =
            executor
                .fetchAll(
                    Statement
                        .create("SELECT 1 FROM sqlite_master WHERE type='table' AND name = :name")
                        .apply { bind("name", "span_$day") },
                ).getOrThrow()
                .rows
                .isNotEmpty()
        if (!exists) return emptyList()

        return executor
            .fetchAll(
                Statement
                    .create(
                        """SELECT lower(hex(s.span_id)), lower(hex(s.parent_span_id)), v.name, s.kind, s.name,
                          s.ts, s.duration_ms, s.status, s.error
                   FROM span_$day s JOIN service v ON v.id = s.service_id
                   WHERE s.trace_id = unhex(:trace) ORDER BY s.ts""",
                    ).apply { bind("trace", traceId) },
            ).getOrThrow()
            .rows
            .map { row ->
                StoredSpan(
                    spanId = row.get(0).asString(),
                    // hex(NULL) is '' in SQLite, not NULL. Left as is, a root span would look like
                    // a child of a span called "" — and would be reported as an orphan.
                    parentSpanId = row.get(1).asStringOrNull()?.takeIf { it.isNotEmpty() },
                    service = row.get(2).asString(),
                    name = row.get(4).asString(),
                    kind = SpanKind.valueOf(row.get(3).asString().uppercase()),
                    ts = row.get(5).asLong(),
                    durationMs = row.get(6).asIntOrNull(),
                    status = row.get(7).asIntOrNull(),
                    error = row.get(8).asIntOrNull() == 1,
                )
            }
    }

    private suspend fun loadLogs(
        executor: TransactionContext,
        day: String,
        traceId: String,
    ): List<Pair<TraceLogLine, String?>> =
        executor
            .fetchAll(
                Statement
                    .create(
                        """SELECT e.id, e.ts, v.name, e.level, e.logger, e.untrusted, e.raw_message,
                          t.text, e.fields, e.redacted, lower(hex(e.span_id))
                   FROM log_entry_$day e
                   JOIN service v ON v.id = e.service_id
                   JOIN log_template t ON t.id = e.template_id
                   WHERE e.trace_id = unhex(:trace) ORDER BY e.ts, e.seq""",
                    ).apply { bind("trace", traceId) },
            ).getOrThrow()
            .rows
            .map { row ->
                val untrusted = row.get(5).asIntOrNull() == 1
                val line =
                    TraceLogLine(
                        entryId = row.get(0).asLong(),
                        ts = row.get(1).asLong(),
                        service = row.get(2).asString(),
                        level = Level.valueOf(row.get(3).asString()),
                        logger = row.get(4).asString(),
                        // An interpolated record keeps its own text; a structured one *is* its
                        // template, which is why the template is not duplicated per row.
                        message = if (untrusted) row.get(6).asStringOrNull().orEmpty() else row.get(7).asString(),
                        untrusted = untrusted,
                        fieldKeys =
                            row
                                .get(8)
                                .asStringOrNull()
                                ?.fieldKeys()
                                .orEmpty(),
                        redacted =
                            row
                                .get(9)
                                .asStringOrNull()
                                ?.split(',')
                                ?.filter { it.isNotBlank() }
                                .orEmpty(),
                    )
                // Same trap: an empty string here would file a record under a span that does not
                // exist instead of at trace level, which is where non-suspend records belong.
                line to row.get(10).asStringOrNull()?.takeIf { it.isNotEmpty() }
            }
}

/**
 * Only the keys. Values are data from outside the process, and the read side hands them over
 * separately — the same split the MCP contract makes into two phases (research D8).
 */
private fun String.fieldKeys(): List<String> =
    runCatching {
        TracyJson
            .parseToJsonElement(this)
            .jsonObject.keys
            .toList()
    }.getOrDefault(emptyList())
