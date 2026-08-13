package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLongOrNull
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import kotlinx.serialization.Serializable
import org.koin.ktor.ext.inject
import ru.workinprogress.tracy.server.db.EntityKeyBudget
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.TracyJson

@Serializable
public data class ServiceSummary(
    public val name: String,
    public val instances: Int,
    public val lastSeen: Long,
    /** What the service made, before sampling: the "who is noisy" number (research D13). */
    public val producedBytes: Long,
    /** What survived to disk. The gap between the two is the point of showing both. */
    public val storedRecords: Long,
    public val maxClockSkewMs: Long,
    /** References per entity key — the number that shows a key filling the database. */
    public val entityRefs: Map<String, Long> = emptyMap(),
)

public fun Route.queryRoutes() {
    val query by inject<QueryRepository>()
    val entities by inject<EntityRepository>()
    val budget by inject<EntityKeyBudget>()

    get("/api/logs") {
        val since = call.longParam("since") ?: return@get call.badRequest("since is required")
        val until = call.longParam("until") ?: return@get call.badRequest("until is required")
        if (since > until) return@get call.badRequest("since must be before until")

        val level =
            call.request.queryParameters["level"]?.let { name ->
                runCatching { Level.valueOf(name.uppercase()) }.getOrNull()
                    ?: return@get call.badRequest("unknown level: $name")
            }

        // Checked here rather than left to the repository: there is no StatusPages in this server,
        // so an exception thrown deeper would reach the caller as a 500 for a plain input mistake.
        val text = call.request.queryParameters["q"]
        if (text != null && text.trim().length < 3) {
            return@get call.badRequest("q needs at least 3 characters: the index is trigram-based")
        }

        val result =
            query.searchLogs(
                service = call.request.queryParameters["service"],
                instance = call.request.queryParameters["instance"],
                level = level,
                since = since,
                until = until,
                templateId = call.request.queryParameters["templateId"]?.toLongOrNull(),
                query = text,
                exceptionClass = call.request.queryParameters["exceptionClass"],
                traceId = call.request.queryParameters["traceId"],
                entityKey = call.request.queryParameters["entityKey"],
                entityValue = call.request.queryParameters["entityValue"],
                limit = call.request.queryParameters["limit"]?.toIntOrNull() ?: 100,
            )
        call.json(TracyJson.encodeToString(result))
    }

    get("/api/templates") {
        val since = call.longParam("since") ?: return@get call.badRequest("since is required")
        val until = call.longParam("until") ?: return@get call.badRequest("until is required")

        val result =
            query.templateStats(
                service = call.request.queryParameters["service"],
                level = call.request.queryParameters["level"]?.let { runCatching { Level.valueOf(it.uppercase()) }.getOrNull() },
                release = call.request.queryParameters["release"],
                since = since,
                until = until,
                stepMillis = call.request.queryParameters["step"]?.toLongOrNull(),
                limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 200) ?: 50,
            )
        call.json(TracyJson.encodeToString(result))
    }

    get("/api/entities/{key}/top") {
        val key = call.parameters["key"] ?: return@get call.badRequest("key is required")
        val since = call.longParam("since") ?: return@get call.badRequest("since is required")
        val until = call.longParam("until") ?: return@get call.badRequest("until is required")

        try {
            val result = entities.top(key, since, until, call.request.queryParameters["limit"]?.toIntOrNull() ?: 20)
            call.json(TracyJson.encodeToString(result))
        } catch (unknown: UnknownEntityKey) {
            call.unknownKey(unknown)
        }
    }

    get("/api/entities/{key}/{value}") {
        val key = call.parameters["key"] ?: return@get call.badRequest("key is required")
        val value = call.parameters["value"] ?: return@get call.badRequest("value is required")
        val since = call.longParam("since") ?: return@get call.badRequest("since is required")
        val until = call.longParam("until") ?: return@get call.badRequest("until is required")

        try {
            val result =
                entities.timeline(key, value, since, until, call.request.queryParameters["limit"]?.toIntOrNull() ?: 200)
            call.json(TracyJson.encodeToString(result))
        } catch (unknown: UnknownEntityKey) {
            call.unknownKey(unknown)
        }
    }

    post("/api/entities/{key}/unsuppress") {
        val key = call.parameters["key"] ?: return@post call.badRequest("key is required")
        // Idempotent on purpose: a repeat is not an error, and an operator retrying a release
        // should not have to wonder whether the first attempt worked.
        if (budget.unsuppress(key)) {
            call.json("""{"key":"$key","suppressed":false}""")
        } else {
            call.respondText(
                """{"error":"unknown key"}""",
                ContentType.Application.Json,
                HttpStatusCode.NotFound,
            )
        }
    }

    get("/api/services") {
        call.json(TracyJson.encodeToString(query.listServices()))
    }
}

internal suspend fun serviceSummaries(db: ISQLite): List<ServiceSummary> =
    TransactionContext.withCurrent(db) {
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

        fetchAll(
            """SELECT v.id, v.name, v.last_seen, count(i.id), coalesce(max(i.clock_skew_ms), 0)
               FROM service v LEFT JOIN instance i ON i.service_id = v.id
               GROUP BY v.id ORDER BY v.name""",
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
                instances = row.get(3).asLong().toInt(),
                lastSeen = row.get(2).asLong(),
                // Two numbers, and the gap between them is the point: produced is what the service
                // made, stored is what tracy decided to keep (research D13).
                producedBytes = produced,
                storedRecords = stored,
                maxClockSkewMs = row.get(4).asLongOrNull() ?: 0,
                entityRefs = refs,
            )
        }
    }

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
private suspend fun ApplicationCall.unknownKey(unknown: UnknownEntityKey) {
    val indexed = unknown.indexed.joinToString(",") { "\"$it\"" }
    respondText(
        """{"error":"key is not indexed","indexed":[$indexed]}""",
        ContentType.Application.Json,
        HttpStatusCode.BadRequest,
    )
}
