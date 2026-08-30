package ru.workinprogress.tracy.server.db

import io.github.smyrgeorge.sqlx4k.QueryExecutor
import io.github.smyrgeorge.sqlx4k.Statement
import io.github.smyrgeorge.sqlx4k.impl.coroutines.TransactionContext
import io.github.smyrgeorge.sqlx4k.impl.extensions.asLong
import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.template.Normalizer
import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.TemplateCount
import ru.workinprogress.tracy.wire.TracyJson

public data class BatchHeader(
    val service: String,
    val instance: String,
    val release: String?,
    val seq: Long,
    /** Bytes the service produced since the last batch — before sampling, before dropping. */
    val producedBytes: Long = 0,
    val dropped: Long = 0,
    /**
     * The agent's clock when it put this batch on the wire (`X-Tracy-Sent`). Null from an agent
     * older than 0.2.1, and then the clock difference is simply unknown rather than guessed —
     * see [ru.workinprogress.tracy.server.ingest.IngestBatchUseCase].
     */
    val sentAt: Long? = null,
    /**
     * One run of the agent (`X-Tracy-Run`). Empty from an agent older than 0.2.2, which keeps the
     * old `(instance, seq)` key — see [ru.workinprogress.tracy.server.ingest.IngestBatchUseCase].
     */
    val runId: String = "",
)

public data class WriteResult(
    val accepted: Int,
    /** True when this exact batch had already been stored: a redelivery, not new data. */
    val duplicate: Boolean,
)

/**
 * Writes a whole batch in **one transaction**.
 *
 * That is why records, spans, counters and entity references travel in a single stream: there is
 * never a moment where the span of a request exists but its records do not. It is also what makes
 * `202` mean what the protocol says it means — the response is sent after the commit, so an agent
 * that lets go of a batch is letting go of something that is stored.
 */
public class IngestRepository(
    private val db: ISQLite,
    private val dictionaries: Dictionaries = Dictionaries(),
    private val partitions: Partitions = Partitions(),
    private val budget: EntityKeyBudget? = null,
    private val clock: () -> Long,
) {
    /**
     * The transaction a batch is written inside.
     *
     * The envelope stays here and the sequence moved to [ru.workinprogress.tracy.server.ingest.IngestBatchUseCase]:
     * splitting them the other way — a use case calling repository methods that each open their
     * own transaction — would trade an invariant for a layer diagram. The batch marker has to land
     * with the records it marks, or a retry after a partial failure writes them twice.
     */
    public suspend fun <T> transaction(block: suspend TransactionContext.() -> T): T =
        TransactionContext.withCurrent(db, block)

    internal suspend fun serviceId(
        executor: QueryExecutor,
        name: String,
        now: Long,
    ): Long = dictionaries.serviceId(executor, name, now)

    internal suspend fun instanceId(
        executor: QueryExecutor,
        serviceId: Long,
        name: String,
        now: Long,
        skew: Long,
        recordAge: Long,
    ): Long = dictionaries.instanceId(executor, serviceId, name, now, skew, recordAge)

    /**
     * What the service produced, as opposed to what survived. Reporting only the latter would
     * answer "how much did tracy decide to keep" when the question is "who is noisy" (research D13).
     */
    internal suspend fun recordProduced(
        executor: QueryExecutor,
        serviceId: Long,
        now: Long,
        producedBytes: Long,
        dropped: Long,
    ) {
        executor.execute(
            Statement
                .create(
                    """INSERT INTO service_produced (service_id, minute, bytes, dropped)
                       VALUES (:service, :minute, :bytes, :dropped)
                       ON CONFLICT(service_id, minute)
                       DO UPDATE SET bytes = bytes + :bytes, dropped = dropped + :dropped""",
                ).apply {
                    bind("service", serviceId)
                    bind("minute", now / 60_000 * 60_000)
                    bind("bytes", producedBytes)
                    bind("dropped", dropped)
                },
        )
    }

    /** The idempotency marker. Written last, inside the same transaction as what it marks. */
    internal suspend fun markBatch(
        executor: QueryExecutor,
        instanceId: Long,
        run: String,
        seq: Long,
        now: Long,
    ) {
        executor.execute(
            Statement
                .create("INSERT INTO ingest_batch (instance_id, run, seq, received_at) VALUES (:i, :r, :s, :t)")
                .apply {
                    bind("i", instanceId)
                    bind("r", run)
                    bind("s", seq)
                    bind("t", now)
                },
        )
    }

    internal suspend fun alreadyStored(
        executor: QueryExecutor,
        instanceId: Long,
        run: String,
        seq: Long,
    ): Boolean =
        executor
            .fetchAll(
                Statement
                    .create("SELECT 1 FROM ingest_batch WHERE instance_id = :i AND run = :r AND seq = :s")
                    .apply {
                        bind("i", instanceId)
                        bind("r", run)
                        bind("s", seq)
                    },
            ).getOrThrow()
            .rows
            .isNotEmpty()

    /**
     * Counted because the loss it marks was invisible. A batch skipped as a duplicate is normal
     * when an agent retries and pathological when a whole pod generation collides — and from the
     * outside those looked identical until this number existed.
     */
    internal suspend fun countDuplicate(
        executor: QueryExecutor,
        instanceId: Long,
    ) {
        executor.execute(
            Statement
                .create("UPDATE instance SET duplicate_batches = duplicate_batches + 1 WHERE id = :id")
                .apply { bind("id", instanceId) },
        )
    }

    internal suspend fun writeRecord(
        executor: QueryExecutor,
        serviceId: Long,
        instanceId: Long,
        header: BatchHeader,
        record: LogRecord,
        now: Long,
    ) {
        val day = dayKey(record.ts)
        partitions.ensure(executor, day)

        // A structured record is its own template; an interpolated one is masked into one.
        // Grouping only — redaction already ran in the agent, and doing it in the other order
        // would put a secret into the template table (research 1.10).
        val templateText =
            if (record.isUntrustedMessage) Normalizer.normalize(record.message) else record.message
        val templateId = dictionaries.templateId(executor, templateText)
        val exceptionClassId =
            record.exception?.className?.let { dictionaries.exceptionClassId(executor, it) }

        executor.execute(
            Statement
                .create(
                    """INSERT INTO log_entry_$day
                   (service_id, instance_id, ts, received_at, seq, level, logger, template_id,
                    raw_message, untrusted, exception_class_id, exception_message, stack_trace,
                    trace_id, span_id, fields, redacted, release)
                   VALUES (:service, :instance, :ts, :now, :seq, :level, :logger, :template,
                    :raw, :untrusted, :exClass, :exMessage, :stack,
                    unhex(:trace), unhex(:span), :fields, :redacted, :release)""",
                ).apply {
                    bind("service", serviceId)
                    bind("instance", instanceId)
                    bind("ts", record.ts)
                    bind("now", now)
                    bind("seq", record.seq)
                    bind("level", record.level.name)
                    bind("logger", record.logger)
                    bind("template", templateId)
                    // Only an interpolated message keeps its text: for a structured one the template
                    // is the message, and storing it twice is what research D5 removed.
                    bind("raw", if (record.isUntrustedMessage) record.message else null)
                    bind("untrusted", if (record.isUntrustedMessage) 1 else 0)
                    bind("exClass", exceptionClassId)
                    bind("exMessage", record.exception?.message)
                    bind("stack", record.exception?.stackTrace)
                    bind("trace", record.traceId)
                    bind("span", record.spanId)
                    bind("fields", record.fields?.let { TracyJson.encodeToString(JsonObject(it)) })
                    bind("redacted", record.redacted?.joinToString(","))
                    bind("release", header.release)
                },
        )

        val keys = record.indexed
        if (!keys.isNullOrEmpty()) {
            val entryId = lastInsertId(executor)
            for (key in keys) {
                val value = record.fields?.get(key)?.contentOrNull() ?: continue
                writeEntityRef(
                    executor,
                    serviceId,
                    instanceId,
                    EntityRef(traceId = record.traceId.orEmpty(), key = key, value = value, ts = record.ts),
                    entryId,
                )
            }
        }
    }

    internal suspend fun writeSpan(
        executor: QueryExecutor,
        serviceId: Long,
        instanceId: Long,
        span: Span,
        @Suppress("UNUSED_PARAMETER") now: Long,
    ) {
        val day = dayKey(span.ts)
        partitions.ensure(executor, day)

        executor.execute(
            Statement
                .create(
                    """INSERT INTO span_$day
                   (trace_id, span_id, parent_span_id, service_id, instance_id, kind, name,
                    ts, duration_ms, status, error, fields)
                   VALUES (unhex(:trace), unhex(:span), unhex(:parent), :service, :instance,
                    :kind, :name, :ts, :duration, :status, :error, :fields)""",
                ).apply {
                    bind("trace", span.traceId)
                    bind("span", span.spanId)
                    bind("parent", span.parentSpanId)
                    bind("service", serviceId)
                    bind("instance", instanceId)
                    bind("kind", span.kind.name.lowercase())
                    bind("name", span.name)
                    bind("ts", span.ts)
                    bind("duration", span.durationMs)
                    bind("status", span.status)
                    bind("error", span.error)
                    bind("fields", span.fields?.let { TracyJson.encodeToString(JsonObject(it)) })
                },
        )
    }

    /**
     * [entryId] is null when the body was sampled away. That is not a degenerate case — it is the
     * normal one for a successful request, and the whole reason references exist (research D12).
     */
    internal suspend fun writeEntityRef(
        executor: QueryExecutor,
        serviceId: Long,
        instanceId: Long,
        ref: EntityRef,
        entryId: Long?,
    ) {
        val day = dayKey(ref.ts)
        partitions.ensure(executor, day)
        val keyId = dictionaries.entityKeyId(executor, ref.key)

        // Defence in depth: the agent is told to stop, but an agent that has not received the
        // list yet — or one that ignores it — must not be able to keep filling the table.
        if (budget != null) {
            if (budget.isSuppressed(serviceId, ref.key)) return
            budget.observe(executor, serviceId, keyId, ref.key, ref.ts)
            if (budget.isSuppressed(serviceId, ref.key)) return
        }

        executor.execute(
            Statement
                .create(
                    """INSERT INTO entity_ref_$day
                   (key_id, value, ts, service_id, instance_id, trace_id, entry_id)
                   VALUES (:key, :value, :ts, :service, :instance, unhex(:trace), :entry)""",
                ).apply {
                    bind("key", keyId)
                    bind("value", ref.value)
                    bind("ts", ref.ts)
                    bind("service", serviceId)
                    bind("instance", instanceId)
                    bind("trace", ref.traceId.ifEmpty { null })
                    bind("entry", entryId)
                },
        )
    }

    /**
     * Counters are summed across instances on write, the way metrik merges windows. Which is
     * exactly why the batch has to be idempotent: a redelivery would not duplicate a row here,
     * it would inflate a number.
     */
    internal suspend fun writeCounter(
        executor: QueryExecutor,
        serviceId: Long,
        header: BatchHeader,
        counter: TemplateCount,
    ) {
        val templateId = dictionaries.templateId(executor, counter.template)
        executor.execute(
            Statement
                .create(
                    """INSERT INTO template_count (service_id, template_id, level, release, minute, count)
                   VALUES (:service, :template, :level, :release, :minute, :count)
                   ON CONFLICT(service_id, template_id, level, release, minute)
                   DO UPDATE SET count = count + :count""",
                ).apply {
                    bind("service", serviceId)
                    bind("template", templateId)
                    bind("level", counter.level.name)
                    bind("release", header.release ?: "")
                    bind("minute", counter.windowStart)
                    bind("count", counter.count)
                },
        )
    }

    private suspend fun lastInsertId(executor: QueryExecutor): Long =
        executor
            .fetchAll("SELECT last_insert_rowid();")
            .getOrThrow()
            .rows
            .first()
            .get(0)
            .asLong()
}

private fun JsonPrimitive.contentOrNull(): String? = content.takeIf { it != "null" }
