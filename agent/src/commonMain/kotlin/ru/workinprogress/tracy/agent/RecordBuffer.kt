package ru.workinprogress.tracy.agent

import kotlinx.coroutines.channels.Channel
import ru.workinprogress.tracy.wire.BatchLine
import ru.workinprogress.tracy.wire.LogRecord
import ru.workinprogress.tracy.wire.Span
import ru.workinprogress.tracy.wire.TemplateCount
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * Bounded, thread-safe hand-off between whoever logs and whoever sends.
 *
 * Thread safety comes from a [Channel] rather than a lock, for the same reason metrik chose it:
 * a lock held on the hot path of somebody else's service is a latency bug waiting to happen, and
 * `trySend` never blocks the caller.
 *
 * The limit is in **bytes and not records**: a record carrying a stack trace is easily thirty
 * times heavier than an ordinary one, so a record-count limit does not bound memory at all.
 * Sizes are *estimated* — serialising on the caller's thread would put JSON encoding on the hot
 * path, which risk 1 explicitly forbids.
 */
@OptIn(ExperimentalAtomicApi::class)
public class RecordBuffer(
    private val maxBytes: Int,
    capacity: Int = DEFAULT_CAPACITY,
) {
    private val channel = Channel<Pair<BatchLine, Int>>(capacity)
    private val pendingBytes = AtomicLong(0)
    private val droppedCount = AtomicLong(0)
    private val producedBytes = AtomicLong(0)

    /** Bytes the service produced, whatever happened to them afterwards: the "who is noisy" number. */
    public val produced: Long get() = producedBytes.load()

    /** Records the buffer had to throw away. Losing data silently is not an option here. */
    public val dropped: Long get() = droppedCount.load()

    public val pending: Long get() = pendingBytes.load()

    /**
     * Never blocks and never throws: a failing observability agent must not surface in the code
     * of the service it observes.
     */
    public fun offer(line: BatchLine): Boolean {
        val size = estimateBytes(line)
        producedBytes.fetchAndAdd(size.toLong())

        if (pendingBytes.load() + size > maxBytes) {
            droppedCount.fetchAndAdd(1)
            return false
        }
        val accepted = channel.trySend(line to size).isSuccess
        if (accepted) pendingBytes.fetchAndAdd(size.toLong()) else droppedCount.fetchAndAdd(1)
        return accepted
    }

    /** Takes everything currently queued, up to [maxBatchBytes] worth. */
    public fun drain(maxBatchBytes: Int): List<BatchLine> {
        val out = mutableListOf<BatchLine>()
        var bytes = 0
        while (true) {
            val result = channel.tryReceive()
            val (line, size) = result.getOrNull() ?: break
            out += line
            bytes += size
            pendingBytes.fetchAndAdd(-size.toLong())
            if (bytes >= maxBatchBytes) break
        }
        return out
    }

    /** Reads and resets the counters that ride along with the next batch. */
    public fun takeCounters(): BufferCounters =
        BufferCounters(
            dropped = droppedCount.exchange(0),
            producedBytes = producedBytes.exchange(0),
        )

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 8192

        /**
         * Cheap approximation of the encoded size. Exactness is not the point — bounding memory
         * without touching the serializer on the caller's thread is.
         */
        public fun estimateBytes(line: BatchLine): Int =
            when (line) {
                is LogRecord -> {
                    OVERHEAD + line.message.length + line.logger.length +
                        (line.fields?.entries?.sumOf { it.key.length + it.value.content.length + 6 } ?: 0) +
                        (line.exception?.let { 40 + (it.message?.length ?: 0) + (it.stackTrace?.length ?: 0) } ?: 0)
                }

                is Span -> {
                    OVERHEAD + line.name.length +
                        (line.fields?.entries?.sumOf { it.key.length + it.value.content.length + 6 } ?: 0)
                }

                is TemplateCount -> {
                    OVERHEAD + line.template.length
                }
            }

        private const val OVERHEAD = 80
    }
}

public data class BufferCounters(
    val dropped: Long,
    val producedBytes: Long,
)
