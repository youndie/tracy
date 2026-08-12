package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.INGEST_PATH
import ru.workinprogress.tracy.wire.IngestHeaders
import ru.workinprogress.tracy.wire.IngestResponse
import ru.workinprogress.tracy.wire.NdJson
import ru.workinprogress.tracy.wire.TracyJson
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** Outcome of one attempt, classified the way docs/api/protocol-ingest.md prescribes. */
public sealed interface SendResult {
    /** Written to the database. Only now may the agent let go of the batch. */
    public data class Accepted(
        val accepted: Int,
        val suppressedKeys: List<String>,
        /** Lines the server could not parse. Zero on an older server, which simply omits it. */
        val malformed: Int = 0,
    ) : SendResult

    /** `400`, `401`, `413` — repeating will not help; count it and move on. */
    public data class Rejected(
        val status: Int,
        val body: String?,
    ) : SendResult

    /** `503`, a timeout, a broken connection — keep the batch and try again later. */
    public data class Retriable(
        val reason: String,
    ) : SendResult
}

/**
 * Sends one batch, once.
 *
 * Two rules shape everything here. It **never throws**: a failing observability agent must not
 * surface in the code of the service it observes. And it treats anything other than `202` as
 * "not stored", because the protocol promises that `202` means written — letting go of a batch on
 * a weaker signal would discard records that exist nowhere.
 */
public class Sender(
    private val config: AgentConfig,
    private val client: HttpClient =
        tracyHttpClient {
            install(HttpTimeout) {
                requestTimeoutMillis = DEFAULT_TIMEOUT.inWholeMilliseconds
                connectTimeoutMillis = DEFAULT_TIMEOUT.inWholeMilliseconds
                socketTimeoutMillis = DEFAULT_TIMEOUT.inWholeMilliseconds
            }
        },
) {
    public suspend fun send(
        batch: List<BatchLine>,
        seq: Long,
        counters: BufferCounters = BufferCounters(0, 0),
    ): SendResult {
        if (batch.isEmpty()) return SendResult.Accepted(0, emptyList())

        val body = NdJson.encodeBatch(batch)

        return runCatching {
            val response =
                client.post(config.endpoint.trimEnd('/') + INGEST_PATH) {
                    contentType(ContentType("application", "x-ndjson"))
                    header(IngestHeaders.KEY, config.apiKey)
                    header(IngestHeaders.SERVICE, config.service)
                    header(IngestHeaders.INSTANCE, config.instanceId)
                    config.release?.let { header(IngestHeaders.RELEASE, it) }
                    header(IngestHeaders.SEQ, seq.toString())
                    if (counters.dropped > 0) header(IngestHeaders.DROPPED, counters.dropped.toString())
                    if (counters.producedBytes > 0) {
                        header(IngestHeaders.PRODUCED, counters.producedBytes.toString())
                    }
                    setBody(body)
                }

            when (val status = response.status.value) {
                202 -> {
                    val parsed =
                        runCatching { TracyJson.decodeFromString<IngestResponse>(response.bodyAsText()) }
                            .getOrDefault(IngestResponse(accepted = batch.size))
                    SendResult.Accepted(parsed.accepted, parsed.suppressedKeys, parsed.malformed)
                }

                // Repeating these cannot help: the key is wrong, the request is malformed, or the
                // batch is too large. Retrying would spin forever on a permanent condition.
                400, 401, 413 -> {
                    SendResult.Rejected(status, response.bodyAsText().take(200))
                }

                else -> {
                    SendResult.Retriable("HTTP $status")
                }
            }
        }.getOrElse { failure ->
            SendResult.Retriable(failure::class.simpleName ?: "network failure")
        }
    }

    public fun close() {
        runCatching { client.close() }
    }

    public companion object {
        public val DEFAULT_TIMEOUT: Duration = 10.seconds
    }
}

/**
 * Exponential backoff with a ceiling. Kept separate from [Sender] so the schedule is testable
 * without a socket, and so a retry storm cannot be introduced by editing request code.
 */
public class Backoff(
    private val base: Duration = 1.seconds,
    private val max: Duration = 60.seconds,
) {
    public fun delayFor(attempt: Int): Duration {
        require(attempt >= 0) { "attempt must not be negative" }
        var d = base
        repeat(minOf(attempt, MAX_DOUBLINGS)) { d *= 2 }
        return if (d > max) max else d
    }

    private companion object {
        const val MAX_DOUBLINGS = 16
    }
}
