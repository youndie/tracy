package ru.workinprogress.tracy.server

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun env(vararg pairs: Pair<String, String>): (String) -> String? = mapOf(*pairs)::get

class ServerConfigTest {
    @Test
    fun `missing ingest key fails the start`() {
        assertFailsWith<IllegalArgumentException> { ServerConfig.fromEnv(env()) }
    }

    @Test
    fun `blank ingest key fails the start`() {
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "   "))
        }
    }

    @Test
    fun `defaults match the documented ones`() {
        val config = ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k"))

        assertEquals(8080, config.httpPort)
        assertEquals("/data/tracy.db", config.dbPath)
        assertNull(config.selfService)
    }

    @Test
    fun `mcp stays off when no token is configured`() {
        val config = ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k"))

        assertNull(config.mcpToken)
        assertTrue(config.mcpAllowedHosts.isEmpty())
    }

    @Test
    fun `blank mcp token counts as absent`() {
        val config =
            ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_MCP_TOKEN" to "  "))

        assertNull(config.mcpToken)
    }

    @Test
    fun `allowed hosts are split and trimmed`() {
        val config =
            ServerConfig.fromEnv(
                env(
                    "TRACY_INGEST_KEY" to "k",
                    "TRACY_MCP_ALLOWED_HOSTS" to " tracy.example , localhost ,, ",
                ),
            )

        assertEquals(listOf("tracy.example", "localhost"), config.mcpAllowedHosts)
    }

    @Test
    fun `non numeric port falls back to the default`() {
        val config =
            ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_HTTP_PORT" to "not-a-port"))

        assertEquals(8080, config.httpPort)
    }
}
