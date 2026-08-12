package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BatchLineTest {
    private val record =
        LogRecord(
            ts = 1754049600123,
            seq = 42,
            level = Level.INFO,
            logger = "OrdersRouting",
            message = "user logged in",
            fields = mapOf("userId" to JsonPrimitive(7)),
        )

    @Test
    fun `log record needs no discriminator`() {
        val json = TracyJson.encodeToString<BatchLine>(record)

        assertTrue("\"k\"" !in json, "the most frequent line must not pay for a discriminator")
        assertTrue("\"m\":\"user logged in\"" in json)
    }

    @Test
    fun `absent values do not reach the wire`() {
        val json = TracyJson.encodeToString<BatchLine>(record)

        assertTrue("null" !in json, "nulls would inflate every single line")
        assertTrue("\"u\"" !in json)
        assertTrue("\"tr\"" !in json)
    }

    @Test
    fun `span carries its discriminator`() {
        val span =
            Span(
                traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                spanId = "00f067aa0ba902b7",
                name = "POST /orders",
                kind = SpanKind.SERVER,
                ts = 1754049600120,
                durationMs = 412,
            )

        val json = TracyJson.encodeToString<BatchLine>(span)

        // Regression against the MCP SDK bug shape: a defaulted discriminator that never
        // gets written breaks every reader (research 1.8).
        assertTrue("\"k\":\"s\"" in json, "discriminator was dropped as a default value")
        assertTrue("\"ki\":\"server\"" in json)
    }

    @Test
    fun `counter carries its discriminator`() {
        val counter =
            TemplateCount(
                windowStart = 1754049600000,
                template = "order <num> not found",
                level = Level.WARN,
                count = 40000,
            )

        assertTrue("\"k\":\"c\"" in TracyJson.encodeToString<BatchLine>(counter))
    }

    @Test
    fun `lines round trip through the discriminator`() {
        val lines: List<BatchLine> =
            listOf(
                record,
                Span("4bf9", "00f0", name = "GET /x", kind = SpanKind.CLIENT, ts = 1, durationMs = 5),
                TemplateCount(windowStart = 1, template = "t", level = Level.ERROR, count = 2),
            )

        for (line in lines) {
            val decoded = TracyJson.decodeFromString<BatchLine>(TracyJson.encodeToString(line))
            assertEquals(line, decoded)
        }
    }

    @Test
    fun `unknown keys from a newer agent do not break an older server`() {
        val decoded =
            TracyJson.decodeFromString<BatchLine>(
                """{"t":1,"n":2,"l":"INFO","g":"L","m":"hi","brandNewField":{"a":1}}""",
            )

        assertIs<LogRecord>(decoded)
        assertEquals("hi", decoded.message)
    }

    @Test
    fun `an unterminated span is data and survives the wire`() {
        val span = Span("tr", "sp", name = "POST /orders", kind = SpanKind.SERVER, ts = 1)
        val decoded = TracyJson.decodeFromString<BatchLine>(TracyJson.encodeToString<BatchLine>(span))

        assertIs<Span>(decoded)
        assertNull(decoded.durationMs)
        assertTrue(decoded.isUnterminated)
    }

    @Test
    fun `level threshold comparison follows severity order`() {
        assertTrue(Level.ERROR.atLeast(Level.INFO))
        assertTrue(Level.INFO.atLeast(Level.INFO))
        assertTrue(!Level.DEBUG.atLeast(Level.INFO))
    }
}
