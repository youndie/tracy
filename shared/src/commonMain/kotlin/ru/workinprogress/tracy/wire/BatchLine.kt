package ru.workinprogress.tracy.wire

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonContentPolymorphicSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonPrimitive

/**
 * One NDJSON line of an ingest batch. Records, spans and counters travel in a single stream so
 * that everything belonging to one request lands in one transaction — there is never a state
 * where the span exists but its log records do not.
 *
 * The discriminator is `k`, and its *absence* means a log record: the most frequent line does not
 * pay for the field. See docs/api/protocol-ingest.md.
 */
@Serializable(with = BatchLineSerializer::class)
public sealed interface BatchLine

/** Values of fields are data, never trusted. See research D8. */
public typealias Fields = Map<String, JsonPrimitive>

@Serializable
public data class ExceptionInfo(
    @SerialName("c") val className: String,
    @SerialName("m") val message: String? = null,
    @SerialName("s") val stackTrace: String? = null,
)

@Serializable
public data class LogRecord(
    /** Epoch millis by the *source* clock. */
    @SerialName("t") val ts: Long,
    /** Monotonic record number within the instance: orders records regardless of clock skew. */
    @SerialName("n") val seq: Long,
    @SerialName("l") val level: Level,
    @SerialName("g") val logger: String,
    /**
     * Developer-written text for a structured call, and therefore trusted; interpolated messages
     * set [untrusted] and are handled as data. Redaction has already run on this string by the
     * time it gets here — see research D8/D11 and section 1.10.
     */
    @SerialName("m") val message: String,
    @SerialName("u") val untrusted: Int? = null,
    @SerialName("f") val fields: Fields? = null,
    @SerialName("tr") val traceId: String? = null,
    @SerialName("sp") val spanId: String? = null,
    @SerialName("e") val exception: ExceptionInfo? = null,
    /** Names whose values were redacted; `<message>` means the message text itself was. */
    @SerialName("r") val redacted: List<String>? = null,
    /** Names of fields marked as entity keys. */
    @SerialName("ix") val indexed: List<String>? = null,
) : BatchLine {
    public val isUntrustedMessage: Boolean get() = untrusted == 1

    public companion object {
        public const val REDACTED_MESSAGE: String = "<message>"
    }
}

@Serializable
public enum class SpanKind {
    @SerialName("server")
    SERVER,

    @SerialName("client")
    CLIENT,

    @SerialName("internal")
    INTERNAL,
}

@Serializable
public data class Span(
    @SerialName("tr") val traceId: String,
    @SerialName("sp") val spanId: String,
    @SerialName("pp") val parentSpanId: String? = null,
    /** Route template for server/client spans — never the raw path, which explodes cardinality. */
    @SerialName("nm") val name: String,
    @SerialName("ki") val kind: SpanKind,
    @SerialName("t") val ts: Long,
    /** Absent means the span was never closed: flushed on graceful shutdown, not garbage. */
    @SerialName("d") val durationMs: Int? = null,
    @SerialName("st") val status: Int? = null,
    @SerialName("er") val error: Int? = null,
    @SerialName("f") val fields: Fields? = null,
    /**
     * Kept last on purpose: it is a wire detail, not something a caller passes. Forced onto the
     * wire with [EncodeDefault] because kotlinx.serialization skips values equal to the default,
     * and a discriminator that silently disappears is exactly the MCP SDK bug from research 1.8.
     */
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("k") val k: String = LineKind.SPAN,
) : BatchLine {
    public val isUnterminated: Boolean get() = durationMs == null
}

/**
 * Counters are sent regardless of sampling — that is the whole point of them (research D13).
 * Aggregated per service; instances are summed on write, like metrik sums windows.
 */
@Serializable
public data class TemplateCount(
    /** Start of the minute window, epoch millis. */
    @SerialName("t") val windowStart: Long,
    @SerialName("m") val template: String,
    @SerialName("l") val level: Level,
    @SerialName("c") val count: Int,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("k") val k: String = LineKind.COUNTER,
) : BatchLine

/**
 * A reference to a business entity, sent **without** a record body.
 *
 * This is what makes research D12 work at all. References are exempt from sampling, so when a
 * trace is dropped the body goes but the reference stays: the server stores it with a null
 * `entry_id`, and "cart 12345 was touched by orders-api at 14:03 in trace X" remains answerable.
 * Without this line kind, the only way to keep a reference would be to keep the whole record,
 * which is precisely the cost sampling exists to avoid.
 */
@Serializable
public data class EntityRef(
    @SerialName("tr") val traceId: String,
    @SerialName("key") val key: String,
    @SerialName("val") val value: String,
    @SerialName("t") val ts: Long,
    @EncodeDefault(EncodeDefault.Mode.ALWAYS)
    @SerialName("k") val k: String = LineKind.ENTITY_REF,
) : BatchLine

internal object BatchLineSerializer : JsonContentPolymorphicSerializer<BatchLine>(BatchLine::class) {
    override fun selectDeserializer(element: JsonElement): KSerializer<out BatchLine> {
        val kind = (element as? JsonObject)?.get("k")?.jsonPrimitive?.content
        return when (kind) {
            LineKind.SPAN -> Span.serializer()
            LineKind.COUNTER -> TemplateCount.serializer()
            LineKind.ENTITY_REF -> EntityRef.serializer()
            else -> LogRecord.serializer()
        }
    }
}
