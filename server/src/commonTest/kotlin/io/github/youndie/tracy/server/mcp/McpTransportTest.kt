package io.github.youndie.tracy.server.mcp

import io.github.youndie.kore.mcp.KoreMcpConfig
import io.github.youndie.kore.mcp.installKoreMcp
import io.github.youndie.tracy.server.db.BatchHeader
import io.github.youndie.tracy.server.db.IngestRepository
import io.github.youndie.tracy.server.ingest.IngestBatchUseCase
import io.github.youndie.tracy.server.openDatabase
import io.github.youndie.tracy.server.query.EntityRepository
import io.github.youndie.tracy.server.query.QueryRepository
import io.github.youndie.tracy.server.trace.SpanSearchRepository
import io.github.youndie.tracy.server.trace.TraceRepository
import io.github.youndie.tracy.wire.Level
import io.github.youndie.tracy.wire.LogRecord
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The tools over a real socket.
 *
 * katcher's history is the argument for this file: sixty-four green tests and a working local
 * binary meant nothing for the deployed endpoint, and the two failures that mattered only appeared
 * once something was actually listening (research 1.8).
 *
 * What is left here is tracy's: the tools, their descriptions and the facade behind them. The
 * transport's own behaviour — nothing without a token, the bearer check, the `Host` check, the
 * `protocolVersion` in the initialize result — moved to kore with the endpoint (M-68) and is tested
 * there. What tracy's wiring has to keep is in `McpPathTest`.
 */
class McpTransportTest {
    private val day = 1785542400000L

    private val toolNames =
        listOf(
            "list_services",
            "search_logs",
            "get_trace",
            "get_entity",
            "search_spans",
            "top_templates",
            "get_entry_content",
        )

    private suspend fun withServer(block: suspend (client: HttpClient, port: Int) -> Unit) {
        val db = openDatabase("/tmp/tracy-mcp-tr-${Random.nextLong()}.db")
        val facade =
            ToolFacade(
                QueryRepository(db, clock = { day }),
                TraceRepository(db),
                SpanSearchRepository(db),
                EntityRepository(db),
            )

        IngestBatchUseCase(IngestRepository(db, clock = { day }), clock = { day })(
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

        val server =
            embeddedServer(CIO, port = 0) {
                installKoreMcp(KoreMcpConfig("tr_mcp_secret"), Implementation(name = "tracy", version = "0.1")) {
                    registerTools(facade)
                }
            }
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
}
