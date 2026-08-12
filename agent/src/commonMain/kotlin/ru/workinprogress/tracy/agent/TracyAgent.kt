package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.EntityRefDeduplicator
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Ties the pieces together: builds a record, redacts it, counts it, and either buffers it for
 * sending or hands it to the request for a tail sampling decision.
 *
 * Ordering here is not arbitrary. Redaction runs **before** anything else can look at the text,
 * because whatever survives it ends up in the template — the table that outlives record bodies,
 * gets indexed and is handed to agents as trusted text (research 1.10).
 */
@OptIn(ExperimentalAtomicApi::class)
public class TracyAgent(
    private val config: AgentConfig,
    private val buffer: RecordBuffer = RecordBuffer(config.maxBufferBytes),
    private val counters: TemplateCounters = TemplateCounters(),
    private val clock: () -> Long,
) : RecordSink {
    private val seq = AtomicLong(0)
    private val dedup = EntityRefDeduplicator()

    public fun logger(name: String): TracyLogger = TracyLogger(name, this)

    override fun isEnabled(level: Level): Boolean = level.atLeast(config.level)

    override fun accept(
        level: Level,
        logger: String,
        message: String,
        cause: Throwable?,
        builder: LogBuilder,
        trace: TracyTraceContext?,
    ) {
        val now = clock()

        // 1. Redact first. Everything downstream — templates, counters, storage — must only ever
        //    see text that has already been through this.
        val redactedMessage = config.redactor.redactMessage(message)
        val redactedFields = config.redactor.redactFields(builder.fields())

        // 2. Count. Exempt from sampling, subject to the level threshold (research D13).
        counters.increment(redactedMessage.text, level, now)

        val redactedNames =
            buildList {
                if (redactedMessage.changed) add(LogRecord.REDACTED_MESSAGE)
                addAll(redactedFields.names)
            }

        val record =
            LogRecord(
                ts = now,
                seq = seq.fetchAndAdd(1),
                level = level,
                logger = logger,
                message = redactedMessage.text,
                fields = redactedFields.fields,
                traceId = trace?.traceId,
                spanId = trace?.spanId,
                exception = cause?.toWire(),
                redacted = redactedNames.ifEmpty { null },
                indexed = builder.indexed(),
            )

        val deduplicated = dedup.apply(record)

        if (trace == null) {
            // No request to attach to, so there is no tail decision to wait for.
            buffer.offer(deduplicated)
            return
        }

        if (level.atLeast(Level.WARN)) trace.markProblem()
        trace.add(deduplicated)
    }

    /** Everything the sender needs to attach to the next batch. */
    public fun drainBatch(): List<ru.workinprogress.tracy.wire.BatchLine> =
        buffer.drain(config.maxBatchBytes) + counters.drainClosed(clock())

    public fun counters(): BufferCounters = buffer.takeCounters()

    public fun buffer(): RecordBuffer = buffer
}

internal fun Throwable.toWire(): ru.workinprogress.tracy.wire.ExceptionInfo =
    ru.workinprogress.tracy.wire.ExceptionInfo(
        className = this::class.simpleName ?: "Throwable",
        message = message,
        stackTrace = stackTraceToString().takeIf { it.isNotBlank() },
    )
