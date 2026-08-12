package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The acceptance gate of M1: a record that went through redaction must not contain the original
 * value **anywhere** in its serialised form — not in a field, not in the message, not in the
 * exception, not in the template that will be derived from it.
 *
 * Written as a separate test on purpose. The unit tests check each rule; this one checks the
 * promise, and the promise is what the rest of the system relies on.
 */
class RedactedRecordOnWireTest {
    private val redactor = Redactor()

    private val secrets =
        listOf(
            "1234567890:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E",
            "hunter2",
            "s3cr3tvalue12345",
        )

    @Test
    fun `no secret survives into the serialised record`() {
        val raw =
            LogRecord(
                ts = 1754049600123,
                seq = 1,
                level = Level.ERROR,
                logger = "TelegramClient",
                message =
                    "Saving body for https://api.telegram.org/bot${secrets[0]}/getUpdates?token=${secrets[2]}",
                fields =
                    mapOf(
                        "url" to JsonPrimitive("postgres://admin:${secrets[1]}@db:5432/orders"),
                        "authorization" to JsonPrimitive("Bearer ${secrets[2]}"),
                        "orderId" to JsonPrimitive("12345"),
                    ),
                traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
            )

        val message = redactor.redactMessage(raw.message)
        val fields = redactor.redactFields(raw.fields)
        val redacted =
            raw.copy(
                message = message.text,
                fields = fields.fields,
                redacted =
                    buildList {
                        if (message.changed) add(LogRecord.REDACTED_MESSAGE)
                        addAll(fields.names)
                    },
            )

        val wire = NdJson.encodeLine(redacted)

        for (secret in secrets) {
            assertTrue(secret !in wire, "a secret reached the wire")
        }
    }

    @Test
    fun `what was redacted stays visible as a fact`() {
        // A masked value must be distinguishable from an absent one, otherwise reading the log
        // gives "the field was empty" where the truth is "the field was hidden".
        val fields = mapOf("authorization" to JsonPrimitive("Bearer abcdef0123456789"))
        val result = redactor.redactFields(fields)

        assertTrue("authorization" in result.names)
        assertTrue(result.fields?.get("authorization")?.content == REDACTED)
    }

    @Test
    fun `a template derived after redaction carries no secret`() {
        // Ordering invariant: redaction runs before normalisation, so nothing unredacted can
        // reach log_template — the table that outlives bodies and is handed to agents.
        val message = "GET https://api.telegram.org/bot${secrets[0]}/getUpdates"

        val template =
            redactor
                .redactMessage(message)
                .text
                .replace(Regex("""\d+"""), "<num>")
                .replace(Regex("""[0-9a-fA-F]{8,}"""), "<hex>")

        for (secret in secrets) {
            assertTrue(secret !in template)
        }
    }

    @Test
    fun `an ordinary record is byte for byte unchanged by redaction`() {
        val raw =
            LogRecord(
                ts = 1,
                seq = 1,
                level = Level.INFO,
                logger = "OrdersRouting",
                message = "order created",
                fields = mapOf("orderId" to JsonPrimitive("12345"), "total" to JsonPrimitive(500)),
            )

        val fields = redactor.redactFields(raw.fields)
        val message = redactor.redactMessage(raw.message)

        assertTrue(!message.changed)
        assertTrue(fields.names.isEmpty())
        assertTrue(NdJson.encodeLine(raw) == NdJson.encodeLine(raw.copy(fields = fields.fields)))
    }
}
