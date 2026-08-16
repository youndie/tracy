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
        //
        // Two numbers, and separating them is the whole of M-110. `received - sent` crosses the
        // two clocks once and is therefore the difference between them. `sent - oldestRecord` is
        // measured start to finish on the agent's own clock, so it is the delay — the flush wait
        // and any retry — with no clock difference in it at all.
        //
        // Before this they were one subtraction, `received - oldestRecord`, which is their sum.
        // On the stand that read 60 966 ms for two services: the backoff ceiling exactly, and not
        // a clock. A field that exists to keep ordering honest was reporting a retry as a clock.
        val sourceTs = lines.firstNotNullOfOrNull { it.sourceTimestamp() }
        val sentAt = header.sentAt

        // Unknown rather than guessed. An agent older than 0.2.1 sends no `X-Tracy-Sent`, and the
        // old subtraction would put the delivery delay back into the clock difference — the exact
        // number this task exists to stop reporting.
        val skew = if (sentAt == null) 0L else now - sentAt
        val recordAge =
            when {
                sourceTs == null -> 0L

                sentAt != null -> sentAt - sourceTs

                // No send time: the age still has a floor, it just also carries the clock
                // difference. Kept because a lag of minutes is worth seeing even approximately.
                else -> now - sourceTs
            }

        return repository.transaction {
            val serviceId = repository.serviceId(this, header.service, now)
            val instanceId = repository.instanceId(this, serviceId, header.instance, now, skew, recordAge)

            // Idempotent per (instance, run, seq): an agent that never saw the 202 retries the
            // same batch, and the protocol promises the retry is free rather than doubled.
            //
            // The run is in the key because the instance name is not enough. It is chosen by the
            // consumer, nothing makes it unique per launch, and on our own platform three
            // services took the public domain from `HOSTNAME` — so every pod generation arrived
            // as the same instance, restarted `seq` at zero and had every batch dropped as a
            // redelivery while the agent was told `202` and let the records go (M-111).
            if (repository.alreadyStored(this, instanceId, header.runId, header.seq)) {
                // Counted, because this was silent for hours. A retry duplicate is ordinary; a
                // whole generation of them is data loss, and only the number tells them apart.
                repository.countDuplicate(this, instanceId)
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
            repository.markBatch(this, instanceId, header.runId, header.seq, now)

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
