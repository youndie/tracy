package ru.workinprogress.tracy.agent

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import ru.workinprogress.tracy.wire.BatchLine
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/**
 * The loop that actually delivers batches.
 *
 * Everything around it existed before this: [Sender] sends one batch, [Backoff] schedules the
 * retries, [TracyAgent.drainBatch] hands over what to send. Nothing joined them and nothing ran
 * on a timer, so `flushInterval` was a configuration value no code read.
 *
 * Three rules, and each one is a decision rather than an implementation detail:
 *
 * - **one batch in flight at a time.** libcurl runs its own thread per transfer (research 1.5,
 *   measured in M-26), so parallel sends buy latency at the price of threads inside a service
 *   that did not ask for them;
 * - **a retriable failure keeps the batch.** The protocol promises `202` means stored, so
 *   anything weaker means the records still exist only here. The same batch is retried before
 *   anything new is drained, which also keeps records in order;
 * - **a rejection drops the batch.** `400`/`401`/`413` will not improve on the next attempt, and
 *   an agent that retries them forever stops sending everything else.
 */
@OptIn(ExperimentalAtomicApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
public class TracyDelivery(
    private val agent: TracyAgent,
    private val config: AgentConfig,
    private val sender: Sender = Sender(config),
    private val backoff: Backoff = Backoff(),
) {
    private val seq = AtomicLong(0)

    /** How many retriable failures in a row. [Backoff] is a pure schedule and holds no state. */
    private var attempt = 0

    /** Conflated: ten errors in a row are one reason to flush, not ten queued flushes. */
    private val wake = Channel<Unit>(Channel.CONFLATED)

    private var job: Job? = null

    /** A batch that failed retriably. Sent again before anything new is drained. */
    private var pending: List<BatchLine> = emptyList()
    private var pendingCounters: BufferCounters = BufferCounters(0, 0)

    /** Counted rather than logged: an observability agent that logs its own failures feeds itself. */
    public var rejected: Int = 0
        private set
    public var malformed: Int = 0
        private set

    public fun start(scope: CoroutineScope) {
        check(job == null) { "delivery already started" }
        agent.onUrgent = ::requestFlush
        job = scope.launch { loop() }
    }

    /**
     * Asks for a flush now instead of at the next tick. Called on `ERROR`: the record that is
     * worth waiting a second for is exactly the one nobody wants to wait a second for.
     */
    public fun requestFlush() {
        wake.trySend(Unit)
    }

    /**
     * Stops the loop and makes one last attempt to deliver what is buffered.
     *
     * A pod is given a grace period between `SIGTERM` and `SIGKILL`, and the records produced
     * during a shutdown — the ones explaining why it shut down — are the least replaceable ones
     * in the buffer.
     */
    public suspend fun stop(grace: kotlin.time.Duration = config.flushInterval) {
        agent.onUrgent = null
        job?.cancel()
        job = null
        withTimeoutOrNull(grace) { flushOnce() }
    }

    private suspend fun loop() {
        while (kotlin.coroutines.coroutineContext[Job]?.isActive != false) {
            select<Unit> {
                wake.onReceive { }
                onTimeout(config.flushInterval.inWholeMilliseconds) { }
            }
            flushOnce()
        }
    }

    /**
     * One drain-and-send cycle. Returns the result, or null when there was nothing to send.
     *
     * Internal rather than private so a test can drive it a step at a time: a test that waits on
     * the loop's own timer measures the timer.
     */
    internal suspend fun flushOnce(): SendResult? {
        val batch: List<BatchLine>
        val counters: BufferCounters

        if (pending.isNotEmpty()) {
            batch = pending
            counters = pendingCounters
        } else {
            batch = agent.drainBatch()
            if (batch.isEmpty()) return null
            // Taken with the batch, not per attempt: counters reset when read, so reading them
            // again on a retry would report the produced bytes of a batch that was never new.
            counters = agent.counters()
        }

        return when (val result = sender.send(batch, seq.fetchAndAdd(1), counters)) {
            is SendResult.Accepted -> {
                pending = emptyList()
                pendingCounters = BufferCounters(0, 0)
                malformed += result.malformed
                agent.applySuppressed(result.suppressedKeys)
                attempt = 0
                result
            }

            is SendResult.Retriable -> {
                pending = batch
                pendingCounters = counters
                delay(backoff.delayFor(attempt))
                attempt++
                result
            }

            is SendResult.Rejected -> {
                pending = emptyList()
                pendingCounters = BufferCounters(0, 0)
                rejected += batch.size
                attempt = 0
                result
            }
        }
    }
}
