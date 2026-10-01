package io.github.youndie.tracy.server.mcp

import io.github.youndie.kore.lifecycle.DrainGate
import io.github.youndie.tracy.server.ServerConfig
import io.github.youndie.tracy.server.TracyProbes
import io.github.youndie.tracy.server.module
import io.github.youndie.tracy.server.openDatabase
import io.ktor.network.selector.SelectorManager
import io.ktor.network.sockets.aSocket
import io.ktor.network.sockets.openReadChannel
import io.ktor.network.sockets.openWriteChannel
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.utils.io.readBuffer
import io.ktor.utils.io.readLine
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.io.readString
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The token is required on every request line that reaches the MCP transport, not only on `/mcp` as
 * a string.
 *
 * Ktor's router skips empty path segments and decodes each one, so `//mcp` and `/%6Dcp` are served
 * by the same handler as `/mcp` while `request.path()` says they are somewhere else. A guard that
 * decides by comparing that string lets them through without a token. The guard has to sit on the
 * transport's own route, where the router that picks the handler is also the one that decides
 * whether the guard applies.
 *
 * Through the application's real `module`, not a bare transport: what this file holds is that
 * tracy's wiring has that property, whatever library it takes the endpoint from. Over a raw socket,
 * because the request line is the thing under test and an HTTP client is free to tidy `//mcp`
 * before it leaves.
 */
class McpPathTest {
    private val token = "tr_mcp_secret"

    /** `/mcp` itself, and request lines that are not `/mcp` as a string but are `/mcp` to the router. */
    private val spellings = listOf("/mcp", "//mcp", "///mcp", "/%6Dcp", "/%6dcp")

    @Test
    fun `every spelling the router sends to the transport needs the token`() =
        withTracy { port ->
            val answers = spellings.associateWith { line -> post(port, line, token = null) }

            // Every line asked before any is judged, so a failure names all the open ones at once.
            val open = answers.filterValues { it.status != 401 || "unauthorized" !in it.body }
            assertTrue(
                open.isEmpty(),
                "answered without a token: " +
                    open.entries.joinToString { (line, a) -> "$line -> ${a.status} ${a.body}" },
            )
        }

    @Test
    fun `the same spellings reach the transport with the token`() =
        withTracy { port ->
            // The control for the test above: without it, a 401 there could mean "this line never
            // reached the transport at all" rather than "the guard recognised it".
            for (line in spellings) {
                val answer = post(port, line, token = token)

                assertEquals(200, answer.status, "$line with the token: ${answer.body}")
                assertTrue("protocolVersion" in answer.body, "$line: ${answer.body}")
            }
        }

    private fun withTracy(block: suspend (port: Int) -> Unit) =
        runBlocking {
            val db = openDatabase("/tmp/tracy-mcp-path-${Random.nextLong()}.db")
            val config =
                ServerConfig(
                    httpPort = 0,
                    dbPath = "unused",
                    ingestKey = "k",
                    mcpToken = token,
                    // The WAL sweep is a loop of its own and has nothing to do with this question.
                    walCheckpointSeconds = 0,
                )
            val server =
                embeddedServer(CIO, port = 0, host = "127.0.0.1") {
                    module(config, db, TracyProbes(db), DrainGate())
                }
            server.start(wait = false)
            try {
                block(
                    server.engine
                        .resolvedConnectors()
                        .first()
                        .port,
                )
            } finally {
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
                db.close()
            }
        }

    private class Answer(
        val status: Int,
        val body: String,
    )

    /** One `initialize` on its own connection, written byte for byte and read to the end. */
    private suspend fun post(
        port: Int,
        target: String,
        token: String?,
    ): Answer =
        SelectorManager(Dispatchers.Default).use { selector ->
            aSocket(selector).tcp().connect("127.0.0.1", port).use { socket ->
                val read = socket.openReadChannel()
                val write = socket.openWriteChannel(autoFlush = true)
                val body =
                    """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18",""" +
                        """"capabilities":{},"clientInfo":{"name":"test","version":"1"}}}"""
                val auth = token?.let { "Authorization: Bearer $it\r\n" }.orEmpty()
                write.writeStringUtf8(
                    "POST $target HTTP/1.1\r\nHost: localhost\r\n$auth" +
                        "Accept: application/json, text/event-stream\r\nContent-Type: application/json\r\n" +
                        "Content-Length: ${body.encodeToByteArray().size}\r\nConnection: close\r\n\r\n$body",
                )
                val statusLine = checkNotNull(read.readLine()) { "$target: the connection closed before a status line" }
                while (true) {
                    val header = checkNotNull(read.readLine()) { "$target: the connection closed inside the headers" }
                    if (header.isEmpty()) break
                }
                Answer(statusLine.split(' ')[1].toInt(), read.readBuffer().readString())
            }
        }
}
