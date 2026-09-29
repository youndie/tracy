package io.github.youndie.tracy.server

import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import org.koin.dsl.module
import org.koin.ktor.ext.inject
import org.koin.ktor.plugin.KOIN_SCOPE_ATTRIBUTE_KEY
import org.koin.ktor.plugin.Koin
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * What [installKoinWithoutCallScope] promises: routes still resolve from the container, and a call
 * gets no Koin scope — the per-call scope is what leaked a native mutex on every request.
 *
 * The second test is the control. It runs koin-ktor's own plugin through the same route and expects
 * the scope to be there, so a green first test cannot mean "this route could not see a scope
 * anyway".
 */
class KoinWiringTest {
    private val probe = module { single { "wired" } }

    private fun Application.probeRoute() {
        routing {
            val value by inject<String>()
            get("/probe") {
                val scoped = call.attributes.contains(KOIN_SCOPE_ATTRIBUTE_KEY)
                call.respondText("value=$value scope=$scoped")
            }
        }
    }

    @Test
    fun `routes resolve from the container and a call opens no scope`() =
        testApplication {
            application {
                installKoinWithoutCallScope(probe)
                probeRoute()
            }
            repeat(3) {
                assertEquals("value=wired scope=false", client.get("/probe").bodyAsText())
            }
        }

    @Test
    fun `control - the koin-ktor plugin does open a scope per call`() =
        testApplication {
            application {
                install(Koin) { modules(probe) }
                probeRoute()
            }
            assertEquals("value=wired scope=true", client.get("/probe").bodyAsText())
        }
}
