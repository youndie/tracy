package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceParent
import kotlin.coroutines.coroutineContext
import kotlin.time.TimeSource

/**
 * Marks a stretch of code as a span.
 *
 * Manual and deliberately so: tracy instruments the boundaries automatically (incoming request,
 * outgoing HTTP call) and nothing else. Auto-instrumenting database drivers, caches and queues is
 * a project of a different size, and time nobody wrapped shows up as unattributed rather than
 * disappearing (research D1).
 *
 * Outside a request this is a no-op that still runs [block]: there is no parent to attach to, and
 * that follows from research 1.3 — a span cannot find its parent in non-suspend or untraced code.
 */
public suspend inline fun <T> withSpan(
    name: String,
    agent: TracyAgent,
    crossinline block: suspend () -> T,
): T {
    val trace = coroutineContext[TracyTraceContext] ?: return block()
    val started = TimeSource.Monotonic.markNow()
    val spanId = TraceParent.newSpanId()
    var failed = false
    try {
        return block()
    } catch (t: Throwable) {
        failed = true
        throw t
    } finally {
        agent.recordSpan(
            trace = trace,
            span =
                Span(
                    traceId = trace.traceId,
                    spanId = spanId,
                    parentSpanId = trace.spanId,
                    name = name,
                    kind = SpanKind.INTERNAL,
                    ts = agent.now() - started.elapsedNow().inWholeMilliseconds,
                    durationMs = started.elapsedNow().inWholeMilliseconds.toInt(),
                    error = if (failed) 1 else null,
                ),
        )
        if (failed) trace.markProblem()
    }
}
