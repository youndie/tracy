package io.github.youndie.tracy.agent

import ch.qos.logback.classic.LoggerContext
import io.github.youndie.tracy.wire.LogRecord
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README's capture step on the JVM, compiled and run (M-150).
 *
 * `install(Tracy)` writes the span of each request and nothing else. A service wired that way looked
 * connected for six weeks while every line it logged stayed in stdout, so the step that closes the gap
 * is held to the same standard as the rest of the quick start: if it stops working, this fails.
 */
class ReadmeCaptureTest {
    private fun agent() =
        TracyAgent(
            config = AgentConfig(service = "orders-api", apiKey = "k", endpoint = "http://unused", instanceId = "i"),
            clock = { 1754049600000L },
        )

    @Test
    fun `a line logged through SLF4J reaches the agent only once the appender is attached`() {
        val tracy = agent()
        val log = LoggerFactory.getLogger("OrdersRouting")

        log.warn("disk almost full")
        assertTrue(tracy.drainBatch().none { it is LogRecord }, "without the capture step tracy gets no records")

        // README, verbatim.
        val context = LoggerFactory.getILoggerFactory() as LoggerContext
        val appender =
            TracyLogbackAppender(tracy).apply {
                this.context = context
                start()
            }
        context.getLogger(Logger.ROOT_LOGGER_NAME).addAppender(appender)

        try {
            log.warn("disk almost full")
            val record = tracy.drainBatch().filterIsInstance<LogRecord>().single()
            assertEquals("OrdersRouting", record.logger)
        } finally {
            // The logger context is global to the test JVM; a leftover appender would feed this
            // agent from every other test.
            context.getLogger(Logger.ROOT_LOGGER_NAME).detachAppender(appender)
            appender.stop()
        }
    }
}
