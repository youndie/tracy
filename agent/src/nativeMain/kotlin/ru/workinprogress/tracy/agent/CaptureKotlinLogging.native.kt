package ru.workinprogress.tracy.agent

import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration

/**
 * Installs [TracyAppender] into kotlin-logging.
 *
 * Native only, and deliberately unavailable on the JVM. There, direct logging is not the active
 * mechanism: swapping the appender would mean switching the whole application to
 * `DirectLoggerFactory`, which silently disables SLF4J — the host's logback configuration and
 * every library that logs through SLF4J would go with it (research 1.4). A library that does that
 * to its host is broken, so the JVM path is an SLF4J appender instead.
 */
public fun TracyAgent.captureKotlinLogging() {
    val previous = KotlinLoggingConfiguration.direct.appender
    KotlinLoggingConfiguration.direct.appender = TracyAppender(this, previous)
}
