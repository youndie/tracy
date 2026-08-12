package ru.workinprogress.tracy.server.trace

import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceLogLine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TraceAssemblerTest {
    private fun span(
        id: String,
        parent: String? = null,
        service: String = "orders-api",
        kind: SpanKind = SpanKind.SERVER,
        ts: Long = 0,
        duration: Int? = 100,
        status: Int? = 200,
        error: Boolean = false,
    ) = StoredSpan(id, parent, service, "op $id", kind, ts, duration, status, error)

    private fun log(
        spanId: String,
        ts: Long = 1,
        message: String = "hello",
    ) = TraceLogLine(
        entryId = ts,
        ts = ts,
        service = "orders-api",
        level = Level.INFO,
        logger = "L",
        message = message,
    )

    @Test
    fun `a chain becomes a tree`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(
                    span("a", duration = 400),
                    span("client", parent = "a", kind = SpanKind.CLIENT, ts = 10, duration = 350),
                    span("b", parent = "client", service = "billing", ts = 12, duration = 348),
                ),
                emptyMap(),
            )

        val root = view.roots.single()
        assertEquals("a", root.spanId)
        assertEquals("client", root.children.single().spanId)
        assertEquals(
            "b",
            root.children
                .single()
                .children
                .single()
                .spanId,
        )
        assertEquals(
            "billing",
            root.children
                .single()
                .children
                .single()
                .service,
        )
    }

    @Test
    fun `unattributed time is what nobody instrumented`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(span("a", duration = 400), span("child", parent = "a", duration = 350)),
                emptyMap(),
            )

        // 400 minus 350: something happened in the parent that no span covers. Showing it beats
        // pretending the parent was busy only with what it could see (research D1).
        assertEquals(50, view.roots.single().unattributedMs)
    }

    @Test
    fun `a leaf has no unattributed time`() {
        val view = TraceAssembler.assemble("trace", listOf(span("a", duration = 400)), emptyMap())

        // Without children there is nothing to attribute time away from, and reporting the whole
        // duration as unattributed would be noise on every leaf in the tree.
        assertNull(view.roots.single().unattributedMs)
    }

    @Test
    fun `a client call with no answer is a lost link`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(
                    span("a", duration = 400),
                    span("client", parent = "a", kind = SpanKind.CLIENT, duration = 300),
                ),
                emptyMap(),
            )

        val client =
            view.roots
                .single()
                .children
                .single()
        // The call happened and the callee stored nothing. "Not involved" would be the wrong
        // conclusion, and it is the one an empty subtree invites.
        assertTrue(client.noRemoteData)
    }

    @Test
    fun `a client call that was answered is not flagged`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(
                    span("a"),
                    span("client", parent = "a", kind = SpanKind.CLIENT),
                    span("b", parent = "client", service = "billing"),
                ),
                emptyMap(),
            )

        assertTrue(
            !view.roots
                .single()
                .children
                .single()
                .noRemoteData,
        )
    }

    @Test
    fun `an orphan is hung off the root rather than dropped`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(span("a"), span("lonely", parent = "never-arrived", ts = 5)),
                emptyMap(),
            )

        assertEquals(2, view.roots.size)
        val orphan = view.roots.first { it.spanId == "lonely" }
        assertTrue(orphan.orphan, "an orphan is the evidence of a lost parent, not garbage")
    }

    @Test
    fun `an unclosed span is marked rather than hidden`() {
        val view = TraceAssembler.assemble("trace", listOf(span("a", duration = null)), emptyMap())

        assertTrue(view.roots.single().unterminated)
        assertNull(view.roots.single().durationMs)
    }

    @Test
    fun `logs land inside their span in time order`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(span("a")),
                mapOf("a" to listOf(log("a", ts = 9, message = "late"), log("a", ts = 2, message = "early"))),
            )

        assertEquals(
            listOf("early", "late"),
            view.roots
                .single()
                .logs
                .map { it.message },
        )
    }

    @Test
    fun `records without a span are kept at trace level`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(span("a")),
                emptyMap(),
                looseLogs = listOf(log("", ts = 3, message = "from non-suspend code")),
            )

        // These come from code where the trace cannot be recovered (research 1.3). They belong to
        // the trace but to no node; dropping them would lose data over a documented limit.
        assertEquals(1, view.looseLogs.size)
    }

    @Test
    fun `an empty trace is an empty tree rather than a failure`() {
        val view = TraceAssembler.assemble("trace", emptyList(), emptyMap())

        assertTrue(view.roots.isEmpty())
        assertTrue(view.looseLogs.isEmpty())
    }

    @Test
    fun `siblings keep their time order`() {
        val view =
            TraceAssembler.assemble(
                "trace",
                listOf(
                    span("a"),
                    span("second", parent = "a", ts = 20),
                    span("first", parent = "a", ts = 10),
                ),
                emptyMap(),
            )

        assertEquals(
            listOf("first", "second"),
            view.roots
                .single()
                .children
                .map { it.spanId },
        )
    }
}
