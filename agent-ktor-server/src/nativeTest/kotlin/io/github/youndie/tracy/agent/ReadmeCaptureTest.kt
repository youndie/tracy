package io.github.youndie.tracy.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.youndie.tracy.wire.LogRecord
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The README's capture step on Kotlin/Native, compiled and run (M-150).
 *
 * `install(Tracy)` writes the span of each request and nothing else. A service wired that way looked
 * connected for six weeks while every line it logged stayed in stdout, so the step that closes the gap
 * is held to the same standard as the rest of the quick start: if it stops working, this fails.
 */
class ReadmeCaptureTest {
    private val originalAppender = KotlinLoggingConfiguration.direct.appender
    private val originalFactory = KotlinLoggingConfiguration.loggerFactory

    @AfterTest
    fun restore() {
        KotlinLoggingConfiguration.loggerFactory = originalFactory
        KotlinLoggingConfiguration.direct.appender = originalAppender
    }

    private fun agent() =
        TracyAgent(
            config = AgentConfig(service = "orders-api", apiKey = "k", endpoint = "http://unused", instanceId = "i"),
            clock = { 1754049600000L },
        )

    @Test
    fun `a line logged through kotlin-logging reaches the agent only once captured`() {
        val tracy = agent()

        KotlinLogging.logger("OrdersRouting").warn { "disk almost full" }
        assertTrue(tracy.drainBatch().none { it is LogRecord }, "without the capture step tracy gets no records")

        // README, verbatim. On Apple targets only loggers obtained after this call are seen, which is
        // why the logger below is a fresh one.
        tracy.captureKotlinLogging()

        KotlinLogging.logger("OrdersRouting").warn { "disk almost full" }
        val record = tracy.drainBatch().filterIsInstance<LogRecord>().single()
        assertEquals("OrdersRouting", record.logger)
    }
}
