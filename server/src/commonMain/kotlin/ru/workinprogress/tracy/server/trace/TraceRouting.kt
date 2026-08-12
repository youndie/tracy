package ru.workinprogress.tracy.server.trace

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import ru.workinprogress.tracy.wire.TracyJson

private val TRACE_ID = Regex("[0-9a-f]{32}")

public fun Route.traceRoutes(
    repository: TraceRepository,
    spans: SpanSearchRepository? = null,
) {
    get("/api/traces/{traceId}") {
        val traceId = call.parameters["traceId"].orEmpty().lowercase()
        if (!TRACE_ID.matches(traceId)) {
            call.respondText(
                """{"error":"traceId must be 32 lowercase hex characters"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        // An unknown trace is 200 with an empty tree, never 404: absence of data is an answer.
        // It does not, however, distinguish "there was no such trace" from "it was sampled away",
        // and that limit belongs in the docs rather than in a status code.
        val view = repository.load(traceId)
        call.respondText(
            TracyJson.encodeToString(view),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    if (spans == null) return

    get("/api/spans") {
        val since = call.request.queryParameters["since"]?.toLongOrNull()
        val until = call.request.queryParameters["until"]?.toLongOrNull()
        if (since == null || until == null || since > until) {
            call.respondText(
                """{"error":"since must be before until"}""",
                ContentType.Application.Json,
                HttpStatusCode.BadRequest,
            )
            return@get
        }

        val result =
            spans.search(
                service = call.request.queryParameters["service"],
                name = call.request.queryParameters["name"],
                minDurationMs = call.request.queryParameters["minDurationMs"]?.toIntOrNull(),
                onlyErrors = call.request.queryParameters["error"] == "true",
                since = since,
                until = until,
                limit =
                    call.request.queryParameters["limit"]
                        ?.toIntOrNull()
                        ?.coerceIn(1, 500) ?: 100,
            )
        call.respondText(TracyJson.encodeToString(result), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
