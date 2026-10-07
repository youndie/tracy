package io.github.youndie.tracy.agent

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.DirectLoggerFactory
import io.github.oshai.kotlinlogging.KLogger
import io.github.oshai.kotlinlogging.KLoggerFactory
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.oshai.kotlinlogging.KLoggingEventBuilder
import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.github.oshai.kotlinlogging.Marker
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.LogRecord
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M-27 — what can actually be captured on Kotlin/Native.
 *
 * research D3 listed this as a hypothesis: "kotlin-logging is intercepted; `println` and Ktor's
 * own logger are not — verify in M2". Guessing here would be dishonest in a specific way, because
 * the answer decides what the README may promise. A user who believes tracy sees everything and
 * then loses the one line that mattered is worse off than one who was told the boundary.
 *
 * Two mechanisms sit behind one call, and which one runs is kotlin-logging's choice, not the
 * platform's name: Linux defaults to the direct factory, Apple targets to os_log. The tests that
 * use the default exercise whichever this host has; the ones that pin a factory exercise the other
 * path too, so a Linux run alone cannot hide the Apple one again.
 */
class NativeCaptureTest {
    private val originalAppender = KotlinLoggingConfiguration.direct.appender
    private val originalFactory = KotlinLoggingConfiguration.loggerFactory

    @AfterTest
    fun restore() {
        KotlinLoggingConfiguration.loggerFactory = originalFactory
        KotlinLoggingConfiguration.direct.appender = originalAppender
    }

    /** Stands in for a factory that is not the direct one — os_log on Apple targets. */
    private class CountingFactory : KLoggerFactory {
        var delegated = 0
        var evaluated = 0

        override fun logger(name: String): KLogger =
            object : KLogger {
                override val name = name

                override fun isLoggingEnabledFor(
                    level: io.github.oshai.kotlinlogging.Level,
                    marker: Marker?,
                ) = true

                override fun at(
                    level: io.github.oshai.kotlinlogging.Level,
                    marker: Marker?,
                    block: KLoggingEventBuilder.() -> Unit,
                ) {
                    KLoggingEventBuilder().apply(block)
                    delegated++
                }
            }
    }

    private fun agent() =
        TracyAgent(
            config =
                AgentConfig(
                    service = "s",
                    apiKey = "k",
                    endpoint = "http://x",
                    instanceId = "i",
                    level = Level.TRACE,
                ),
            clock = { 1754049600000L },
        )

    @Test
    fun `logs written through kotlin-logging are captured`() {
        val agent = agent()
        agent.captureKotlinLogging()

        KotlinLogging.logger("SomebodyElse").info { "third party говорит" }

        val record = agent.drainBatch().filterIsInstance<LogRecord>().single()
        assertEquals("SomebodyElse", record.logger)
        assertEquals("third party говорит", record.message)
    }

    @Test
    fun `a captured record carries no trace`() {
        val agent = agent()
        agent.captureKotlinLogging()

        KotlinLogging.logger("SomebodyElse").warn { "no trace here" }

        // Appender.log is not suspend and KLoggingEvent has no trace id: there is nowhere to
        // recover one from (research 1.3). Documented limit, not an oversight.
        assertNull(
            agent
                .drainBatch()
                .filterIsInstance<LogRecord>()
                .single()
                .traceId,
        )
    }

    @Test
    fun `the previous appender keeps running where the factory is direct`() {
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val agent = agent()
        var delegated = 0
        KotlinLoggingConfiguration.direct.appender =
            object : Appender {
                override fun log(loggingEvent: KLoggingEvent) {
                    delegated++
                }
            }
        agent.captureKotlinLogging()

        KotlinLogging.logger("X").info { "hello" }

        // stdout must survive: tracy is not the only copy of the logs (research D10).
        assertEquals(1, delegated)
        assertEquals(1, agent.drainBatch().filterIsInstance<LogRecord>().size)
    }

    @Test
    fun `the previous factory keeps running where it is not direct`() {
        val previous = CountingFactory()
        KotlinLoggingConfiguration.loggerFactory = previous
        val agent = agent()
        agent.captureKotlinLogging()

        KotlinLogging.logger("X").info { "hello" }

        // os_log on Apple targets is the host's copy, the same way stdout is on Linux: the host
        // is not switched to the direct factory to make room for tracy.
        assertEquals(1, previous.delegated)
        assertEquals(1, agent.drainBatch().filterIsInstance<LogRecord>().size)
    }

    @Test
    fun `a message is built once for both destinations`() {
        KotlinLoggingConfiguration.loggerFactory = CountingFactory()
        val agent = agent()
        agent.captureKotlinLogging()
        var built = 0

        KotlinLogging.logger("X").info {
            built++
            "hello"
        }

        assertEquals(1, built)
        assertEquals(
            "hello",
            agent
                .drainBatch()
                .filterIsInstance<LogRecord>()
                .single()
                .message,
        )
    }

    @Test
    fun `a logger obtained before capture is seen where the factory is direct`() {
        KotlinLoggingConfiguration.loggerFactory = DirectLoggerFactory
        val early = KotlinLogging.logger("Early")
        val agent = agent()
        agent.captureKotlinLogging()

        early.info { "written by a logger older than tracy" }

        assertEquals(1, agent.drainBatch().filterIsInstance<LogRecord>().size)
    }

    @Test
    fun `a logger obtained before capture is not seen where the factory is wrapped`() {
        KotlinLoggingConfiguration.loggerFactory = CountingFactory()
        val early = KotlinLogging.logger("Early")
        val agent = agent()
        agent.captureKotlinLogging()

        early.info { "written by a logger older than tracy" }

        // The boundary on Apple targets: a logger already handed out belongs to the old factory.
        // If this ever starts failing, the KDoc of captureKotlinLogging needs rewriting.
        assertTrue(agent.drainBatch().filterIsInstance<LogRecord>().isEmpty())
    }

    @Test
    fun `redaction applies to captured third party logs too`() {
        val agent = agent()
        agent.captureKotlinLogging()

        // This is the real shape: the token in production logs was written by a library, not by
        // application code (research 1.10).
        KotlinLogging.logger("KtorClient").info {
            "GET https://api.telegram.org/bot1234567890:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E/getUpdates"
        }

        val record = agent.drainBatch().filterIsInstance<LogRecord>().single()
        assertTrue("AAFqqqZrh" !in record.message)
    }

    @Test
    fun `the level threshold applies to captured logs`() {
        val agent =
            TracyAgent(
                config =
                    AgentConfig(
                        service = "s",
                        apiKey = "k",
                        endpoint = "http://x",
                        instanceId = "i",
                        level = Level.WARN,
                    ),
                clock = { 1L },
            )
        agent.captureKotlinLogging()

        KotlinLogging.logger("X").info { "below the floor" }

        assertTrue(agent.drainBatch().filterIsInstance<LogRecord>().isEmpty())
    }

    @Test
    fun `println is not captured and that is the boundary`() {
        val agent = agent()
        agent.captureKotlinLogging()

        println("this line goes nowhere near tracy")

        assertTrue(
            agent.drainBatch().filterIsInstance<LogRecord>().isEmpty(),
            "if this ever starts passing, the README boundary needs rewriting",
        )
    }

    @Test
    fun `Ktor own logging is not captured on native`() {
        val agent = agent()
        agent.captureKotlinLogging()

        val server =
            embeddedServer(CIO, port = 0) {
                environment.log.info("a line from the Ktor application logger")
                routing { get("/") { call.respondText("ok") } }
            }
        server.start(wait = false)
        try {
            runBlocking { server.engine.resolvedConnectors() }
        } finally {
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }

        val captured = agent.drainBatch().filterIsInstance<LogRecord>()

        // Measured, not assumed: Ktor's own logger on Kotlin/Native does not go through
        // kotlin-logging, so nothing tracy installs can see it. This assertion pins the boundary
        // the README promises — if it ever fails, the promise widened and the docs must follow.
        assertTrue(
            captured.isEmpty(),
            "Ktor own logs became visible on native (${captured.map { it.logger }}) — update the docs",
        )
    }
}
