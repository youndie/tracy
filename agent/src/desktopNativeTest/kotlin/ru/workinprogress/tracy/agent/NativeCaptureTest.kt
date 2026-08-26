package ru.workinprogress.tracy.agent

import io.github.oshai.kotlinlogging.KotlinLogging
import io.github.oshai.kotlinlogging.KotlinLoggingConfiguration
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.runBlocking
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
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
 */
class NativeCaptureTest {
    private val original = KotlinLoggingConfiguration.direct.appender

    @AfterTest
    fun restore() {
        KotlinLoggingConfiguration.direct.appender = original
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
    fun `the previous appender keeps running`() {
        val agent = agent()
        var delegated = 0
        KotlinLoggingConfiguration.direct.appender =
            object : io.github.oshai.kotlinlogging.Appender {
                override fun log(loggingEvent: io.github.oshai.kotlinlogging.KLoggingEvent) {
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
