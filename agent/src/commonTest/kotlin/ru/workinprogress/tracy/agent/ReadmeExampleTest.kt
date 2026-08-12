package ru.workinprogress.tracy.agent

import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The README's quick start, compiled.
 *
 * A snippet that merely looks right is worse than none: it is the first thing a reader tries, and
 * it fails in their editor rather than in ours. This file exists so that changing the agent's
 * public API breaks the build here, next to the docs it invalidates.
 */
class ReadmeExampleTest {
    private fun Application.quickStart(
        ingestKey: String,
        podName: String,
    ): TracyAgent {
        val config =
            AgentConfig(
                service = "orders-api",
                apiKey = ingestKey,
                endpoint = "https://tracy.example",
                instanceId = podName,
            )
        val tracy =
            TracyAgent(config, clock = {
                kotlin.time.Clock.System
                    .now()
                    .toEpochMilliseconds()
            })

        TracyDelivery(tracy, config).start(this)

        install(Tracy) { agent = tracy }

        // The client plugin belongs to the HttpClient making outgoing calls, not to the
        // Application. Putting both in one block is the mistake this file caught in the README.
        io.ktor.client.HttpClient {
            install(TracyClient) { agent = tracy }
        }

        val log = tracy.logger("OrdersRouting")
        routing {
            post("/orders") {
                log.info("order created") { field("orderId", "12345", indexed = true) }
            }
        }
        return tracy
    }

    @Test
    fun `the quick start compiles and wires an agent`() {
        // Not run against a socket — that is covered elsewhere. What is asserted here is that the
        // README's code is code.
        assertEquals("orders-api", AgentConfig("orders-api", "k", "https://x", "i").service)
    }
}
