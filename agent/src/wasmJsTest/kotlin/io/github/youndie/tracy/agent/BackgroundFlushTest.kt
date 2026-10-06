package io.github.youndie.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.browser.window
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.w3c.dom.events.Event
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.seconds

/**
 * A page that is being left sends what it has, without waiting for the next tick (research-clients K5).
 *
 * The flush interval is an hour here, so a send within a few seconds can only be the page's event.
 */
class BackgroundFlushTest {
    @Test
    fun `pagehide sends the buffer at once`() =
        runTest {
            val sent = CompletableDeferred<String>()
            val config =
                AgentConfig(
                    service = "app",
                    apiKey = "k",
                    endpoint = "http://tracy.test",
                    instanceId = "tab",
                    flushInterval = 1.hours,
                )
            val engine =
                MockEngine { request ->
                    sent.complete(request.url.encodedPath)
                    respond("""{"accepted":1}""", HttpStatusCode.Accepted)
                }
            val agent = TracyAgent(config, clock = { 1754049600000L })
            val delivery = TracyDelivery(agent, config, sender = Sender(config, client = HttpClient(engine)))
            // Real time, not the test's virtual clock: the flush is woken by a browser event.
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            try {
                delivery.start(scope)
                val hook = delivery.flushWhenBackgrounded()
                agent.logger("Screen").warn("leaving")

                window.dispatchEvent(Event("pagehide"))

                val path = withContext(Dispatchers.Default) { withTimeout(5.seconds) { sent.await() } }
                assertTrue(path.endsWith("/ingest"), "the batch went to $path")
                hook.close()
            } finally {
                scope.cancel()
            }
        }
}
