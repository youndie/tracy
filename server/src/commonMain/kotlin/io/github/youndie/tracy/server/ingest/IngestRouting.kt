package io.github.youndie.tracy.server.ingest

import io.github.youndie.tracy.server.ServerConfig
import io.github.youndie.tracy.server.db.BatchHeader
import io.github.youndie.tracy.server.db.EntityKeyBudget
import io.github.youndie.tracy.wire.INGEST_PATH
import io.github.youndie.tracy.wire.IngestHeaders
import io.github.youndie.tracy.wire.IngestResponse
import io.github.youndie.tracy.wire.NdJson
import io.github.youndie.tracy.wire.TracyJson
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.header
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject

public fun Route.ingestRoutes() {
    val config by inject<ServerConfig>()
    val acceptBatch by inject<IngestBatchUseCase>()

    // The breaker's decision travels back to the agent on every accepted response, so the route
    // needs the budget itself rather than a callback threaded in from Application (research D15).
    val budget by inject<EntityKeyBudget>()
    val clientLimiter by inject<ClientRateLimiter>()

    post(INGEST_PATH) {
        val key = call.request.header(IngestHeaders.KEY)
        // Constant-time comparison is pointless for a shared installation key sent on every
        // batch; what matters is that a missing or wrong key never reaches the database.
        val app = key?.let { config.clientKeys[it] }
        if (key.isNullOrBlank() || (key != config.ingestKey && app == null)) {
            call.respondJson(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""")
            return@post
        }

        val requestedService = call.request.header(IngestHeaders.SERVICE)
        val instance = call.request.header(IngestHeaders.INSTANCE)
        val seq = call.request.header(IngestHeaders.SEQ)?.toLongOrNull()
        if (requestedService.isNullOrBlank() || instance.isNullOrBlank() || seq == null) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                """{"error":"X-Tracy-Service, X-Tracy-Instance and X-Tracy-Seq are required"}""",
            )
            return@post
        }

        // A client key writes to its own app and nowhere else, whatever the header says; the
        // installation key may not write into `app:` at all (research-clients K3).
        if (app == null && requestedService.startsWith(APP_SERVICE_PREFIX)) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                """{"error":"the $APP_SERVICE_PREFIX prefix is reserved for client keys"}""",
            )
            return@post
        }
        val service = if (app != null) APP_SERVICE_PREFIX + app else requestedService

        if (app != null && !clientLimiter.tryAcquire(app, instance)) {
            call.response.header(HttpHeaders.RetryAfter, "60")
            call.respondJson(HttpStatusCode.TooManyRequests, """{"error":"rate limited"}""")
            return@post
        }

        val body = call.receiveText()
        val ceiling = if (app != null) minOf(config.clientMaxBatchBytes, config.maxBatchBytes) else config.maxBatchBytes
        if (body.encodeToByteArray().size > ceiling) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"batch too large"}""")
            return@post
        }

        // A line that fails to parse is skipped and counted, never fatal: logs are not a
        // transaction, and losing a whole batch over one bad line would be the worse failure.
        val decoded = NdJson.decodeBatch(body)
        val lines = if (app != null) clientBatch(decoded.lines).lines else decoded.lines

        val header =
            BatchHeader(
                service = service,
                instance = instance,
                release = call.request.header(IngestHeaders.RELEASE),
                seq = seq,
                producedBytes = call.request.header(IngestHeaders.PRODUCED)?.toLongOrNull() ?: 0,
                dropped = call.request.header(IngestHeaders.DROPPED)?.toLongOrNull() ?: 0,
                sentAt = call.request.header(IngestHeaders.SENT)?.toLongOrNull(),
                runId = call.request.header(IngestHeaders.RUN).orEmpty(),
            )

        val result =
            runCatching { acceptBatch(header, lines) }
                .getOrElse {
                    // 503 rather than 500: this is retriable, and the agent must keep the batch.
                    call.respondJson(HttpStatusCode.ServiceUnavailable, """{"error":"unavailable"}""")
                    return@post
                }

        // Sent only after the commit. The protocol promises 202 means stored, and an agent that
        // sees it lets go of records that exist nowhere else.
        val response =
            IngestResponse(
                accepted = result.accepted,
                malformed = decoded.malformed,
                suppressedKeys = budget.suppressedFor(service),
            )
        call.respondJson(HttpStatusCode.Accepted, TracyJson.encodeToString(response))
    }
}

private suspend fun ApplicationCall.respondJson(
    status: HttpStatusCode,
    body: String,
) {
    respondText(body, io.ktor.http.ContentType.Application.Json, status)
}
