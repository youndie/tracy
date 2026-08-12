package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class EntityRefsTest {
    private fun record(
        seq: Long,
        traceId: String? = "4bf92f3577b34da6a3ce929d0e0e4736",
        fields: Fields = mapOf("orderId" to JsonPrimitive("12345")),
        indexed: List<String>? = listOf("orderId"),
    ) = LogRecord(
        ts = seq,
        seq = seq,
        level = Level.INFO,
        logger = "L",
        message = "order touched",
        fields = fields,
        traceId = traceId,
        indexed = indexed,
    )

    @Test
    fun `the same entity in one trace is marked once`() {
        val d = EntityRefDeduplicator()

        assertEquals(listOf("orderId"), d.apply(record(1)).indexed)
        assertNull(d.apply(record(2)).indexed)
        assertNull(d.apply(record(3)).indexed)
    }

    @Test
    fun `the same entity in another trace is marked again`() {
        val d = EntityRefDeduplicator()
        d.apply(record(1))

        val other = d.apply(record(2, traceId = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"))

        assertEquals(listOf("orderId"), other.indexed)
    }

    @Test
    fun `a different value of the same key is a different entity`() {
        val d = EntityRefDeduplicator()
        d.apply(record(1))

        val other = d.apply(record(2, fields = mapOf("orderId" to JsonPrimitive("999"))))

        assertEquals(listOf("orderId"), other.indexed)
    }

    @Test
    fun `keys are deduplicated independently`() {
        val d = EntityRefDeduplicator()
        val fields = mapOf("orderId" to JsonPrimitive("1"), "userId" to JsonPrimitive("7"))

        d.apply(record(1, fields = fields, indexed = listOf("orderId")))
        val second = d.apply(record(2, fields = fields, indexed = listOf("orderId", "userId")))

        assertEquals(listOf("userId"), second.indexed, "orderId was seen, userId was not")
    }

    @Test
    fun `records without a trace keep their marks`() {
        val d = EntityRefDeduplicator()

        // There is no trace to deduplicate within; dropping the mark would lose the reference,
        // and a reference is the only thing that survives sampling.
        assertEquals(listOf("orderId"), d.apply(record(1, traceId = null)).indexed)
        assertEquals(listOf("orderId"), d.apply(record(2, traceId = null)).indexed)
    }

    @Test
    fun `a mark without a matching field is dropped`() {
        val d = EntityRefDeduplicator()

        val out = d.apply(record(1, fields = mapOf("other" to JsonPrimitive("x"))))

        assertNull(out.indexed)
    }

    @Test
    fun `unmarked records pass through untouched`() {
        val d = EntityRefDeduplicator()
        val plain = record(1, indexed = null)

        assertEquals(plain, d.apply(plain))
    }

    @Test
    fun `tracking is bounded so the agent cannot grow without limit`() {
        val d = EntityRefDeduplicator(maxTracked = 4)

        repeat(10) { i ->
            d.apply(record(i.toLong(), fields = mapOf("orderId" to JsonPrimitive("v$i"))))
        }

        // The oldest entries were evicted, so the earliest value is marked again rather than
        // being remembered forever.
        assertEquals(
            listOf("orderId"),
            d.apply(record(99, fields = mapOf("orderId" to JsonPrimitive("v0")))).indexed,
        )
    }
}
