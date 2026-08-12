package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.EntityRefDeduplicator
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
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
    /** Injectable so the sampling decision is testable without relying on luck. */
    private val random: () -> Double = { kotlin.random.Random.nextDouble() },
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

    public fun now(): Long = clock()

    /**
     * Redaction for text that is not a log message but still ends up stored — a span name, for
     * instance. Same rule, same reason: a credential in low-cardinality structure that reads as
     * trusted is worse than one in a record body (research 1.10).
     */
    @PublishedApi
    internal fun redactText(text: String): String = config.redactor.redactMessage(text).text

    @PublishedApi
    internal fun recordSpan(
        trace: TracyTraceContext,
        span: Span,
    ) {
        if (config.spans) trace.add(span)
    }

    /**
     * The tail decision (research D7). Everything of the request has been waiting for it, because
     * whether the request is interesting is only known once it has finished.
     *
     * Note what does **not** happen here: nothing is propagated downstream. By now the outgoing
     * calls have already been made, so a tail decision cannot reach them — only the head decision
     * could, and it did. For errors that is enough: they travel up as responses and every service
     * reaches the same conclusion on its own.
     */
    public fun finishRequest(
        trace: TracyTraceContext,
        span: Span?,
        durationMs: Long,
        statusCode: Int?,
        forced: Boolean = false,
    ) {
        val pending = trace.takePending()

        val keepAll =
            trace.hasProblem ||
                (statusCode != null && statusCode >= 500) ||
                durationMs >= config.slowThreshold.inWholeMilliseconds ||
                trace.sampledUpstream ||
                forced ||
                random() < config.sampleRate

        if (keepAll) {
            pending.forEach { buffer.offer(it) }
            span?.let { if (config.spans) buffer.offer(it) }
            return
        }

        // Dropped, but the floor still applies: warnings, and entity references without bodies.
        // Spans follow the trace — keeping one per request cost ~1.2 GB a day at 100 rps, sixteen
        // times the rest of the budget put together (research D7).
        for (line in pending) {
            when {
                line !is LogRecord -> Unit
                line.level.atLeast(Level.WARN) -> buffer.offer(line)
                else -> emitRefsWithoutBody(line)
            }
        }
    }

    private fun emitRefsWithoutBody(record: LogRecord) {
        val keys = record.indexed ?: return
        val traceId = record.traceId ?: return
        for (key in keys) {
            val value = record.fields?.get(key)?.content ?: continue
            buffer.offer(
                EntityRef(
                    traceId = traceId,
                    key = key,
                    value = value,
                    ts = record.ts,
                ),
            )
        }
    }

    /** Everything the sender needs to attach to the next batch. */
    public fun drainBatch(): List<BatchLine> = buffer.drain(config.maxBatchBytes) + counters.drainClosed(clock())

    public fun counters(): BufferCounters = buffer.takeCounters()

    public fun buffer(): RecordBuffer = buffer
}

internal fun Throwable.toWire(): ru.workinprogress.tracy.wire.ExceptionInfo =
    ru.workinprogress.tracy.wire.ExceptionInfo(
        className = this::class.simpleName ?: "Throwable",
        message = message,
        stackTrace = stackTraceToString().takeIf { it.isNotBlank() },
    )
