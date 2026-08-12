package ru.workinprogress.tracy.server.mcp

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import ru.workinprogress.tracy.server.ServerConfig
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.server.query.EntityRepository
import ru.workinprogress.tracy.server.query.QueryRepository
import ru.workinprogress.tracy.server.trace.SpanSearchRepository
import ru.workinprogress.tracy.server.trace.TraceRepository
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The transport over a real socket.
 *
 * katcher's history is the argument for this file: sixty-four green tests and a working local
 * binary meant nothing for the deployed endpoint, and the two failures that mattered only appeared
 * once something was actually listening (research 1.8).
 */
class McpTransportTest {
    private val day = 1785542400000L

    private val toolNames =
        listOf("list_services", "search_logs", "get_trace", "get_entity", "search_spans", "top_templates", "get_entry_content")

    private suspend fun withServer(
        token: String? = "tr_mcp_secret",
        allowedHosts: List<String> = emptyList(),
        block: suspend (client: HttpClient, port: Int) -> Unit,
    ) {
        val db = openDatabase("/tmp/tracy-mcp-tr-${Random.nextLong()}.db")
        val config =
            ServerConfig(
                httpPort = 0,
                dbPath = "unused",
                ingestKey = "k",
                mcpToken = token,
                mcpAllowedHosts = allowedHosts,
            )
        val facade =
            ToolFacade(QueryRepository(db), TraceRepository(db), SpanSearchRepository(db), EntityRepository(db))

        IngestRepository(db, clock = { day }).write(
            BatchHeader("orders-api", "pod-a", "1.0", 1),
            listOf(
                LogRecord(
                    ts = day + 1,
                    seq = 1,
                    level = Level.WARN,
                    logger = "OrdersRouting",
                    message = "payment declined",
                    fields = mapOf("orderId" to JsonPrimitive("12345")),
                    indexed = listOf("orderId"),
                ),
            ),
        )

        val server = embeddedServer(CIO, port = 0) { installMcp(config, facade) }
        server.start(wait = false)
        val client = HttpClient()
        try {
            block(
                client,
                server.engine
                    .resolvedConnectors()
                    .first()
                    .port,
            )
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 300)
        }
    }

    private suspend fun HttpClient.initialize(
        port: Int,
        token: String?,
        version: String,
    ) = post("http://127.0.0.1:$port/mcp") {
        token?.let { header("Authorization", "Bearer $it") }
        header("Content-Type", "application/json")
        header("Accept", "application/json, text/event-stream")
        setBody(
            """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"$version",""" +
                """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}""",
        )
    }

    @Test
    fun `nothing is installed without a token`() =
        runTest {
            withServer(token = null) { client, port ->
                assertEquals(404, client.post("http://127.0.0.1:$port/mcp").status.value)
            }
        }

    @Test
    fun `an unauthorised call gets a code and not a login page`() =
        runTest {
            withServer { client, port ->
                val response = client.initialize(port, token = null, version = "2025-06-18")

                assertEquals(401, response.status.value)
                assertTrue("unauthorized" in response.bodyAsText())
            }
        }

    @Test
    fun `a wrong token is rejected`() =
        runTest {
            withServer { client, port ->
                assertEquals(401, client.initialize(port, "wrong", "2025-06-18").status.value)
            }
        }

    @Test
    fun `initialize answers with a protocol version`() =
        runTest {
            withServer { client, port ->
                val body = client.initialize(port, "tr_mcp_secret", "2025-06-18").bodyAsText()

                // This is the field the SDK drops when it equals the default, which breaks every
                // current client (research 1.8). Here it must be present.
                assertTrue("protocolVersion" in body, body)
            }
        }

    @Test
    fun `the negotiated version is echoed back for both versions`() =
        runTest {
            withServer { client, port ->
                for (version in listOf("2025-06-18", "2025-11-25")) {
                    val body = client.initialize(port, "tr_mcp_secret", version).bodyAsText()

                    // Measured, not assumed. katcher lost a day to a missing `protocolVersion`,
                    // and tracy carried a rewriting shim for it until this test was written: on
                    // 0.15.0 over `mcpStatelessStreamableHttp` the field comes back for every
                    // version, including the SDK default that was blamed. The shim is gone; this
                    // test is what stays, so a regression is a failure and not another day.
                    assertTrue("\"protocolVersion\":\"$version\"" in body, body)
                }
            }
        }

    private suspend fun HttpClient.rpc(
        port: Int,
        body: String,
    ) = post("http://127.0.0.1:$port/mcp") {
        header("Authorization", "Bearer tr_mcp_secret")
        header("Content-Type", "application/json")
        header("Accept", "application/json, text/event-stream")
        setBody(body)
    }.bodyAsText()

    @Test
    fun `all seven tools are listed with schemas`() =
        runTest {
            withServer { client, port ->
                val body = client.rpc(port, """{"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}""")

                for (name in toolNames) {
                    assertTrue("\"$name\"" in body, "$name missing from tools/list: $body")
                }
                // Descriptions carry the contract an agent cannot infer from a name: that
                // search_logs returns a sample, that get_trace does not follow an entity.
                assertTrue("SAMPLE" in body, "search_logs must announce that it samples")
                assertTrue("different trace" in body, "get_trace must say what it cannot answer")
            }
        }

    @Test
    fun `a tool call actually reaches the facade`() =
        runTest {
            withServer { client, port ->
                val body =
                    client.rpc(
                        port,
                        """{"jsonrpc":"2.0","id":3,"method":"tools/call","params":""" +
                            """{"name":"list_services","arguments":{}}}""",
                    )

                // The whole path in one assertion: socket, SDK dispatch, facade, SQLite.
                assertTrue("orders-api" in body, body)
                assertTrue("isError" !in body, body)
            }
        }

    @Test
    fun `a required window is refused rather than answered over all of time`() =
        runTest {
            withServer { client, port ->
                val body =
                    client.rpc(
                        port,
                        """{"jsonrpc":"2.0","id":4,"method":"tools/call","params":""" +
                            """{"name":"search_logs","arguments":{}}}""",
                    )

                // An unbounded read scans every partition kept. Refusing is the cheap answer,
                // and it must arrive as a tool error the agent can read, not a transport failure.
                assertTrue("\"isError\":true" in body, body)
                assertTrue("`since` is required" in body, body)
            }
        }

    @Test
    fun `a windowed search returns structure and not values`() =
        runTest {
            withServer { client, port ->
                val body =
                    client.rpc(
                        port,
                        """{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"search_logs",""" +
                            """"arguments":{"since":0,"until":99999999999999}}}""",
                    )

                assertTrue("payment declined" in body, body)
                assertTrue("orderId" in body, body)
                // Phase one hands over the field key. The value belongs to phase two, behind the gate.
                assertTrue("12345" !in body, "a field value escaped phase one: $body")
            }
        }

    @Test
    fun `an unexpected host is refused`() =
        runTest {
            withServer(allowedHosts = listOf("tracy.example")) { client, port ->
                val response = client.initialize(port, "tr_mcp_secret", "2025-06-18")

                assertTrue(response.status.value >= 400, "expected a refusal, got ${response.status}")
            }
        }
}
