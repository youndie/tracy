package ru.workinprogress.tracy.server.query

import io.ktor.resources.Resource

/**
 * The read API as types rather than strings.
 *
 * This is the one item of M9 that had already cost something. `docs/api/endpoint-query.md`
 * promised a `cursor` parameter on `/api/logs` that never existed in the code, and the drift
 * surfaced only during the M8 documentation pass, several milestones later. A path and its
 * parameters written as a class cannot diverge from the document quietly: the document is
 * generated from — or checked against — something the compiler also reads.
 *
 * The 25 hand-written `queryParameters["…"]` reads that these replace were not merely verbose.
 * Each one decided a default and a parse failure on its own, in the body of the handler, where a
 * reader looking for the contract had to find them all.
 */
@Resource("/api/logs")
public class LogsResource(
    public val since: Long? = null,
    public val until: Long? = null,
    public val service: String? = null,
    public val instance: String? = null,
    public val level: String? = null,
    public val templateId: Long? = null,
    /** Substring of the message template, at least three characters — the index is trigram-based. */
    public val q: String? = null,
    public val exceptionClass: String? = null,
    public val traceId: String? = null,
    public val entityKey: String? = null,
    public val entityValue: String? = null,
    public val limit: Int = 100,
)

@Resource("/api/templates")
public class TemplatesResource(
    public val since: Long? = null,
    public val until: Long? = null,
    public val service: String? = null,
    public val level: String? = null,
    public val release: String? = null,
    /** Bucket width in millis; absent means totals over the whole window. */
    public val step: Long? = null,
    public val limit: Int = 50,
)

@Resource("/api/services")
public class ServicesResource

@Resource("/api/entities/{key}")
public class EntitiesResource(
    public val key: String,
) {
    /** Aggregation by value: which values of this key were touched most. */
    @Resource("top")
    public class Top(
        public val parent: EntitiesResource,
        public val since: Long? = null,
        public val until: Long? = null,
        public val limit: Int = 20,
    )

    /**
     * Releasing a key from the breaker. `POST` because it changes server state, and idempotent
     * because an operator retrying should not have to wonder whether the first attempt worked.
     */
    @Resource("unsuppress")
    public class Unsuppress(
        public val parent: EntitiesResource,
    )

    /**
     * The timeline of one entity value.
     *
     * Declared last of the three on purpose: `{value}` also matches `top` and `unsuppress`, and
     * Ktor resolves the more specific literal segments first. Keeping the order visible here is
     * cheaper than rediscovering it from a request that reached the wrong handler.
     */
    @Resource("{value}")
    public class Value(
        public val parent: EntitiesResource,
        public val value: String,
        public val since: Long? = null,
        public val until: Long? = null,
        public val limit: Int = 200,
    )
}

@Resource("/api/traces/{traceId}")
public class TraceResource(
    public val traceId: String,
)

@Resource("/api/spans")
public class SpansResource(
    public val since: Long? = null,
    public val until: Long? = null,
    public val service: String? = null,
    public val name: String? = null,
    public val minDurationMs: Int? = null,
    /**
     * Was `error=true` before M-101 and is `onlyErrors` now — the MCP tool has always called it
     * that, and two names for one filter is exactly the drift typed resources exist to stop.
     */
    public val onlyErrors: Boolean = false,
    public val limit: Int = 100,
)
