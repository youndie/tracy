package io.github.youndie.tracy.agent

import io.github.youndie.tracy.wire.BatchLine
import io.github.youndie.tracy.wire.NdJson
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.onTimeout
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
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
 *   an agent that retries them forever stops sending everything else;
 * - **the batch is cut to size here, by encoding it.** The buffer bounds itself with an estimate,
 *   on purpose — encoding on the caller's thread is what risk 1 forbids — but the server checks the
 *   body it receives. This is the one place that can measure what the server will measure, and a
 *   `413` under the rule above is a whole batch lost with no way to ask for it again.
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

    /**
     * Records too large to fit a batch on their own — a stack trace of a megabyte. Counted apart
     * from [rejected] because this is the only loss the agent cannot split its way out of, and the
     * number is the difference between "the server refused us" and "we produced something that
     * cannot be sent".
     */
    public var oversized: Int = 0
        private set

    /**
     * Starts the loop, and **returns itself** so the caller ends up holding the thing that has to be
     * stopped.
     *
     * That return is the whole of issue #32. [stop] was written for exactly the moment a pod is
     * asked to end, and by default nobody called it: the documented wiring was
     * `TracyDelivery(agent, config).start(this)`, which gives the caller no reason to keep a
     * reference, and without a reference `stop` cannot be called at all. The most complete service
     * in the portfolio wrote precisely that line, and every shutdown lost up to one flush interval
     * of records — including the records explaining the shutdown.
     *
     * [Application.startTracyDelivery] is the shorter form for a service that has no ordered
     * shutdown of its own.
     */
    public fun start(scope: CoroutineScope): TracyDelivery {
        check(job == null) { "delivery already started" }
        agent.onUrgent = ::requestFlush
        job = scope.launch { loop() }
        return this
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
     *
     * **Calling it twice is not an error**: the second call finds no loop, and its flush finds an
     * empty buffer. A service that takes the `ApplicationStopping` subscription of
     * `startTracyDelivery` *and* stops the delivery from its own shutdown sequence therefore pays
     * one wasted attempt rather than meeting a crash.
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
        val queued: List<BatchLine>
        val counters: BufferCounters

        if (pending.isNotEmpty()) {
            queued = pending
            counters = pendingCounters
        } else {
            queued = agent.drainBatch()
            if (queued.isEmpty()) return null
            // Taken with the batch, not per attempt: counters reset when read, so reading them
            // again on a retry would report the produced bytes of a batch that was never new.
            counters = agent.counters()
        }

        // The drain bounded the batch by `RecordBuffer.estimateBytes`, which exists to bound memory
        // without encoding on the caller's thread, and undercounts the wire by a quarter to a half
        // — `ix`, `r` and the exception class are not in it at all, and `String.length` counts
        // UTF-16 units where the body is UTF-8. The server measures the body. With both limits at
        // 1 MiB by default, that difference is a `413`, which the protocol says not to retry, so a
        // full batch was discarded whole — worst exactly while working off a backlog, where every
        // batch is full.
        val split = NdJson.splitByBytes(queued, config.maxBatchBytes)
        val batch = split.batches.first()
        val rest = split.batches.drop(1).flatten()

        // The one loss that splitting cannot prevent: a single record larger than the limit. It is
        // still sent — the server's own limit may be higher than ours — but it is counted here,
        // because `rejected` would not say why it could not be delivered.
        if (batch.size == 1 && NdJson.encodeLine(batch.first()).encodeToByteArray().size > config.maxBatchBytes) {
            oversized++
        }

        return when (val result = sender.send(batch, seq.fetchAndAdd(1), counters)) {
            is SendResult.Accepted -> {
                carryOver(rest)
                malformed += result.malformed
                agent.applySuppressed(result.suppressedKeys)
                attempt = 0
                result
            }

            is SendResult.Retriable -> {
                // The part that failed goes back at the front: records stay in order, and the
                // batch the server never confirmed exists nowhere else.
                pending = batch + rest
                pendingCounters = counters
                delay(backoff.delayFor(attempt))
                attempt++
                result
            }

            is SendResult.Rejected -> {
                carryOver(rest)
                rejected += batch.size
                attempt = 0
                result
            }
        }
    }

    /**
     * Keeps what the split left over for the next cycle, and asks for that cycle now.
     *
     * Without the wake a backlog would leave at one batch per `flushInterval`, which is how it
     * behaved when a drain produced exactly one batch; the difference is that the leftovers are
     * already out of the buffer, so waiting a second per part delays records that are no longer
     * bounded by anything.
     */
    private fun carryOver(rest: List<BatchLine>) {
        pending = rest
        // Already sent with the first part, and they reset when read.
        pendingCounters = BufferCounters(0, 0)
        if (rest.isNotEmpty()) requestFlush()
    }
}
