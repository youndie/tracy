package ru.workinprogress.tracy.server.template

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NormalizerTest {
    @Test
    fun `numbers collapse so one event is one template`() {
        assertEquals("order <num> not found", Normalizer.normalize("order 8123 not found"))
        assertEquals(
            Normalizer.normalize("order 1 not found"),
            Normalizer.normalize("order 999999 not found"),
        )
    }

    @Test
    fun `identifiers and addresses collapse`() {
        assertEquals("user <uuid> logged in", Normalizer.normalize("user 4bf92f35-77b3-4da6-a3ce-929d0e0e4736 logged in"))
        assertEquals("mail to <email>", Normalizer.normalize("mail to person@example.com"))
        assertEquals("from <ip>", Normalizer.normalize("from 203.0.113.7"))
    }

    @Test
    fun `durations keep their unit`() {
        // "in <num>ms" and "in <num>" would be two templates for one event.
        assertEquals("<num> OK: GET - <path> in <num>ms", Normalizer.normalize("200 OK: GET - /api/user/me in 873ms"))
    }

    @Test
    fun `a constant message is left alone`() {
        for (message in listOf("order created", "payment provider rejected", "user logged in")) {
            assertEquals(message, Normalizer.normalize(message))
        }
    }

    @Test
    fun `quoted values collapse`() {
        assertEquals("""Adding Tool: <str>""", Normalizer.normalize("""Adding Tool: "list_apps""""))
    }

    @Test
    fun `a very long message is bounded`() {
        val long = "prefix " + "word ".repeat(500)

        assertTrue(Normalizer.normalize(long).length <= 300)
    }

    @Test
    fun `masking is grouping and not safety`() {
        // A token in a path is *not* removed here. Redaction runs earlier, in the agent, and this
        // test exists so nobody mistakes the normaliser for a defence (research 1.10).
        val masked = Normalizer.normalize("GET https://api.example/botAAFqqqZrhOXDDIZWEFmm5Rfi/getUpdates")

        assertTrue("AAFqqqZrh" in masked || "<path>" in masked)
    }
}
