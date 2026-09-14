package io.github.youndie.tracy.server

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
    fun `the pool is two connections wide and reaps nothing unless told otherwise`() {
        val config = ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k"))

        assertEquals(ServerConfig.DEFAULT_DB_MAX_CONNECTIONS, config.dbMaxConnections)
        assertNull(config.dbIdleTimeoutSeconds)
    }

    @Test
    fun `a pool size that is not a positive number falls back to the default`() {
        // Zero connections is not a smaller pool, it is a server that cannot answer; a typo must
        // not be able to express it.
        listOf("0", "-4", "many", "").forEach { value ->
            val config =
                ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_DB_MAX_CONNECTIONS" to value))

            assertEquals(ServerConfig.DEFAULT_DB_MAX_CONNECTIONS, config.dbMaxConnections, "for '$value'")
        }
    }

    @Test
    fun `the pool knobs are read from the environment`() {
        val config =
            ServerConfig.fromEnv(
                env(
                    "TRACY_INGEST_KEY" to "k",
                    "TRACY_DB_MAX_CONNECTIONS" to "4",
                    "TRACY_DB_IDLE_TIMEOUT_SECONDS" to "60",
                ),
            )

        assertEquals(4, config.dbMaxConnections)
        assertEquals(60, config.dbIdleTimeoutSeconds)
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
