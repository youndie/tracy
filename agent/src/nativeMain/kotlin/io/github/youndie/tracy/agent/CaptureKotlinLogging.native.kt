package io.github.youndie.tracy.agent

import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Level
import io.github.oshai.kotlinlogging.Marker

/**
 * Installs tracy into kotlin-logging, through whichever mechanism kotlin-logging is actually using.
 *
 * Native only, and deliberately unavailable on the JVM. There, direct logging is not the active
 * mechanism: swapping the appender would mean switching the whole application to
 * `DirectLoggerFactory`, which silently disables SLF4J — the host's logback configuration and
 * every library that logs through SLF4J would go with it (research 1.4). A library that does that
 * to its host is broken, so the JVM path is an SLF4J appender instead.
 *
 * The same reasoning holds on Apple targets, which is why there are two paths here. On Linux the
 * active factory is [DirectLoggerFactory] and every logger reads `direct.appender` at the moment it
 * writes, so [TracyAppender] goes there and sees every logger, including ones created earlier. On
 * macOS and iOS kotlin-logging's default is `DarwinLoggerFactory`, which writes to os_log and never
 * reads `direct.appender` at all; switching the host to the direct factory would take its logs out
 * of the unified log and put them on stdout. So there the factory is wrapped instead: os_log keeps
 * every line, tracy gets a copy — but only from loggers obtained **after** this call, because a
 * logger already handed out belongs to the old factory and nothing can reach inside it.
 */
public fun TracyAgent.captureKotlinLogging() {
    val factory = KotlinLoggingConfiguration.loggerFactory
    if (factory == DirectLoggerFactory) {
        val previous = KotlinLoggingConfiguration.direct.appender
        KotlinLoggingConfiguration.direct.appender = TracyAppender(this, previous)
    } else {
        KotlinLoggingConfiguration.loggerFactory = TracyLoggerFactory(this, factory)
    }
}

private class TracyLoggerFactory(
    private val sink: RecordSink,
    private val delegate: KLoggerFactory,
) : KLoggerFactory {
    override fun logger(name: String): KLogger = TracyKLogger(sink, delegate.logger(name))
}

/**
 * The host's logger with a tap on it. The host's logger evaluates the message, as it would without
 * tracy, and tracy reads the builder it filled — so a message lambda runs once, not once per
 * destination, and `isXxxEnabled` answers for both of them.
 */
private class TracyKLogger(
    private val sink: RecordSink,
    private val delegate: KLogger,
) : KLogger {
    override val name: String get() = delegate.name

    override fun isLoggingEnabledFor(
        level: Level,
        marker: Marker?,
    ): Boolean = delegate.isLoggingEnabledFor(level, marker) || sink.wants(level)

    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "a message lambda the host never asked to run may not throw into the host's call",
    )
    override fun at(
        level: Level,
        marker: Marker?,
        block: KLoggingEventBuilder.() -> Unit,
    ) {
        var built: KLoggingEventBuilder? = null
        // The host's own output first, and its failures are its own: without tracy they would
        // reach the caller too.
        delegate.at(level, marker) {
            block()
            built = this
        }
        if (!sink.wants(level)) return
        // Below the host's threshold the lambda has not run yet, and a lambda that throws there
        // never threw before tracy was installed: it must not start now.
        val event = built ?: runCatching { KLoggingEventBuilder().apply(block) }.getOrNull() ?: return
        sink.capture(KLoggingEvent(level, marker, name, event))
    }
}
