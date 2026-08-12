package ru.workinprogress.tracy.agent

import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import kotlinx.coroutines.currentCoroutineContext
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceParent
import kotlin.time.TimeSource

public class TracyClientConfig {
    public var agent: TracyAgent? = null
}

/**
 * Client plugin: opens a span around an outgoing call and passes the trace on.
 *
 * The header carries **our** span id as `parent-id`, so the callee hangs off this call rather than
 * off the request that caused it. That is what turns a flat list of services into the tree
 * `get_trace` returns.
 *
 * The `sampled` bit that travels is the **head** decision only — inherited or forced. A tail
 * decision cannot go downstream because by the time it is made the call has already happened
 * (research D7). For errors this costs nothing: they come back up as responses and every service
 * decides for itself.
 *
 * Outside a trace the plugin does nothing at all: there is no parent, and inventing one would
 * produce a trace that nothing else knows about.
 */
public val TracyClient: io.ktor.client.plugins.api.ClientPlugin<TracyClientConfig> =
    createClientPlugin("TracyClient", ::TracyClientConfig) {
        val agent =
            requireNotNull(pluginConfig.agent) {
                "tracy: install(TracyClient) { agent = ... } is required"
            }

        on(Send) { request ->
            val trace = currentCoroutineContext()[TracyTraceContext] ?: return@on proceed(request)

            val spanId = TraceParent.newSpanId()
            request.headers.remove(TraceParent.HEADER)
            request.headers.append(
                TraceParent.HEADER,
                TraceParent(trace.traceId, spanId, trace.sampledUpstream).toHeader(),
            )

            val started = TimeSource.Monotonic.markNow()
            val startedAt = agent.now()
            var failed = false
            var status: Int? = null
            try {
                val call = proceed(request)
                status = call.response.status.value
                if (status >= 500) trace.markProblem()
                call
            } catch (t: Throwable) {
                failed = true
                trace.markProblem()
                throw t
            } finally {
                agent.recordSpan(
                    trace,
                    Span(
                        traceId = trace.traceId,
                        spanId = spanId,
                        parentSpanId = trace.spanId,
                        name = spanName(request.method.value, request.url.buildString(), agent),
                        kind = SpanKind.CLIENT,
                        ts = startedAt,
                        durationMs = started.elapsedNow().inWholeMilliseconds.toInt(),
                        status = status,
                        error = if (failed || (status ?: 0) >= 500) 1 else null,
                    ),
                )
            }
        }
    }

/**
 * Name of an outgoing span: method, scheme, host and path — with the query string dropped and
 * redaction applied.
 *
 * The docs originally wrote this as the full URL. Measuring real logs showed why that is wrong:
 * the live token found in production was sitting in a URL path written by Ktor's own client
 * logging (research 1.10). A span name is low-cardinality structure that gets treated as trusted,
 * which makes it the worst possible place for a credential. The query string goes for the same
 * reason plus cardinality — it is where keys and per-request values live.
 */
internal fun spanName(
    method: String,
    url: String,
    agent: TracyAgent,
): String {
    val withoutQuery = url.substringBefore('?').substringBefore('#')
    return "$method ${agent.redactText(withoutQuery)}"
}
