package io.github.youndie.tracy.sample

import kotlinx.coroutines.CoroutineScope

/** The same calls with nothing behind them: the baseline the agent's cost is measured against. */
@Suppress("UNUSED_PARAMETER")
class Telemetry(
    scope: CoroutineScope,
    instanceId: String,
) {
    val name: String = "without the agent"

    suspend fun <T> action(
        name: String,
        block: suspend () -> T,
    ): T = block()

    suspend fun degraded(originalType: String) = Unit
}
