package ru.workinprogress.tracy.server.trace

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import org.koin.ktor.ext.inject
import ru.workinprogress.tracy.server.query.SpansResource
import ru.workinprogress.tracy.server.query.TraceResource
import ru.workinprogress.tracy.wire.TracyJson

private val TRACE_ID = Regex("[0-9a-f]{32}")

public fun Route.traceRoutes() {
    val traces by inject<TraceRepository>()
    val spans by inject<SpanSearchRepository>()

    get<TraceResource> { params ->
        val traceId = params.traceId.lowercase()
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
        val view = traces.load(traceId)
        call.respondText(
            TracyJson.encodeToString(view),
            ContentType.Application.Json,
            HttpStatusCode.OK,
        )
    }

    get<SpansResource> { params ->
        val since = params.since
        val until = params.until
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
                service = params.service,
                name = params.name,
                minDurationMs = params.minDurationMs,
                onlyErrors = params.onlyErrors,
                since = since,
                until = until,
                limit = params.limit.coerceIn(1, 500),
            )
        call.respondText(TracyJson.encodeToString(result), ContentType.Application.Json, HttpStatusCode.OK)
    }
}
