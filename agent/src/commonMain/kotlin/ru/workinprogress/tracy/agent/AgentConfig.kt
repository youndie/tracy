package ru.workinprogress.tracy.agent

import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.Redactor
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Configuration of the agent living inside somebody else's process.
 *
 * Defaults are chosen from measurements, not taste — see docs/services/tracy-agent.md and
 * research 1.10. In particular [level] is the strongest volume lever there is: real logs are
 * 97% framework noise and 82% TRACE, so an INFO floor removes ~90% of bytes before trace
 * sampling even applies.
 */
public class AgentConfig(
    public val service: String,
    public val apiKey: String,
    public val endpoint: String,
    public val instanceId: String,
    public val release: String? = null,
    public val level: Level = Level.INFO,
    public val sampleRate: Double = 0.01,
    public val slowThreshold: Duration = 1.seconds,
    public val flushInterval: Duration = 1.seconds,
    public val maxBufferBytes: Int = 8 * 1024 * 1024,
    public val maxBatchBytes: Int = 1024 * 1024,
    public val spans: Boolean = true,
    public val redactor: Redactor = Redactor(),
) {
    init {
        // Deliberate: an agent silently disabled by a typo in a variable is indistinguishable
        // from a healthy one, and metrik lost months to exactly that class of failure.
        require(service.isNotBlank()) { "tracy: service is required" }
        require(apiKey.isNotBlank()) { "tracy: apiKey is required" }
        require(endpoint.isNotBlank()) { "tracy: endpoint is required" }
        require(instanceId.isNotBlank()) { "tracy: instanceId is required" }
        require(sampleRate in 0.0..1.0) { "tracy: sampleRate must be within 0..1" }
        require(maxBatchBytes in 1..maxBufferBytes) {
            "tracy: maxBatchBytes must be positive and not exceed maxBufferBytes"
        }
    }
}
