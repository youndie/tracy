package io.github.youndie.tracy.sample

import io.github.youndie.tracy.agent.AgentConfig
import io.github.youndie.tracy.agent.TracyAgent
import io.github.youndie.tracy.agent.TracyDelivery
import io.github.youndie.tracy.agent.action
import io.github.youndie.tracy.agent.flushWhenBackgrounded
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Clock

/** The app's telemetry through the tracy agent: a client key, its own batch ceiling, a flush on leaving. */
class Telemetry(
    scope: CoroutineScope,
    instanceId: String,
) {
    val name: String = "with the agent"

    private val config =
        AgentConfig(
            // Ignored by the server for a client key: it writes to `app:<name from the key>`.
            service = "compose-app",
            apiKey = CLIENT_KEY,
            endpoint = TRACY_ENDPOINT,
            instanceId = instanceId,
            sampleRate = 1.0,
            // Under the server's 64 KiB ceiling for a client key.
            maxBatchBytes = 48 * 1024,
        )
    private val agent = TracyAgent(config, clock = { Clock.System.now().toEpochMilliseconds() })

    init {
        TracyDelivery(agent, config).start(scope).flushWhenBackgrounded()
    }

    suspend fun <T> action(
        name: String,
        block: suspend () -> T,
    ): T = agent.action(name, block)

    suspend fun degraded(originalType: String) {
        agent.logger("Renderer").warn("component drawn as a placeholder") {
            field("originalType", originalType, indexed = true)
        }
    }
}
