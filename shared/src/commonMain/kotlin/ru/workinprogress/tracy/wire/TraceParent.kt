package ru.workinprogress.tracy.wire

import kotlin.random.Random

/**
 * W3C Trace Context `traceparent`: `version-traceId-parentId-flags`.
 *
 * Implemented literally against https://www.w3.org/TR/trace-context/ — the field widths and the
 * all-zero rules are load bearing, because getting them wrong makes tracy a silently incompatible
 * participant in someone else's trace rather than a broken one.
 */
public data class TraceParent(
    val traceId: String,
    val parentId: String,
    val sampled: Boolean,
) {
    public fun toHeader(): String {
        val flags = if (sampled) "01" else "00"
        return "$VERSION-$traceId-$parentId-$flags"
    }

    /** The header this service sends onward: same trace, our span becomes the parent. */
    public fun childHeader(
        ourSpanId: String,
        sampled: Boolean = this.sampled,
    ): String {
        val flags = if (sampled) "01" else "00"
        return "$VERSION-$traceId-$ourSpanId-$flags"
    }

    public companion object {
        public const val HEADER: String = "traceparent"
        public const val TRACESTATE_HEADER: String = "tracestate"

        private const val VERSION = "00"
        private const val TRACE_ID_LEN = 32
        private const val SPAN_ID_LEN = 16

        private val ZERO_TRACE = "0".repeat(TRACE_ID_LEN)
        private val ZERO_SPAN = "0".repeat(SPAN_ID_LEN)

        /**
         * Returns null for anything the spec calls invalid. A caller must treat null as "start a
         * new trace", never as "reject the request": an unparsable header is the upstream's
         * problem, not this request's.
         */
        public fun parse(header: String?): TraceParent? {
            if (header == null) return null
            val parts = header.split('-')
            if (parts.size != 4) return null

            val (version, traceId, parentId, flags) = parts
            if (!isLowerHex(version, 2) || version == "ff") return null
            // Version 00 defines exactly four fields; trailing content is a different version.
            if (version != VERSION) return null
            if (!isLowerHex(traceId, TRACE_ID_LEN) || traceId == ZERO_TRACE) return null
            if (!isLowerHex(parentId, SPAN_ID_LEN) || parentId == ZERO_SPAN) return null
            if (!isLowerHex(flags, 2)) return null

            val sampled = (hexDigit(flags[1]) and 0x1) == 0x1
            return TraceParent(traceId, parentId, sampled)
        }

        public fun newTrace(
            sampled: Boolean = false,
            random: Random = Random,
        ): TraceParent = TraceParent(randomHex(TRACE_ID_LEN, random), randomHex(SPAN_ID_LEN, random), sampled)

        public fun newSpanId(random: Random = Random): String = randomHex(SPAN_ID_LEN, random)

        private fun randomHex(
            length: Int,
            random: Random,
        ): String {
            // All-zero is invalid, and the odds are negligible rather than zero — so handle it.
            while (true) {
                val s = buildString(length) { repeat(length) { append(HEX[random.nextInt(16)]) } }
                if (s.any { it != '0' }) return s
            }
        }

        private const val HEX = "0123456789abcdef"

        private fun isLowerHex(
            s: String,
            length: Int,
        ): Boolean = s.length == length && s.all { it in '0'..'9' || it in 'a'..'f' }

        private fun hexDigit(c: Char): Int = if (c in '0'..'9') c - '0' else c - 'a' + 10
    }
}
