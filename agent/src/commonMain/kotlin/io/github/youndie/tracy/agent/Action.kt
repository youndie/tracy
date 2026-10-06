package io.github.youndie.tracy.agent

import io.github.youndie.tracy.wire.Span
import io.github.youndie.tracy.wire.SpanKind
import io.github.youndie.tracy.wire.TraceParent
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext
import kotlin.time.TimeSource

/**
 * A trace that starts in an app: one thing a person did, and everything it caused (research-clients K2).
 *
 * On a server the root of a trace is the incoming request, and `install(Tracy)` opens it. An app
 * has no incoming request, so before this existed nothing opened a trace there at all — and
 * [TracyClient], which does nothing outside a trace, sent no `traceparent`. The chain began on the
 * server, without the step tracy is read for: what the person did.
 *
 * ```kotlin
 * tracy.action("checkout.submit") {
 *     http.post("/orders") { … }   // carries the trace; the service continues it
 * }
 * ```
 *
 * Inside an existing trace this is a span of that trace, like [withSpan], and opens nothing new.
 *
 * Opening one is a call the app makes, not something done to every tap. A span per click is
 * automatic instrumentation, which research D1 turned down for the server for the same reason: it
 * records a lot of things nobody asked about.
 *
 * Sampling works as on a server (research D7). The head decision is made here: when it says keep, the
 * `sampled` bit travels with every outgoing call and every service keeps its part. The tail decision
 * is made when the action ends: a failure, a warning or a slow action keeps the app's own records
 * even when the head said drop.
 */
public suspend fun <T> TracyAgent.action(
    name: String,
    block: suspend () -> T,
): T {
    if (currentCoroutineContext()[TracyTraceContext] != null) return withSpan(name, this) { block() }

    val trace =
        TracyTraceContext(
            traceId = TraceParent.newTrace().traceId,
            spanId = TraceParent.newSpanId(),
            sampledUpstream = headSample(),
        )
    val started = TimeSource.Monotonic.markNow()
    val startedAt = now()
    var failed = false
    try {
        return withContext(trace) { block() }
    } catch (t: Throwable) {
        failed = true
        trace.markProblem()
        throw t
    } finally {
        val durationMs = started.elapsedNow().inWholeMilliseconds
        finishRequest(
            trace = trace,
            span =
                Span(
                    traceId = trace.traceId,
                    spanId = trace.spanId,
                    parentSpanId = null,
                    name = redactText(name),
                    kind = SpanKind.INTERNAL,
                    ts = startedAt,
                    durationMs = durationMs.toInt(),
                    error = if (failed) 1 else null,
                ),
            durationMs = durationMs,
            statusCode = null,
            sampleAtTail = false,
        )
    }
}
