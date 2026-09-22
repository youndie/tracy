package io.github.youndie.tracy.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.github.youndie.tracy.server.db.EntityKeyBudget
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.TracyJson
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject

@Serializable
public data class ServiceSummary(
    public val name: String,
    /**
     * Instances that reported inside [windowMs] — the ones writing now, not the ones that ever did.
     *
     * A row in `instance` is a pod *generation*: the recommended `instanceId` is the pod name, which
     * is unique per generation, and nothing deletes those rows. Counting them all answered "how many
     * deploys has this service had" under the name "instances": on the stand it read 67 for a
     * service running one pod.
     */
    public val instances: Int,
    /** Every generation ever seen. The number the old `instances` was, under a name that says so. */
    public val instancesEverSeen: Int,
    /** The window [instances], [maxClockSkewMs] and [maxRecordAgeMs] were taken over. */
    public val windowMs: Long,
    public val lastSeen: Long,
    /** What the service made, before sampling: the "who is noisy" number (research D13). */
    public val producedBytes: Long,
    /** What survived to disk. The gap between the two is the point of showing both. */
    public val storedRecords: Long,
    /**
     * Difference between the agent's clock and the server's — see `X-Tracy-Sent` — across the
     * instances inside [windowMs].
     *
     * **Absent when no instance reported inside the window**, because a missing field says "nobody
     * to ask" and a zero says "the clocks agree". Taken over every generation ever, this field was
     * a maximum that could only rise: 61 014 ms on the stand, unchanged across four days of healthy
     * traffic, left behind by a pod deleted weeks earlier — under which a fresh twenty-second skew
     * would have been invisible.
     */
    public val maxClockSkewMs: Long? = null,
    /**
     * How long the oldest record of a batch waited before it went out: the flush interval plus
     * any retry. A large value here is a delivery problem, and before M-110 it was being reported
     * as a clock problem instead.
     */
    public val maxRecordAgeMs: Long? = null,
    /**
     * Batches the server skipped because it had already stored that key. A few are ordinary —
     * an agent retrying after a lost response. A number that climbs while `producedBytes` stands
     * still is the shape of M-111, and it is here so that shape is visible at a glance.
     *
     * **Cumulative, unlike the three windowed fields above**, and deliberately: it is a counter of
     * events, not a reading of a current state, and a counter that forgets cannot be compared with
     * its earlier self.
     */
    public val duplicateBatches: Long = 0,
    /** References per entity key — the number that shows a key filling the database. */
    public val entityRefs: Map<String, Long> = emptyMap(),
)

public fun Route.queryRoutes() {
    val query by inject<QueryRepository>()
    val entityLookup by inject<EntityTimelineUseCase>()
    val budget by inject<EntityKeyBudget>()

    get<LogsResource> { params ->
        val window = call.window(params.since, params.until) ?: return@get
        val level = call.level(params.level) ?: return@get

        // Checked here rather than left to the repository: there is no StatusPages in this server,
        // so an exception thrown deeper would reach the caller as a 500 for a plain input mistake.
        if (params.q != null && params.q.trim().length < 3) {
            return@get call.badRequest("q needs at least 3 characters: the index is trigram-based")
        }

        val result =
            query.searchLogs(
                service = params.service,
                instance = params.instance,
                level = level.value,
                since = window.first,
                until = window.second,
                templateId = params.templateId,
                query = params.q,
                exceptionClass = params.exceptionClass,
                traceId = params.traceId,
                entityKey = params.entityKey,
                entityValue = params.entityValue,
                limit = params.limit,
            )
        call.json(TracyJson.encodeToString(result))
    }

    get<TemplatesResource> { params ->
        val window = call.window(params.since, params.until) ?: return@get
        val level = call.level(params.level) ?: return@get

        val result =
            query.templateStats(
                service = params.service,
                level = level.value,
                release = params.release,
                since = window.first,
                until = window.second,
                stepMillis = params.step,
                limit = params.limit.coerceIn(1, 200),
            )
        call.json(TracyJson.encodeToString(result))
    }

    get<EntitiesResource.Top> { params ->
        val window = call.window(params.since, params.until) ?: return@get

        when (val result = entityLookup.top(params.parent.key, window.first, window.second, params.limit)) {
            is EntityLookup.Found -> call.json(TracyJson.encodeToString(result.value))
            is EntityLookup.KeyNotIndexed -> call.unknownKey(result)
        }
    }

    get<EntitiesResource.Value> { params ->
        val window = call.window(params.since, params.until) ?: return@get

        when (
            val result =
                entityLookup.timeline(
                    params.parent.key,
                    params.value,
                    window.first,
                    window.second,
                    params.limit,
                )
        ) {
            is EntityLookup.Found -> call.json(TracyJson.encodeToString(result.value))
            is EntityLookup.KeyNotIndexed -> call.unknownKey(result)
        }
    }

    post<EntitiesResource.Unsuppress> { params ->
        // Idempotent on purpose: a repeat is not an error, and an operator retrying a release
        // should not have to wonder whether the first attempt worked.
        if (budget.unsuppress(params.parent.key)) {
            call.json("""{"key":"${params.parent.key}","suppressed":false}""")
        } else {
            call.respondText(
                """{"error":"unknown key"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
        }
    }

    get<ServicesResource> {
        call.json(TracyJson.encodeToString(query.listServices()))
    }
}

/**
 * The window, validated once instead of in every handler.
 *
 * `since` and `until` are nullable in the resource rather than required, because a missing
 * parameter has to answer `400` with a sentence — and a required constructor parameter answers
 * with whatever Ktor makes of a deserialization failure.
 */
private suspend fun ApplicationCall.window(
    since: Long?,
    until: Long?,
): Pair<Long, Long>? {
    if (since == null) {
        badRequest("since is required")
        return null
    }
    if (until == null) {
        badRequest("until is required")
        return null
    }
    if (since > until) {
        badRequest("since must be before until")
        return null
    }
    return since to until
}

/** Null means the answer was already sent; a wrapper holding null means "no level filter". */
private suspend fun ApplicationCall.level(name: String?): LevelFilter? {
    if (name == null) return LevelFilter(null)
    @Suppress(
        "ktlint:kapkan:cancellation-swallowed",
        "parsing an enum name is synchronous: there is no suspension point to be cancelled at",
    )
    val parsed = runCatching { Level.valueOf(name.uppercase()) }.getOrNull()
    if (parsed == null) {
        badRequest("unknown level: $name")
        return null
    }
    return LevelFilter(parsed)
}

private class LevelFilter(
    val value: Level?,
)

private fun ApplicationCall.longParam(name: String): Long? = request.queryParameters[name]?.toLongOrNull()

private suspend fun ApplicationCall.json(body: String) {
    respondText(body, ContentType.Application.Json, HttpStatusCode.OK)
}

private suspend fun ApplicationCall.badRequest(message: String) {
    respondText("""{"error":"$message"}""", ContentType.Application.Json, HttpStatusCode.BadRequest)
}

/**
 * An unknown key is 400 with the list of real ones, never an empty 200: empty reads as "that never
 * happened" when the truth is "nobody ever indexed this".
 */
private suspend fun ApplicationCall.unknownKey(unknown: EntityLookup.KeyNotIndexed) {
    val indexed = unknown.indexed.joinToString(",") { "\"$it\"" }
    respondText(
        """{"error":"key is not indexed","indexed":[$indexed]}""",
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
    )
}

/**
 * How far back an instance counts as reporting.
 *
 * A day, because the question this endpoint answers is "who is writing now", and the coarsest
 * normal rhythm an agent has is the minute window of its template counters — anything quieter than
 * a day is a service that has stopped, which `lastSeen` says on its own and without a window.
 */
internal const val INSTANCE_WINDOW_MILLIS: Long = 24 * 60 * 60 * 1000L

/**
 * Kept as a free function rather than folded into the repository because it reaches across every
 * partition table by name — see [QueryRepository.listServices], which is what callers use.
 */
internal suspend fun serviceSummaries(
    db: ISQLite,
    now: Long,
    windowMs: Long = INSTANCE_WINDOW_MILLIS,
): List<ServiceSummary> =
    TransactionContext.withCurrent(db) {
        val since = now - windowMs
        val partitions =
            fetchAll("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'log_entry_%'")
                .getOrThrow()
                .rows
                .map { it.get(0).asString() }

        val refPartitions =
            fetchAll("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'entity_ref_%'")
                .getOrThrow()
                .rows
                .map { it.get(0).asString() }

        // `max(CASE ...)` rather than a `WHERE`: the service still has to appear when none of its
        // instances is inside the window, and then the maxima come back NULL — which is the answer,
        // and a different answer from zero.
        fetchAll(
            Statement
                .create(
                    """SELECT v.id, v.name, v.last_seen,
                              count(i.id),
                              coalesce(sum(CASE WHEN i.last_seen >= :since THEN 1 ELSE 0 END), 0),
                              max(CASE WHEN i.last_seen >= :since THEN i.clock_skew_ms END),
                              max(CASE WHEN i.last_seen >= :since THEN i.record_age_ms END),
                              coalesce(sum(i.duplicate_batches), 0)
                         FROM service v LEFT JOIN instance i ON i.service_id = v.id
                        GROUP BY v.id ORDER BY v.name""",
                ).apply { bind("since", since) },
        ).getOrThrow().rows.map { row ->
            val serviceId = row.get(0).asLong()
            var stored = 0L
            for (table in partitions) {
                stored +=
                    fetchAll(
                        Statement
                            .create("SELECT count(*) FROM $table WHERE service_id = :id")
                            .apply { bind("id", serviceId) },
                    ).getOrThrow().rows.first().get(0).asLong()
            }
            val produced =
                fetchAll(
                    Statement
                        .create("SELECT coalesce(sum(bytes), 0) FROM service_produced WHERE service_id = :id")
                        .apply { bind("id", serviceId) },
                ).getOrThrow().rows.first().get(0).asLong()

            val refs = mutableMapOf<String, Long>()
            for (table in refPartitions) {
                fetchAll(
                    Statement
                        .create(
                            """SELECT k.name, count(*) FROM $table r
                               JOIN entity_key k ON k.id = r.key_id
                               WHERE r.service_id = :id GROUP BY k.name""",
                        ).apply { bind("id", serviceId) },
                ).getOrThrow().rows.forEach { refRow ->
                    val key = refRow.get(0).asString()
                    refs[key] = (refs[key] ?: 0) + refRow.get(1).asLong()
                }
            }

            ServiceSummary(
                name = row.get(1).asString(),
                instances = row.get(4).asLong().toInt(),
                instancesEverSeen = row.get(3).asLong().toInt(),
                windowMs = windowMs,
                lastSeen = row.get(2).asLong(),
                // Two numbers, and the gap between them is the point: produced is what the service
                // made, stored is what tracy decided to keep (research D13).
                producedBytes = produced,
                storedRecords = stored,
                maxClockSkewMs = row.get(5).asLongOrNull(),
                maxRecordAgeMs = row.get(6).asLongOrNull(),
                duplicateBatches = row.get(7).asLongOrNull() ?: 0,
                entityRefs = refs,
            )
        }
    }
