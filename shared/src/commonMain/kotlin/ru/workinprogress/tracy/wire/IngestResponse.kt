package ru.workinprogress.tracy.wire

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Body of a `202` from `POST /ingest`.
 *
 * [suppressedKeys] is a control channel, not information: it is the only way the server's decision
 * to stop indexing an entity key reaches the agent (research D15). It rides on every accepted
 * response rather than only on change, so an agent that just restarted learns the current state
 * from its first reply.
 *
 * [accepted] is written even when it is zero. kotlinx.serialization omits defaults, and a batch
 * whose every line was unparseable would otherwise answer `{}` — byte for byte what a caller sees
 * on success. A silent gate inside an observability tool is the failure it exists to prevent.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
public data class IngestResponse(
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("accepted")
    val accepted: Int = 0,
    /**
     * Lines the server could not parse. The count was computed and discarded before M7: the
     * decoder counted malformed lines so that an agent had "a reason to notice", and then the
     * reason never left the server.
     */
    @SerialName("malformed") val malformed: Int = 0,
    @SerialName("suppressedKeys") val suppressedKeys: List<String> = emptyList(),
)
