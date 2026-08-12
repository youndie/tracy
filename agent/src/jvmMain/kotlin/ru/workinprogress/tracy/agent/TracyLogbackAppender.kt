package ru.workinprogress.tracy.agent

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import ru.workinprogress.tracy.wire.Level

/**
 * Captures logs on the JVM, where almost everything — Ktor included — goes through SLF4J.
 *
 * Attached alongside the existing appenders rather than replacing them: stdout keeps working,
 * for the same reason as on native (research D10).
 *
 * Records captured here carry no trace. MDC could supply one on the JVM, but tracy does not put
 * anything there: the correlation mechanism has to be the one that also works on native, and
 * there is none (research 1.3). Promising correlation on one platform only would make the same
 * log line behave differently depending on where it runs.
 */
public class TracyLogbackAppender(
    private val sink: RecordSink,
) : AppenderBase<ILoggingEvent>() {
    override fun append(event: ILoggingEvent) {
        val level = event.level.toTracy() ?: return
        if (!sink.isEnabled(level)) return

        val builder = LogBuilder()
        event.mdcPropertyMap.forEach { (key, value) -> builder.field(key, value) }

        runCatching {
            sink.accept(
                level = level,
                logger = event.loggerName,
                message = event.formattedMessage.orEmpty(),
                cause = event.throwableProxy?.let { IllegalStateException(it.className + ": " + it.message) },
                builder = builder,
                trace = null,
            )
        }
    }
}

private fun ch.qos.logback.classic.Level.toTracy(): Level? =
    when (levelInt) {
        ch.qos.logback.classic.Level.TRACE_INT -> Level.TRACE
        ch.qos.logback.classic.Level.DEBUG_INT -> Level.DEBUG
        ch.qos.logback.classic.Level.INFO_INT -> Level.INFO
        ch.qos.logback.classic.Level.WARN_INT -> Level.WARN
        ch.qos.logback.classic.Level.ERROR_INT -> Level.ERROR
        else -> null
    }
