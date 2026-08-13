package ru.workinprogress.tracy.server.ingest

import ru.workinprogress.tracy.server.db.BatchHeader
import ru.workinprogress.tracy.server.db.IngestRepository
import ru.workinprogress.tracy.server.db.WriteResult
import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.EntityRef
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.TemplateCount

/**
 * Accepting one batch — the decisions, without the SQL.
 *
 * This used to be sixty-five lines inside `IngestRepository.write`, which made a repository the
 * place where clock-skew policy, idempotency and the meaning of each line kind were decided. The
 * SQL stayed where it was; only the sequence moved.
 *
 * The transaction did **not** move with it. A use case calling repository methods that each open
 * their own transaction would read better on a diagram and be wrong: the batch marker has to land
 * with the records it marks, or a retry after a partial failure writes them twice. So the envelope
 * stays in the repository and this class runs inside it.
 */
public class IngestBatchUseCase(
    private val repository: IngestRepository,
    private val clock: () -> Long,
) {
    public suspend operator fun invoke(
        header: BatchHeader,
        lines: List<BatchLine>,
    ): WriteResult {
        val now = clock()

        // Clock skew is recorded rather than corrected: the trace timeline is assembled from
        // several pods, and a silently "fixed" timestamp would reorder cause and effect without
        // saying so (research risk 6).
        val sourceTs = lines.firstNotNullOfOrNull { it.sourceTimestamp() }
        val skew = if (sourceTs == null) 0L else now - sourceTs

        return repository.transaction {
            val serviceId = repository.serviceId(this, header.service, now)
            val instanceId = repository.instanceId(this, serviceId, header.instance, now, skew)

            // Idempotent per (instance, seq): an agent that never saw the 202 retries the same
            // batch, and the protocol promises the retry is free rather than doubled.
            if (repository.alreadyStored(this, instanceId, header.seq)) {
                return@transaction WriteResult(accepted = 0, duplicate = true)
            }

            var accepted = 0
            for (line in lines) {
                when (line) {
                    is LogRecord -> repository.writeRecord(this, serviceId, instanceId, header, line, now)
                    is Span -> repository.writeSpan(this, serviceId, instanceId, line, now)
                    is EntityRef -> repository.writeEntityRef(this, serviceId, instanceId, line, null)
                    is TemplateCount -> repository.writeCounter(this, serviceId, header, line)
                }
                accepted++
            }

            if (header.producedBytes > 0 || header.dropped > 0) {
                repository.recordProduced(this, serviceId, now, header.producedBytes, header.dropped)
            }
            repository.markBatch(this, instanceId, header.seq, now)

            WriteResult(accepted, duplicate = false)
        }
    }
}

private fun BatchLine.sourceTimestamp(): Long? =
    when (this) {
        is LogRecord -> ts
        is Span -> ts
        is EntityRef -> ts
        is TemplateCount -> windowStart
    }
