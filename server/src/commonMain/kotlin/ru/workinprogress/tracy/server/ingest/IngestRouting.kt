package ru.workinprogress.tracy.server.ingest

import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.Route
import io.ktor.server.routing.post
import org.koin.ktor.ext.inject
import ru.workinprogress.tracy.server.ServerConfig
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.EntityKeyBudget
import ru.workinprogress.tracy.wire.INGEST_PATH
import ru.workinprogress.tracy.wire.IngestHeaders
import ru.workinprogress.tracy.wire.IngestResponse
import ru.workinprogress.tracy.wire.NdJson
import ru.workinprogress.tracy.wire.TracyJson

public fun Route.ingestRoutes() {
    val config by inject<ServerConfig>()
    val acceptBatch by inject<IngestBatchUseCase>()

    // The breaker's decision travels back to the agent on every accepted response, so the route
    // needs the budget itself rather than a callback threaded in from Application (research D15).
    val budget by inject<EntityKeyBudget>()

    post(INGEST_PATH) {
        val key = call.request.header(IngestHeaders.KEY)
        // Constant-time comparison is pointless for a shared installation key sent on every
        // batch; what matters is that a missing or wrong key never reaches the database.
        if (key.isNullOrBlank() || key != config.ingestKey) {
            call.respondJson(HttpStatusCode.Unauthorized, """{"error":"unauthorized"}""")
            return@post
        }

        val service = call.request.header(IngestHeaders.SERVICE)
        val instance = call.request.header(IngestHeaders.INSTANCE)
        val seq = call.request.header(IngestHeaders.SEQ)?.toLongOrNull()
        if (service.isNullOrBlank() || instance.isNullOrBlank() || seq == null) {
            call.respondJson(
                HttpStatusCode.BadRequest,
                """{"error":"X-Tracy-Service, X-Tracy-Instance and X-Tracy-Seq are required"}""",
            )
            return@post
        }

        val body = call.receiveText()
        if (body.encodeToByteArray().size > config.maxBatchBytes) {
            call.respondJson(HttpStatusCode.PayloadTooLarge, """{"error":"batch too large"}""")
            return@post
        }

        // A line that fails to parse is skipped and counted, never fatal: logs are not a
        // transaction, and losing a whole batch over one bad line would be the worse failure.
        val decoded = NdJson.decodeBatch(body)

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
            runCatching { acceptBatch(header, decoded.lines) }
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
