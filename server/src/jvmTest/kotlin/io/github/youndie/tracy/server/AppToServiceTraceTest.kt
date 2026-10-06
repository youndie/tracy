package io.github.youndie.tracy.server

import io.github.youndie.kore.koin.installKoreKoin
import io.github.youndie.tracy.agent.AgentConfig
import io.github.youndie.tracy.agent.Tracy
import io.github.youndie.tracy.agent.TracyAgent
import io.github.youndie.tracy.agent.TracyClient
import io.github.youndie.tracy.agent.TracyDelivery
import io.github.youndie.tracy.agent.action
import io.github.youndie.tracy.server.ingest.ingestRoutes
import io.github.youndie.tracy.server.trace.SpanSearchRepository
import io.github.youndie.tracy.server.trace.TraceRepository
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.server.application.install
import io.ktor.server.cio.CIO
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.resources.Resources
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The whole of research-clients in one run (M-147): an app opens a trace around what a person did,
 * calls a service, and both write to tracy — the app with a client key, the service with the
 * installation key. `get_trace` has to return one tree: the app's action, its outgoing call, and the
 * service's request under it.
 *
 * Everything is real except the screen: a tracy server on a socket, a Ktor service with the server
 * plugin, an HTTP client with the client plugin. On the JVM because that is where all three run in
 * one process; the platform halves are covered by their own targets.
 */
class AppToServiceTraceTest {
    // Real time on purpose: the server files records into daily partitions by their timestamps and
    // the delivery stamps `X-Tracy-Sent`, and every party here runs on this one machine.
    @Suppress(
        "ktlint:kapkan:wall-clock",
        "one machine plays the app, the service and tracy; their clocks are the same clock",
    )
    private val clock: () -> Long = { System.currentTimeMillis() }

    @Test
    fun `an app's action and the service it called come back as one trace`() =
        // Bounded: three servers and two deliveries in one process, and a hang anywhere in them would
        // otherwise hold CI until the job's own limit with nothing in the log to say where.
        runBlocking {
            withTimeout(60.seconds) { oneTrace() }
        }

    private suspend fun oneTrace() =
        coroutineScope {
            val db = openDatabase("/tmp/tracy-e2e-${Random.nextLong()}.db")
            val config =
                ServerConfig(
                    httpPort = 0,
                    dbPath = "unused",
                    ingestKey = "installation-key",
                    clientKeys = mapOf("app-key" to "shop"),
                )
            val tracy =
                embeddedServer(CIO, port = 0) {
                    installKoreKoin { modules(serverModule(config, db)) }
                    install(Resources)
                    routing { ingestRoutes() }
                }.start(wait = false)
            val tracyUrl = "http://127.0.0.1:${tracy.port()}"

            // The service samples nothing on its own: it keeps the request because the app's head
            // decision travels in `traceparent`.
            val serviceConfig =
                AgentConfig(
                    service = "orders-api",
                    apiKey = "installation-key",
                    endpoint = tracyUrl,
                    instanceId = "pod-a",
                    sampleRate = 0.0,
                )
            val serviceAgent = TracyAgent(serviceConfig, clock = clock)
            val service =
                embeddedServer(CIO, port = 0) {
                    install(Tracy) { agent = serviceAgent }
                    routing {
                        get("/orders") {
                            serviceAgent.logger("Orders").info("order created")
                            call.respondText("ok")
                        }
                    }
                }.start(wait = false)

            val appConfig =
                AgentConfig(
                    service = "ignored-for-a-client-key",
                    apiKey = "app-key",
                    endpoint = tracyUrl,
                    instanceId = "phone-1",
                    sampleRate = 1.0,
                )
            val appAgent = TracyAgent(appConfig, clock = clock)
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                val appDelivery = TracyDelivery(appAgent, appConfig).start(scope)
                val serviceDelivery = TracyDelivery(serviceAgent, serviceConfig).start(scope)

                HttpClient { install(TracyClient) { agent = appAgent } }.use { http ->
                    appAgent.action("checkout.submit") { http.get("http://127.0.0.1:${service.port()}/orders") }
                }
                // `stop` makes the last attempt, which is what an app leaving and a pod ending do.
                appDelivery.stop()
                serviceDelivery.stop()

                val root =
                    SpanSearchRepository(db)
                        .search(service = "app:shop", since = 0, until = Long.MAX_VALUE)
                        .hits
                        .single { it.name == "checkout.submit" }
                val view = TraceRepository(db).load(root.traceId)

                val tree = view.roots.single()
                assertEquals("app:shop", tree.service, "the trace starts in the app")
                assertEquals("checkout.submit", tree.name)
                val call = tree.children.single()
                assertEquals("app:shop", call.service, "the app's outgoing call hangs off its action")
                val request = call.children.single()
                assertEquals("orders-api", request.service, "the service continues the app's trace")
                val lines = (request.logs + view.looseLogs).filter { it.service == "orders-api" }
                assertTrue(lines.any { it.message == "order created" }, "the service's record is in the trace: $lines")
            } finally {
                scope.cancel()
                service.stop(0, 300)
                tracy.stop(0, 300)
            }
        }

    private suspend fun EmbeddedServer<*, *>.port(): Int = engine.resolvedConnectors().first().port
}
