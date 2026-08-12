package ru.workinprogress.tracy.agent

import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.header
import io.ktor.server.request.httpMethod
import io.ktor.server.routing.RoutingRoot
import io.ktor.util.AttributeKey
import kotlinx.coroutines.withContext
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceParent
import kotlin.time.TimeSource

public class TracyPluginConfig {
    public var agent: TracyAgent? = null

    /** Header that forces a trace to be kept, for debugging one specific user. */
    public var forceHeader: String = "X-Tracy-Force"
}

internal val RouteTemplateKey: AttributeKey<String> = AttributeKey("tracy.route")

/**
 * Server plugin: reads or starts a trace, opens the incoming-request span, and makes the tail
 * sampling decision when the request finishes.
 *
 * Three things here are consequences of what metrik paid for, not choices:
 *
 * - the route **template** is taken from the `RoutingCallStarted` event rather than from
 *   `routingCallKey`, which is `internal`, or the `Metrics` hook, which is `@InternalAPI`.
 *   Third-party plugins do not get those;
 * - `RoutingNode.toString()` prints the whole branch of selectors — `/users/{id}/(method:GET)` —
 *   so everything in brackets has to go;
 * - the span name is the template and never the request path. A raw path would explode
 *   cardinality and, worse, would carry user data into a span name that reads as trusted.
 */
public val Tracy: io.ktor.server.application.ApplicationPlugin<TracyPluginConfig> =
    createApplicationPlugin("Tracy", ::TracyPluginConfig) {
        val agent =
            requireNotNull(pluginConfig.agent) {
                "tracy: install(Tracy) { agent = ... } is required"
            }
        val forceHeader = pluginConfig.forceHeader

        application.monitor.subscribe(RoutingRoot.RoutingCallStarted) { call ->
            call.attributes.put(RouteTemplateKey, sanitizeRoute(call.route.toString()))
        }

        // Wrapping the pipeline rather than using hooks: the trace has to be *inside* the
        // coroutine context of the handler, and on Kotlin/Native there is no other way to get it
        // there (research 1.3).
        application.intercept(ApplicationCallPipeline.Plugins) {
            val call = context
            val incoming = TraceParent.parse(call.request.header(TraceParent.HEADER))
            val forced = call.request.header(forceHeader) != null
            val trace =
                TracyTraceContext(
                    traceId = incoming?.traceId ?: TraceParent.newTrace().traceId,
                    spanId = TraceParent.newSpanId(),
                    sampledUpstream = incoming?.sampled == true || forced,
                )
            val parentSpanId = incoming?.parentId
            val started = TimeSource.Monotonic.markNow()
            val startedAt = agent.now()

            var failed = false
            try {
                withContext(trace) { proceed() }
            } catch (t: Throwable) {
                failed = true
                trace.markProblem()
                throw t
            } finally {
                val durationMs = started.elapsedNow().inWholeMilliseconds
                val status = call.response.status()?.value
                val method = call.request.httpMethod.value
                val template = call.attributes.getOrNull(RouteTemplateKey) ?: UNMATCHED

                agent.finishRequest(
                    trace = trace,
                    span =
                        Span(
                            traceId = trace.traceId,
                            spanId = trace.spanId,
                            parentSpanId = parentSpanId,
                            name = "$method $template",
                            kind = SpanKind.SERVER,
                            ts = startedAt,
                            durationMs = durationMs.toInt(),
                            status = status,
                            error = if (failed) 1 else null,
                        ),
                    durationMs = durationMs,
                    statusCode = status,
                    forced = forced,
                )
            }
        }
    }

/** A request that matched no route at all — a 404, or a response produced before routing. */
internal const val UNMATCHED: String = "<unmatched>"

/**
 * `RoutingNode.toString()` yields `/users/{id}/(method:GET)`; everything in brackets is a selector
 * and not part of the path.
 */
internal fun sanitizeRoute(raw: String): String {
    val cleaned =
        raw
            .split('/')
            .filter { it.isNotEmpty() && !it.startsWith("(") }
            .joinToString("/")
    return if (cleaned.isEmpty()) "/" else "/$cleaned"
}
