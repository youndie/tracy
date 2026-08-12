package ru.workinprogress.tracy.wire

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of a `202` from `POST /ingest`.
 *
 * [suppressedKeys] is a control channel, not information: it is the only way the server's decision
 * to stop indexing an entity key reaches the agent (research D15). It rides on every accepted
 * response rather than only on change, so an agent that just restarted learns the current state
 * from its first reply.
 */
@Serializable
public data class IngestResponse(
    @SerialName("accepted") val accepted: Int = 0,
    @SerialName("suppressedKeys") val suppressedKeys: List<String> = emptyList(),
)
