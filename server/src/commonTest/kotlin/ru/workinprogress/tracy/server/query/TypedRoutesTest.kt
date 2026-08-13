package ru.workinprogress.tracy.server.query

import io.github.smyrgeorge.sqlx4k.sqlite.ISQLite
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.koin.ktor.plugin.Koin
import ru.workinprogress.tracy.server.ServerConfig
import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.ingest.IngestBatchUseCase
import ru.workinprogress.tracy.server.openDatabase
import ru.workinprogress.tracy.server.serverModule
import ru.workinprogress.tracy.server.trace.traceRoutes
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.LogRecord
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The typed routes, over HTTP.
 *
 * A `@Resource` declaration fails at runtime rather than at compile time — an unmatched route is
 * simply a 404, and a wrong one silently answers with a different handler. Nothing about that
 * shows up in a green build, which is the whole reason this file exists next to the resources it
 * checks.
 */
class TypedRoutesTest {
    private val day = 1785542400000L
    private val window = "since=0&until=99999999999999"

    private suspend fun withServer(block: suspend (client: HttpClient, port: Int) -> Unit) {
        val db: ISQLite = openDatabase("/tmp/tracy-typed-${Random.nextLong()}.db")
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
                    traceId = "4bf92f3577b34da6a3ce929d0e0e4736",
                    indexed = listOf("orderId"),
                ),
            ),
        )

        val config = ServerConfig(httpPort = 0, dbPath = "unused", ingestKey = "k")
        val server =
            embeddedServer(CIO, port = 0) {
                install(Koin) { modules(serverModule(config, db)) }
                install(Resources)
                routing {
                    queryRoutes()
                    traceRoutes()
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

    @Test
    fun `every typed route is reachable`() =
        runTest {
            withServer { client, port ->
                val paths =
                    listOf(
                        "/api/logs?$window",
                        "/api/templates?$window",
                        "/api/services",
                        "/api/entities/orderId/top?$window",
                        "/api/entities/orderId/12345?$window",
                        "/api/traces/4bf92f3577b34da6a3ce929d0e0e4736",
                        "/api/spans?$window",
                    )

                for (path in paths) {
                    val response = client.get("http://127.0.0.1:$port$path")
                    assertEquals(200, response.status.value, "$path answered ${response.status}")
                }
            }
        }

    @Test
    fun `top and a value of the same key reach different handlers`() =
        runTest {
            withServer { client, port ->
                // `{value}` also matches the literal `top`, and the two answer different shapes.
                // Ktor resolves the literal first; this test is what says so out loud.
                val top = client.get("http://127.0.0.1:$port/api/entities/orderId/top?$window").bodyAsText()
                val value = client.get("http://127.0.0.1:$port/api/entities/orderId/12345?$window").bodyAsText()

                assertTrue("values" in top || "top" in top, top)
                assertTrue("touches" in value, value)
            }
        }

    @Test
    fun `query parameters actually arrive`() =
        runTest {
            withServer { client, port ->
                // A resource whose fields never bind would still answer 200 with everything in it,
                // so the filter has to be shown to exclude something.
                val all = client.get("http://127.0.0.1:$port/api/logs?$window").bodyAsText()
                val other = client.get("http://127.0.0.1:$port/api/logs?$window&service=nobody").bodyAsText()

                assertTrue("payment declined" in all, all)
                assertTrue("payment declined" !in other, other)
            }
        }

    @Test
    fun `a missing window is a sentence rather than a 500`() =
        runTest {
            withServer { client, port ->
                val response = client.get("http://127.0.0.1:$port/api/logs")

                assertEquals(400, response.status.value)
                assertTrue("since is required" in response.bodyAsText())
            }
        }

    @Test
    fun `an unknown level is refused by name`() =
        runTest {
            withServer { client, port ->
                val response = client.get("http://127.0.0.1:$port/api/logs?$window&level=LOUD")

                assertEquals(400, response.status.value)
                assertTrue("LOUD" in response.bodyAsText())
            }
        }

    @Test
    fun `releasing an unknown key is a 404 rather than a silent success`() =
        runTest {
            withServer { client, port ->
                val response = client.post("http://127.0.0.1:$port/api/entities/nosuchkey/unsuppress")

                assertEquals(404, response.status.value)
            }
        }
}
