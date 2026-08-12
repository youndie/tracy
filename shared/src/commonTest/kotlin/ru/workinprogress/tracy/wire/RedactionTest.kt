package ru.workinprogress.tracy.wire

import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RedactionTest {
    private val redactor = Redactor()

    // Same shape as the token found in production logs (research 1.10), invented value.
    private val botToken = "1234567890:AAFqqqZrh-OXDDIZWEFmm5Rfi9WFcF9ui2E"

    @Test
    fun `secret inside a url path is redacted although it has no field name`() {
        val message = "Saving body for https://api.telegram.org/bot$botToken/getUpdates?timeout=30"

        val result = redactor.redactMessage(message)

        assertTrue(result.changed)
        assertTrue("AAFqqqZrh" !in result.text, "the secret survived redaction")
        // The line has to stay useful: host and endpoint are what make it worth reading.
        assertTrue("api.telegram.org" in result.text)
        assertTrue("getUpdates" in result.text)
    }

    @Test
    fun `redaction happens before the template can ever see the secret`() {
        // The regression that matters: normalisation preserved this token, and the template
        // table outlives bodies, is FTS-indexed and is handed to agents as trusted text.
        val message = "GET https://api.telegram.org/bot$botToken/getUpdates"

        val redacted = redactor.redactMessage(message).text
        val templated = redacted.replace(Regex("""\d+"""), "<num>")

        assertTrue("AAFqqqZrh" !in templated)
    }

    @Test
    fun `credentials in url userinfo are redacted`() {
        val result = redactor.redactMessage("connecting to postgres://admin:hunter2@db:5432/orders")

        assertTrue("hunter2" !in result.text)
        assertTrue("db:5432" in result.text, "the host is not a secret and is needed to debug")
    }

    @Test
    fun `bearer tokens are redacted wherever they appear`() {
        val result = redactor.redactMessage("Adding header Authorization: Bearer abcdef0123456789XYZ")

        assertTrue("abcdef0123456789XYZ" !in result.text)
        assertTrue("Bearer" in result.text)
    }

    @Test
    fun `jwt is recognised by shape`() {
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dozjgNryP4J3jVmNHl0w5N_XgL0n3I9PlFUP0THsR8U"

        assertTrue("dozjgNryP4J3" !in redactor.redactMessage("token=$jwt").text)
    }

    @Test
    fun `query parameters that carry keys are masked but stay visible`() {
        val result = redactor.redactMessage("GET /v1/items?api_key=s3cr3tvalue12345&page=2")

        assertTrue("s3cr3tvalue12345" !in result.text)
        assertTrue("api_key=" in result.text, "the parameter name is diagnostic, the value is not")
        assertTrue("page=2" in result.text)
    }

    @Test
    fun `ordinary messages are left untouched`() {
        val plain =
            listOf(
                "user logged in",
                "200 OK: GET - /api/user/me in 873ms",
                "order 8123 not found",
                "GET /health: Start handler",
                "Connection refused to db:5432",
            )

        for (message in plain) {
            val result = redactor.redactMessage(message)
            assertEquals(message, result.text, "false positive on: $message")
            assertTrue(!result.changed)
        }
    }

    @Test
    fun `sensitive field names are redacted by name`() {
        val fields =
            mapOf(
                "authorization" to JsonPrimitive("Bearer abc"),
                "apiKey" to JsonPrimitive("whatever"),
                "userId" to JsonPrimitive(42),
            )

        val result = redactor.redactFields(fields)

        assertEquals(REDACTED, result.fields?.get("authorization")?.content)
        assertEquals(REDACTED, result.fields?.get("apiKey")?.content)
        assertEquals("42", result.fields?.get("userId")?.content)
        assertTrue("authorization" in result.names && "apiKey" in result.names)
        assertTrue("userId" !in result.names)
    }

    @Test
    fun `a secret hiding in an innocent field value is still caught`() {
        val fields = mapOf("url" to JsonPrimitive("https://api.telegram.org/bot$botToken/x"))

        val result = redactor.redactFields(fields)

        assertTrue("AAFqqqZrh" !in (result.fields?.get("url")?.content ?: ""))
        assertTrue("url" in result.names)
    }

    @Test
    fun `field names are matched case insensitively`() {
        val result = redactor.redactFields(mapOf("Authorization" to JsonPrimitive("Bearer abc")))

        assertEquals(REDACTED, result.fields?.get("Authorization")?.content)
    }

    @Test
    fun `card numbers are redacted`() {
        assertTrue("4111 1111 1111 1111" !in redactor.redactMessage("card 4111 1111 1111 1111").text)
    }
}
