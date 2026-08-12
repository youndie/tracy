package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.BatchLine
import kotlin.coroutines.CoroutineContext

/**
 * Carries the trace of the current request through the coroutine context.
 *
 * This is the **only** mechanism available on Kotlin/Native: `ThreadContextElement` is JVM-only
 * and there is no MDC, so a plain non-suspend logger cannot recover the trace id implicitly
 * (research 1.3). Hence the logging API is suspend-first — in a Ktor service every handler
 * already is, so it costs nothing there, and code that logs outside a request simply produces
 * uncorrelated records.
 *
 * The element also holds the records of the request, because the sampling decision is a **tail**
 * decision: whether to keep them is known only once the request has finished (research D7).
 */
public class TracyTraceContext(
    public val traceId: String,
    public val spanId: String,
    /** Inherited or forced upstream decision. A tail decision cannot travel downstream. */
    public val sampledUpstream: Boolean,
) : CoroutineContext.Element {
    override val key: CoroutineContext.Key<*> get() = Key

    private val pending = mutableListOf<BatchLine>()

    /** Set when anything at WARN or above happened, or the response failed. */
    public var hasProblem: Boolean = false
        private set

    public fun add(line: BatchLine) {
        pending += line
    }

    public fun markProblem() {
        hasProblem = true
    }

    public fun takePending(): List<BatchLine> {
        val out = pending.toList()
        pending.clear()
        return out
    }

    public companion object Key : CoroutineContext.Key<TracyTraceContext>
}
