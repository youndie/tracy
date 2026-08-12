package ru.workinprogress.tracy.server.mcp

import kotlinx.serialization.Serializable
import ru.workinprogress.tracy.server.query.EntityRepository
import ru.workinprogress.tracy.server.query.LogHit
import ru.workinprogress.tracy.server.query.QueryRepository
import ru.workinprogress.tracy.server.query.UnknownEntityKey
import ru.workinprogress.tracy.server.trace.SpanSearchRepository
import ru.workinprogress.tracy.server.trace.TraceRepository
import ru.workinprogress.tracy.wire.Level

/**
 * What an agent is shown in **phase one**: structure and nothing that free-form text can hide in.
 *
 * Every string here is either an identifier, an enum, or a template the developer wrote. Values
 * arrive only through [ToolFacade.entryContent], after a report (research D8, katcher §5).
 */
@Serializable
public data class McpLogLine(
    public val entryId: Long,
    public val ts: Long,
    public val service: String,
    public val level: Level,
    public val logger: String,
    public val templateId: Long,
    public val message: String,
    public val traceId: String? = null,
    public val fieldKeys: List<String> = emptyList(),
    public val redacted: List<String> = emptyList(),
    /** True when the screen held the text back. The line stays visible; the text does not. */
    public val withheld: Boolean = false,
    public val withheldBy: List<String> = emptyList(),
)

@Serializable
public data class McpSearchResult(
    public val lines: List<McpLogLine>,
    public val truncated: Boolean = false,
    public val remaining: Int = 0,
    public val sampling: String,
)

@Serializable
public data class McpEntryContent(
    public val entryId: Long,
    public val fields: Map<String, String> = emptyMap(),
    public val withheld: List<String> = emptyList(),
    public val rules: List<String> = emptyList(),
)

@Serializable
public data class McpRefusal(
    public val error: String,
    public val reason: String,
)

/**
 * The tools, without the transport. Kept apart so the contract is testable without an MCP client
 * and without a socket.
 */
public class ToolFacade(
    private val logs: QueryRepository,
    private val traces: TraceRepository,
    private val spans: SpanSearchRepository,
    private val entities: EntityRepository,
    private val maxRemembered: Int = 5000,
) {
    /**
     * Entry ids this server has actually shown. The transport is stateless, so this is per server
     * rather than per connection — weaker than a session, and enough for what the gate needs: a
     * report about entries nobody was ever shown establishes nothing.
     */
    private val offered = LinkedHashSet<Long>()

    private val gate = EntryContentGate { offered }

    private fun remember(ids: List<Long>) {
        offered += ids
        while (offered.size > maxRemembered) {
            val iterator = offered.iterator()
            iterator.next()
            iterator.remove()
        }
    }

    public suspend fun listServices(): List<ru.workinprogress.tracy.server.query.ServiceSummary> = logs.listServices()

    public suspend fun searchLogs(
        service: String? = null,
        instance: String? = null,
        level: Level? = null,
        since: Long,
        until: Long,
        templateId: Long? = null,
        query: String? = null,
        exceptionClass: String? = null,
        entityKey: String? = null,
        entityValue: String? = null,
        limit: Int = 50,
    ): McpSearchResult {
        val result =
            logs.searchLogs(
                service = service,
                instance = instance,
                level = level,
                since = since,
                until = until,
                templateId = templateId,
                query = query,
                exceptionClass = exceptionClass,
                entityKey = entityKey,
                entityValue = entityValue,
                limit = limit,
            )
        remember(result.items.map { it.entryId })

        return McpSearchResult(
            lines = result.items.map { it.toMcp() },
            truncated = result.truncated,
            remaining = result.remaining,
            sampling = result.note,
        )
    }

    public suspend fun getTrace(traceId: String): ru.workinprogress.tracy.wire.TraceView {
        val view = traces.load(traceId)
        remember(view.roots.flatMap { it.allEntryIds() } + view.looseLogs.map { it.entryId })
        return view
    }

    public suspend fun searchSpans(
        service: String? = null,
        name: String? = null,
        minDurationMs: Int? = null,
        onlyErrors: Boolean = false,
        since: Long,
        until: Long,
        limit: Int = 50,
    ): ru.workinprogress.tracy.server.trace.SpanSearchResult = spans.search(service, name, minDurationMs, onlyErrors, since, until, limit)

    public suspend fun getEntity(
        key: String,
        value: String,
        since: Long,
        until: Long,
        limit: Int = 100,
    ): Any =
        try {
            val timeline = entities.timeline(key, value, since, until, limit)
            remember(timeline.touches.mapNotNull { it.entryId })
            timeline
        } catch (unknown: UnknownEntityKey) {
            // Not an empty result: empty reads as "that never happened" when the truth is
            // "nobody ever indexed this key".
            McpRefusal("key is not indexed", "indexed keys: ${unknown.indexed.joinToString(", ")}")
        }

    public suspend fun topTemplates(
        service: String? = null,
        level: Level? = null,
        release: String? = null,
        since: Long,
        until: Long,
        stepMillis: Long? = null,
        limit: Int = 30,
    ): ru.workinprogress.tracy.server.query.TemplateStatsResult =
        logs.templateStats(service, level, release, since, until, stepMillis, limit)

    /**
     * Phase two. Refuses unless the report holds up, and screens every value it does release —
     * the gate can only tighten, never unlock what the screen withheld.
     */
    public suspend fun entryContent(request: ContentRequest): Any {
        when (val verdict = gate.evaluate(request)) {
            is GateVerdict.Refused -> return McpRefusal("content withheld", verdict.reason)
            GateVerdict.Allowed -> Unit
        }

        return logs.fieldValues(request.entryIds).map { (entryId, fields) ->
            // Every released value goes through the screen. Composition is one-way: passing the
            // gate never unlocks what the screen held back.
            val screened = fields.mapValues { (_, value) -> LogTrust.screen(value) }
            McpEntryContent(
                entryId = entryId,
                fields = fields.filterKeys { screened.getValue(it).safe },
                withheld = screened.filterValues { !it.safe }.keys.toList(),
                rules = screened.values.flatMap { it.rules }.distinct(),
            )
        }
    }
}

private fun LogHit.toMcp(): McpLogLine {
    // The template a developer wrote is trusted; an interpolated message is data and is screened
    // like a value (research D8, risk 4).
    val screen = if (untrusted) LogTrust.screen(message) else ScreenResult.SAFE
    return McpLogLine(
        entryId = entryId,
        ts = ts,
        service = service,
        level = level,
        logger = logger,
        templateId = templateId,
        message = if (screen.safe) message else "",
        traceId = traceId,
        fieldKeys = fieldKeys,
        redacted = redacted,
        withheld = !screen.safe,
        withheldBy = screen.rules,
    )
}

private fun ru.workinprogress.tracy.wire.TraceNode.allEntryIds(): List<Long> =
    logs.map { it.entryId } + children.flatMap { it.allEntryIds() }
