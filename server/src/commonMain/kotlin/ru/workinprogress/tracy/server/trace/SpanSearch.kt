package ru.workinprogress.tracy.server.trace

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asIntOrNull
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.Serializable
import ru.workinprogress.tracy.wire.SpanKind

/**
 * The entry point for "it was slow" — the one case neither tracy nor metrik could answer before.
 *
 * `get_trace` needs a trace id in hand; metrik aggregates durations per route and cannot point at
 * a particular request. The spans are already stored, so this was a missing query rather than
 * missing data.
 */
@Serializable
public data class SpanHit(
    public val traceId: String,
    public val spanId: String,
    public val service: String,
    public val name: String,
    public val kind: SpanKind,
    public val ts: Long,
    public val durationMs: Int?,
    public val status: Int?,
    public val error: Boolean,
)

@Serializable
public data class SpanSearchResult(
    public val hits: List<SpanHit>,
    public val truncated: Boolean = false,
    /**
     * Spans follow the trace sampling decision, so this is a sample of slow requests and not all
     * of them. "How many were there" is a question for metrik.
     */
    public val sampled: Boolean = true,
)

public class SpanSearchRepository(
    private val db: ISQLite,
) {
    public suspend fun search(
        service: String? = null,
        name: String? = null,
        minDurationMs: Int? = null,
        onlyErrors: Boolean = false,
        since: Long,
        until: Long,
        limit: Int = 100,
    ): SpanSearchResult =
        TransactionContext.withCurrent(db) {
            val days =
                fetchAll(
                    "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'span_%' ORDER BY name DESC",
                ).getOrThrow().rows.map { it.get(0).asString().removePrefix("span_") }

            val hits = mutableListOf<SpanHit>()
            for (day in days) {
                if (hits.size > limit) break
                val sql =
                    buildString {
                        append("SELECT lower(hex(s.trace_id)), lower(hex(s.span_id)), v.name, s.kind, s.name, ")
                        append("s.ts, s.duration_ms, s.status, s.error ")
                        append("FROM span_$day s JOIN service v ON v.id = s.service_id ")
                        append("WHERE s.ts BETWEEN :since AND :until ")
                        if (service != null) append("AND v.name = :service ")
                        if (name != null) append("AND s.name = :spanName ")
                        if (minDurationMs != null) append("AND s.duration_ms >= :minDuration ")
                        if (onlyErrors) append("AND s.error = 1 ")
                        append("ORDER BY s.duration_ms DESC LIMIT ${limit + 1}")
                    }

                hits +=
                    fetchAll(
                        Statement.create(sql).apply {
                            bind("since", since)
                            bind("until", until)
                            if (service != null) bind("service", service)
                            if (name != null) bind("spanName", name)
                            if (minDurationMs != null) bind("minDuration", minDurationMs)
                        },
                    ).getOrThrow().rows.map { row ->
                        SpanHit(
                            traceId = row.get(0).asString(),
                            spanId = row.get(1).asString(),
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

            val sorted = hits.sortedByDescending { it.durationMs ?: 0 }
            SpanSearchResult(
                hits = sorted.take(limit),
                truncated = sorted.size > limit,
            )
        }
}
