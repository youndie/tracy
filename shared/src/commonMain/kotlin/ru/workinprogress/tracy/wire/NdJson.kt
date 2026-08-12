package ru.workinprogress.tracy.wire

/** Result of parsing a batch: logs never fail as a unit. */
public data class DecodedBatch(
    val lines: List<BatchLine>,
    /** Lines that could not be parsed. Skipped, never fatal — and never silent. */
    val malformed: Int,
)

public data class BatchSplit(
    val batches: List<List<BatchLine>>,
    /**
     * Lines that do not fit into `maxBytes` on their own — a stack trace can be tens of kilobytes.
     * They are not dropped and not truncated: each goes as its own batch, and the count gives the
     * agent a reason to notice. Silently losing data inside an observability tool is not an option.
     */
    val oversized: Int,
)

public object NdJson {
    public fun encodeLine(line: BatchLine): String = TracyJson.encodeToString<BatchLine>(line)

    public fun encodeBatch(lines: List<BatchLine>): String = lines.joinToString("\n") { encodeLine(it) }

    /**
     * A line that fails to parse is counted and skipped; the rest of the batch is stored.
     * See docs/api/protocol-ingest.md — "logs are not a transaction".
     */
    public fun decodeBatch(text: String): DecodedBatch {
        val lines = mutableListOf<BatchLine>()
        var malformed = 0
        for (raw in text.lineSequence()) {
            if (raw.isBlank()) continue
            val parsed = runCatching { TracyJson.decodeFromString<BatchLine>(raw) }.getOrNull()
            if (parsed == null) malformed++ else lines += parsed
        }
        return DecodedBatch(lines, malformed)
    }

    public fun splitByBytes(
        lines: List<BatchLine>,
        maxBytes: Int,
    ): BatchSplit {
        require(maxBytes > 0) { "maxBytes must be positive" }

        val batches = mutableListOf<List<BatchLine>>()
        var current = mutableListOf<BatchLine>()
        var currentBytes = 0
        var oversized = 0

        for (line in lines) {
            val size = encodeLine(line).encodeToByteArray().size

            if (size > maxBytes) {
                if (current.isNotEmpty()) {
                    batches += current
                    current = mutableListOf()
                    currentBytes = 0
                }
                batches += listOf(line)
                oversized++
                continue
            }

            // +1 for the separating newline once the batch is non-empty.
            val added = if (current.isEmpty()) size else size + 1
            if (currentBytes + added > maxBytes) {
                batches += current
                current = mutableListOf()
                currentBytes = 0
            }
            current += line
            currentBytes += if (current.size == 1) size else size + 1
        }

        if (current.isNotEmpty()) batches += current
        return BatchSplit(batches, oversized)
    }
}
