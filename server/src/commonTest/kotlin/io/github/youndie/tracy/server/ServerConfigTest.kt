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
        // Zero connections is not a smaller pool, it is a server that cannot answer. An empty
        // value is the same as an unset one: that is how a chart writes "I am not setting this".
        listOf("0", "-4", "").forEach { value ->
            val config =
                ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_DB_MAX_CONNECTIONS" to value))

            assertEquals(ServerConfig.DEFAULT_DB_MAX_CONNECTIONS, config.dbMaxConnections, "for '$value'")
        }
    }

    @Test
    fun `a pool size that is not a number at all stops the start`() {
        // `many` used to land in the same bucket as `0`, and the two say opposite things: one is a
        // number the operator chose and this server refuses, the other is a value it could not read
        // — and reading it as the default hides a typo that is sitting in the pod spec in plain
        // sight.
        assertFailsWith<IllegalArgumentException> {
            ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_DB_MAX_CONNECTIONS" to "many"))
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
    fun `a non numeric port stops the start instead of falling back`() {
        // This test used to assert the opposite, and that is how the behaviour survived: a silent
        // fallback with a test around it reads as a decision rather than an oversight.
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_HTTP_PORT" to "not-a-port"))
            }

        assertTrue("TRACY_HTTP_PORT" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `a number that will not parse stops the start instead of becoming the default`() {
        val failure =
            assertFailsWith<IllegalArgumentException> {
                ServerConfig.fromEnv(
                    // Exactly what the pod carried on the stand: Helm renders a plain YAML number
                    // as a float, and this is the string the server was handed for six weeks.
                    env("TRACY_INGEST_KEY" to "k", "TRACY_DB_MAX_BYTES" to "6.442450944e+09"),
                )
            }

        // The message has to name the variable: the operator is looking at a pod spec that says
        // 6442450944 and a server that behaves as if nobody had set anything.
        assertTrue("TRACY_DB_MAX_BYTES" in failure.message.orEmpty(), failure.message.orEmpty())
    }

    @Test
    fun `an unset number still takes the default`() {
        val config = ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k"))

        // The distinction the old code could not make: absent is not the same as unreadable.
        assertEquals(4L * 1024 * 1024 * 1024, config.maxDbBytes)
        assertEquals(30, config.retentionDays)
    }

    @Test
    fun `a whole number is taken as written`() {
        val config =
            ServerConfig.fromEnv(env("TRACY_INGEST_KEY" to "k", "TRACY_DB_MAX_BYTES" to " 6442450944 "))

        // Trimmed, because a value that travelled through YAML can arrive with whitespace, and
        // failing on that would be the same trap with better manners.
        assertEquals(6_442_450_944L, config.maxDbBytes)
    }
}
