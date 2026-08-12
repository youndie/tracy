package ru.workinprogress.tracy.wire

import kotlinx.serialization.Serializable

/**
 * What `GET /api/traces/{traceId}` returns: a tree of spans with the log records written inside
 * each of them.
 *
 * The computed fields are the interesting part. Each one distinguishes a kind of *absence*, and
 * they mean different things — a reader who cannot tell them apart concludes "it did not happen"
 * from data that says "it is not known".
 */
@Serializable
public data class TraceView(
    public val traceId: String,
    public val roots: List<TraceNode>,
    /**
     * Records that carry no span id — written from non-suspend code, where the trace cannot be
     * recovered (research 1.3). They belong to the trace but to no node in it, and dropping them
     * would lose data for a documented platform limit.
     */
    public val looseLogs: List<TraceLogLine> = emptyList(),
    public val truncated: Boolean = false,
    public val remaining: Int = 0,
)

@Serializable
public data class TraceNode(
    public val spanId: String,
    public val parentSpanId: String? = null,
    public val service: String,
    public val name: String,
    public val kind: SpanKind,
    public val ts: Long,
    public val durationMs: Int? = null,
    public val status: Int? = null,
    public val error: Boolean = false,
    /**
     * Duration of this span minus the sum of its known children: "something happened here that
     * nobody instrumented". Not an error, and not nothing — tracy shows unattributed time rather
     * than pretending the parent was busy with what it could see (research D1).
     */
    public val unattributedMs: Int? = null,
    /**
     * A client span with no server span on the other side. The call happened; the callee stored
     * nothing — because it was sampled away, is not instrumented, or was down. **A missing link,
     * not a missing call**, and the difference decides whether a reader concludes "that service
     * was not involved".
     */
    public val noRemoteData: Boolean = false,
    /** Flushed on a graceful shutdown without ever closing. On SIGKILL nothing arrives at all. */
    public val unterminated: Boolean = false,
    /** Parent did not arrive; hung off the root rather than discarded — it is the trace's evidence. */
    public val orphan: Boolean = false,
    public val children: List<TraceNode> = emptyList(),
    public val logs: List<TraceLogLine> = emptyList(),
)

@Serializable
public data class TraceLogLine(
    public val entryId: Long,
    public val ts: Long,
    public val service: String,
    public val level: Level,
    public val logger: String,
    /** The developer's template for a structured record; the raw text when [untrusted]. */
    public val message: String,
    public val untrusted: Boolean = false,
    public val fieldKeys: List<String> = emptyList(),
    /** Names whose values were masked — so a hidden value is never mistaken for an absent one. */
    public val redacted: List<String> = emptyList(),
)
