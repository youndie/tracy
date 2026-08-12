package ru.workinprogress.tracy.server.trace

import ru.workinprogress.tracy.wire.SpanKind
import ru.workinprogress.tracy.wire.TraceLogLine
import ru.workinprogress.tracy.wire.TraceNode
import ru.workinprogress.tracy.wire.TraceView

/** A span as it comes out of storage, before the tree exists. */
public data class StoredSpan(
    val spanId: String,
    val parentSpanId: String?,
    val service: String,
    val name: String,
    val kind: SpanKind,
    val ts: Long,
    val durationMs: Int?,
    val status: Int?,
    val error: Boolean,
)

/**
 * Builds the tree **on read** rather than storing it.
 *
 * Nothing about a trace is known when its parts arrive: services write independently, in any
 * order, and some of them never write at all. Assembling on read is the only version that
 * survives that, and it costs one pass over a few hundred rows.
 */
public object TraceAssembler {
    public fun assemble(
        traceId: String,
        spans: List<StoredSpan>,
        logsBySpan: Map<String, List<TraceLogLine>>,
        looseLogs: List<TraceLogLine> = emptyList(),
        truncated: Boolean = false,
        remaining: Int = 0,
    ): TraceView {
        val byId = spans.associateBy { it.spanId }
        val childrenOf = spans.groupBy { it.parentSpanId }

        // A client span is answered by a server span whose parent is that client span. Without
        // this, a lost link is indistinguishable from a call that never happened.
        val answeredParents =
            spans
                .filter { it.kind == SpanKind.SERVER && it.parentSpanId != null }
                .mapNotNull { it.parentSpanId }
                .toSet()

        fun build(span: StoredSpan): TraceNode {
            val children = (childrenOf[span.spanId] ?: emptyList()).sortedBy { it.ts }.map(::build)
            val knownChildMs = children.sumOf { it.durationMs ?: 0 }
            val unattributed =
                span.durationMs?.let { total -> (total - knownChildMs).takeIf { it > 0 && children.isNotEmpty() } }

            return TraceNode(
                spanId = span.spanId,
                parentSpanId = span.parentSpanId,
                service = span.service,
                name = span.name,
                kind = span.kind,
                ts = span.ts,
                durationMs = span.durationMs,
                status = span.status,
                error = span.error,
                unattributedMs = unattributed,
                noRemoteData = span.kind == SpanKind.CLIENT && span.spanId !in answeredParents,
                unterminated = span.durationMs == null,
                orphan = span.parentSpanId != null && span.parentSpanId !in byId,
                children = children,
                logs = logsBySpan[span.spanId].orEmpty().sortedBy { it.ts },
            )
        }

        // Roots are the spans whose parent is absent from this trace: either a genuine root, or
        // an orphan whose parent never arrived. Both are shown; discarding the second would throw
        // away the only trace of a lost link.
        val roots =
            spans
                .filter { it.parentSpanId == null || it.parentSpanId !in byId }
                .sortedBy { it.ts }
                .map(::build)

        return TraceView(
            traceId = traceId,
            roots = roots,
            looseLogs = looseLogs.sortedBy { it.ts },
            truncated = truncated,
            remaining = remaining,
        )
    }
}
