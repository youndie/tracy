package io.github.youndie.tracy.agent

import io.github.youndie.tracy.template.Normalizer
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.LogRecord
import io.github.youndie.tracy.wire.TemplateCount
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Which messages are trusted, and which only look it.
 *
 * Found on the stand rather than here: four real services connected, and every record they sent
 * arrived marked trusted — including framework logs where SLF4J had already substituted its
 * arguments. One of them was a 9.6 KB HTTP response body, the whole product catalogue, stored
 * verbatim as a message template. The wire field existed, the server honoured it and the
 * normalizer was written and tested; nothing ever set the flag, so the normalizer never ran on
 * anything and `log_template` grew one row per distinct *message* rather than per shape.
 *
 * Two consequences, and the second is the serious one: the storage arithmetic of research D6
 * assumes a small template dictionary, and D8 calls that same table trusted text that gets handed
 * to a coding agent.
 */
class CapturedIsUntrustedTest {
    private fun agent() =
        TracyAgent(
            AgentConfig(service = "s", apiKey = "k", endpoint = "http://x", instanceId = "i", sampleRate = 1.0),
            clock = { 1785542400000L },
            random = { 0.0 },
        )

    private fun TracyAgent.records(): List<LogRecord> = drainBatch().filterIsInstance<LogRecord>()

    @Test
    fun `a structured call stays trusted`() =
        runTest {
            val agent = agent()

            agent.logger("OrdersRouting").info("order created") { field("orderId", "12345") }

            val record = agent.records().single()
            // The developer wrote this constant and put the value in a field. That separation is
            // what makes the text safe to store, index and show — research D8.
            assertFalse(record.isUntrustedMessage)
            assertEquals("order created", record.message)
        }

    @Test
    fun `a captured framework log is untrusted`() {
        val agent = agent()

        agent.accept(
            level = Level.INFO,
            logger = "org.mongodb.driver.cluster",
            message =
                "Monitor thread connected to ServerDescription{address=db-0.internal:27017, " +
                    "roundTripTimeNanos=178281013}",
            cause = null,
            builder = LogBuilder(),
            trace = null,
            untrusted = true,
        )

        val record = agent.records().single()
        // The framework substituted its arguments before tracy saw the string, so the words and
        // the data arrive already mixed and the server has to turn it into a template.
        assertTrue(record.isUntrustedMessage)
    }

    @Test
    fun `a captured log carries its text for the server to normalize`() {
        val agent = agent()
        val body = "RESPONSE: 200 OK BODY START " + "x".repeat(9_000) + " BODY END"

        agent.accept(
            level = Level.INFO,
            logger = "io.ktor.client.HttpClient",
            message = body,
            cause = null,
            builder = LogBuilder(),
            trace = null,
            untrusted = true,
        )

        val record = agent.records().single()
        // The agent does not truncate — the server does, when it makes the template. What the
        // agent must get right is the flag, because without it the server stores the message
        // verbatim as a template and a response body becomes a dictionary entry forever.
        assertTrue(record.isUntrustedMessage, "a 9 KB response body must not be stored as a trusted template")
    }

    /** The clock moves, so a counter window can close and be drained. */
    private var now = 1785542400000L

    private fun movingAgent() =
        TracyAgent(
            AgentConfig(service = "s", apiKey = "k", endpoint = "http://x", instanceId = "i", sampleRate = 1.0),
            clock = { now },
            random = { 0.0 },
        )

    private fun TracyAgent.capture(message: String) =
        accept(
            level = Level.INFO,
            logger = "com.zaxxer.hikari.pool.HikariPool",
            message = message,
            cause = null,
            builder = LogBuilder(),
            trace = null,
            untrusted = true,
        )

    /**
     * M-155, found on the first records konekt-server sent: a captured line was counted under its
     * raw text, so every value was a template of its own — a pool's object hash on every
     * connection — and none of them was the template the server filed the record under.
     */
    @Test
    fun `captured lines that differ only in their values are one counter`() {
        val agent = movingAgent()

        agent.capture("HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@735f7ae5")
        agent.capture("HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@1c9b6a2f")
        now += TemplateCounters.WINDOW_MS

        val counter = agent.drainBatch().filterIsInstance<TemplateCount>().single()
        assertEquals(2, counter.count)
        // The server's own mask, so the counter and the record share one template.
        assertEquals(
            Normalizer.normalize("HikariPool-1 - Added connection org.postgresql.jdbc.PgConnection@735f7ae5"),
            counter.template,
        )
    }

    @Test
    fun `a structured call is counted as written even with a number in it`() =
        runTest {
            val agent = movingAgent()

            agent.logger("Retry").info("retry 3 exhausted")
            now += TemplateCounters.WINDOW_MS

            // The developer's constant is its own template; masking it would split it from its
            // records, which the server stores unmasked.
            assertEquals(
                "retry 3 exhausted",
                agent
                    .drainBatch()
                    .filterIsInstance<TemplateCount>()
                    .single()
                    .template,
            )
        }
}
