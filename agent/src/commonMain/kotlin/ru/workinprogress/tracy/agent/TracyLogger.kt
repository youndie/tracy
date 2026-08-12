package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.Level
import kotlin.coroutines.coroutineContext

/**
 * The logging API.
 *
 * Every entry point is `inline` so that a suppressed call allocates nothing: the level is checked
 * before the block runs, so the lambda is never materialised and the fields are never built. That
 * is a requirement rather than a nicety — this code sits on the hot path of somebody else's
 * service (risk 1).
 *
 * The functions are **suspend**, and that is a deliberate consequence of research 1.3: on
 * Kotlin/Native the trace can only come from the coroutine context, because `ThreadContextElement`
 * is JVM-only and there is no MDC. In a Ktor service every handler is already suspend, so this
 * costs nothing where it matters. Code that must log from a non-suspend path calls [RecordSink]
 * directly and gets an uncorrelated record — which is exactly what the docs promise, no more.
 *
 * The message is a constant the developer wrote; the values go into fields. Keeping them apart is
 * what lets tracy hand the message to a coding agent as trusted text while treating the values as
 * hostile (research D8).
 */
public class TracyLogger
    @PublishedApi
    internal constructor(
        @PublishedApi internal val name: String,
        @PublishedApi internal val sink: RecordSink,
    ) {
        public fun isEnabled(level: Level): Boolean = sink.isEnabled(level)

        public suspend inline fun trace(
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ): Unit = log(Level.TRACE, message, cause, build)

        public suspend inline fun debug(
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ): Unit = log(Level.DEBUG, message, cause, build)

        public suspend inline fun info(
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ): Unit = log(Level.INFO, message, cause, build)

        public suspend inline fun warn(
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ): Unit = log(Level.WARN, message, cause, build)

        public suspend inline fun error(
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ): Unit = log(Level.ERROR, message, cause, build)

        public suspend inline fun log(
            level: Level,
            message: String,
            cause: Throwable? = null,
            build: LogBuilder.() -> Unit = {},
        ) {
            // Nothing below this line runs for a suppressed record — not the block, not the
            // fields, not the template counter.
            if (!sink.isEnabled(level)) return
            sink.accept(
                level = level,
                logger = name,
                message = message,
                cause = cause,
                builder = LogBuilder().apply(build),
                trace = coroutineContext[TracyTraceContext],
            )
        }
    }

/**
 * What a logger writes into. Separated so the hot path can be tested without a running agent, and
 * so that non-suspend capture paths (an SLF4J appender, a kotlin-logging appender) have somewhere
 * to hand a record with `trace = null`.
 */
public interface RecordSink {
    public fun isEnabled(level: Level): Boolean

    public fun accept(
        level: Level,
        logger: String,
        message: String,
        cause: Throwable?,
        builder: LogBuilder,
        trace: TracyTraceContext? = null,
        /**
         * True when the message is a formatted string rather than a constant the developer wrote.
         *
         * This is the whole of research D8 in one parameter. A structured call already *is* its
         * template — `log.info("order created") { field("orderId", id) }` — and its text can be
         * stored, indexed and handed to an agent as trusted. A captured framework log is not:
         * SLF4J and kotlin-logging substitute `{}` before tracy ever sees the string, so the data
         * and the developer's words arrive already mixed.
         */
        untrusted: Boolean = false,
    )
}
