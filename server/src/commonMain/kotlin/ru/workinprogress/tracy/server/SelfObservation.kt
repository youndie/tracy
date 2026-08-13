package ru.workinprogress.tracy.server

import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.ingest.IngestBatchUseCase
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Redactor
import ru.workinprogress.tracy.wire.TemplateCount

/**
 * tracy watching itself.
 *
 * Written straight into the repository rather than posted to its own `/ingest`. The loopback
 * version was tried and reverted: the client and the server are the same process, so the round
 * trip re-tests code the server's own tests already cover, and it costs a real dependency — the
 * agent's native HTTP client is Curl, which added 13 MB to the image and a link against
 * `libz.so.1` that `distroless/cc` does not carry. "Verify through the real path" earns its keep
 * when the paths differ; here they do not.
 *
 * **What is logged is deliberately narrow.** A server that logged every accepted batch would log
 * its own writes, and each of those would produce more records than it carried — an observability
 * tool is the one place where a feedback loop writes itself. Only events that are rare by
 * construction go here: a start, a retention sweep.
 */
public class SelfObservation(
    private val acceptBatch: IngestBatchUseCase,
    private val service: String,
    private val instanceId: String,
    private val release: String?,
    private val clock: () -> Long,
    private val redactor: Redactor = Redactor(),
) {
    private var seq: Long = 0

    /**
     * Redaction runs here too, for the same reason it runs in the agent: whatever survives it
     * ends up in the template table, which outlives record bodies and is handed to agents as
     * trusted text (research 1.10). Being the server is not an exemption.
     */
    public suspend fun log(
        level: Level,
        logger: String,
        message: String,
        fields: Map<String, String> = emptyMap(),
    ) {
        val redacted = redactor.redactMessage(message)
        val record =
            LogRecord(
                ts = clock(),
                seq = ++seq,
                level = level,
                logger = logger,
                message = redacted.text,
                fields =
                    fields
                        .takeIf { it.isNotEmpty() }
                        ?.mapValues { (_, value) -> kotlinx.serialization.json.JsonPrimitive(value) },
            )

        // The counter goes with the record, and that is not decoration. `top_templates` is the
        // tool that answers "how often", and it reads `template_count` — which the agent fills
        // for every other service. Writing the record alone made tracy's own events visible in
        // `search_logs` and invisible in `top_templates`, so "how often does retention sweep"
        // answered *never* for events that had just happened. Found by pointing a real MCP
        // client at the deployed server, not by a test.
        val counter =
            TemplateCount(
                windowStart = record.ts - record.ts % MINUTE_MILLIS,
                template = record.message,
                level = level,
                count = 1,
            )

        // Failure here must never propagate: a server that cannot write its own log line still
        // has to accept everyone else's.
        runCatching {
            acceptBatch(
                BatchHeader(
                    service = service,
                    instance = instanceId,
                    release = release,
                    seq = seq,
                ),
                listOf(record, counter),
            )
        }
    }
}

private const val MINUTE_MILLIS: Long = 60_000
