package ru.workinprogress.tracy.server.query

/**
 * Looking up one business entity, and the refusal that is not an empty answer.
 *
 * The policy is one sentence — *an unindexed key is refused rather than answered empty, because
 * empty reads as "that never happened" when the truth is "nobody ever indexed this key"* — and it
 * was written three times: once in the MCP facade and twice in the HTTP routes. Three copies of a
 * rule that only matters when it is applied consistently.
 *
 * The result is a type rather than an exception on the way out, so each transport maps it to its
 * own shape: `400` with a body over HTTP, an `McpRefusal` over MCP. That is the whole reason the
 * layer exists — the decision is one, the presentation is two.
 */
public class EntityTimelineUseCase(
    private val entities: EntityRepository,
) {
    public suspend fun timeline(
        key: String,
        value: String,
        since: Long,
        until: Long,
        limit: Int = 200,
    ): EntityLookup<EntityTimeline> =
        try {
            EntityLookup.Found(entities.timeline(key, value, since, until, limit))
        } catch (unknown: UnknownEntityKey) {
            EntityLookup.KeyNotIndexed(unknown.key, unknown.indexed)
        }

    public suspend fun top(
        key: String,
        since: Long,
        until: Long,
        limit: Int = 20,
    ): EntityLookup<EntityTopResult> =
        try {
            EntityLookup.Found(entities.top(key, since, until, limit))
        } catch (unknown: UnknownEntityKey) {
            EntityLookup.KeyNotIndexed(unknown.key, unknown.indexed)
        }
}

/** Either the answer, or the reason there cannot be one. */
public sealed interface EntityLookup<out T> {
    public data class Found<T>(
        val value: T,
    ) : EntityLookup<T>

    /**
     * [indexed] is empty when no service has ever marked a field — a different situation from a
     * misspelled key, and one an agent draws the opposite conclusion from. The transports say
     * which of the two it is; this type only has to keep them apart.
     */
    public data class KeyNotIndexed(
        val key: String,
        val indexed: List<String>,
    ) : EntityLookup<Nothing>
}
