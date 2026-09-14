package io.github.youndie.tracy.agent

import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationStopping
import kotlinx.coroutines.runBlocking

/**
 * Starts the delivery loop in the application's scope and stops it when the application stops.
 *
 * The one-line wiring for a service that has no shutdown sequence of its own, and the answer to
 * issue #32: `TracyDelivery(agent, config).start(this)` was the documented form, it gave nobody a
 * reason to keep the reference, and [TracyDelivery.stop] — written for exactly this moment — had no
 * caller anywhere. Every shutdown lost up to one flush interval of records, which are the records
 * explaining the shutdown.
 *
 * **On the JVM this is the whole fix. On Kotlin/Native it is most of it, and the remainder is worth
 * knowing.** `ApplicationStopping` fires at different points on the two platforms:
 * `EmbeddedServer.stop` runs its steps in the opposite order, so on Kotlin/Native this event arrives
 * **before** the engine has drained. The flush therefore happens early, and records produced while
 * in-flight requests finish are still lost. A service that wants those too calls
 * [TracyDelivery.stop] from its own ordered shutdown — kore's telemetry group is exactly that place,
 * and doing both is safe: stopping twice costs one wasted flush attempt and nothing else.
 *
 * `runBlocking` in the handler is not decoration either: the event is not a suspending callback, and
 * a `launch` here would return immediately and let the process exit out from under the flush — which
 * is the failure this function exists to remove, reintroduced one line lower.
 *
 * ```kotlin
 * val tracy = TracyAgent(config, clock = { Clock.System.now().toEpochMilliseconds() })
 * val delivery = startTracyDelivery(tracy, config)   // keep it if you have a shutdown of your own
 * install(Tracy) { agent = tracy }
 * ```
 */
public fun Application.startTracyDelivery(
    agent: TracyAgent,
    config: AgentConfig,
    stopOnApplicationStopping: Boolean = true,
): TracyDelivery {
    val delivery = TracyDelivery(agent, config).start(this)

    if (stopOnApplicationStopping) {
        monitor.subscribe(ApplicationStopping) {
            runBlocking { delivery.stop() }
        }
    }

    return delivery
}
