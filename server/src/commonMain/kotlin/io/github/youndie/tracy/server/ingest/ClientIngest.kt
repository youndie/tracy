package io.github.youndie.tracy.server.ingest

import io.github.youndie.tracy.wire.BatchLine
import io.github.youndie.tracy.wire.EntityRef
import io.github.youndie.tracy.wire.LogRecord
import io.github.youndie.tracy.wire.Span
import io.github.youndie.tracy.wire.TemplateCount
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The service namespace that belongs to apps (research-clients K3).
 *
 * A client key writes only here — `app:<name>`, whatever `X-Tracy-Service` says — and the
 * installation key may not write here at all. That second half is what makes the prefix mean
 * something: a reader that sees `app:` knows every byte under it came from a process running on
 * somebody else's device, and nothing the installation wrote can be mistaken for it, or the reverse.
 */
public const val APP_SERVICE_PREFIX: String = "app:"

/** Names an operator may give an app: what ends up after `app:`, so it stays an identifier. */
internal val APP_NAME: Regex = Regex("[a-z0-9][a-z0-9-]{0,62}")

/**
 * What a client batch is allowed to put in the database (research-clients K4).
 *
 * The key that wrote it is public by definition — it ships inside the app — so nothing in the batch
 * is a developer's word for anything:
 *
 * - **a log record is untrusted, always**, including its message. On the server a structured
 *   record's message is a template compiled into our binary and handed to a reader as is (research
 *   D8). From a client it is text anyone holding the key could have typed, so it is stored the way
 *   an interpolated message is: as data, screened on the way out.
 * - **template counters are dropped.** A counter carries a template's text and lands in the table
 *   `top_templates` reads, where text is not screened — it is meant to be developer text. A
 *   client's counts can be rebuilt from its records; a client's text in that table cannot be
 *   unwritten.
 * - spans and entity references pass: their strings are names and identifiers, read as such.
 */
internal data class ClientBatch(
    val lines: List<BatchLine>,
    val droppedCounters: Int,
)

internal fun clientBatch(lines: List<BatchLine>): ClientBatch {
    var dropped = 0
    val kept =
        lines.mapNotNull { line ->
            when (line) {
                is LogRecord -> line.copy(untrusted = 1)
                is TemplateCount -> null.also { dropped++ }
                is Span, is EntityRef -> line
            }
        }
    return ClientBatch(kept, dropped)
}

/**
 * Batches per minute, per key and per instance, for client keys only.
 *
 * The installation key is not limited here: it is a secret, and its writers are our own services,
 * whose volume the breaker of research D15 and the size ceiling already shape. A client key is
 * known to anyone who unpacked the app, so how much it may write is the only thing standing between
 * a curious user and the disk.
 *
 * A fixed one-minute window rather than a token bucket: the agent sends a batch every few seconds
 * at most, so the edge of the window costs a well-behaved app nothing, and two counters per minute
 * are cheap to reason about. The per-key limit is checked first, which also bounds how many
 * instance counters a minute can create — an app inventing a new instance id per batch still runs
 * out at the key.
 */
public class ClientRateLimiter(
    private val perKey: Int,
    private val perInstance: Int,
    private val clock: () -> Long,
) {
    // Handlers run concurrently on every target; two maps and a window are not one atomic value.
    private val lock = Mutex()
    private var window = -1L
    private val byKey = HashMap<String, Int>()
    private val byInstance = HashMap<Pair<String, String>, Int>()

    /** `true` when the batch may go in; it is counted only then. */
    public suspend fun tryAcquire(
        app: String,
        instance: String,
    ): Boolean = lock.withLock { acquireLocked(app, instance) }

    private fun acquireLocked(
        app: String,
        instance: String,
    ): Boolean {
        val minute = clock() / 60_000
        if (minute != window) {
            window = minute
            byKey.clear()
            byInstance.clear()
        }
        val keyCount = byKey[app] ?: 0
        if (keyCount >= perKey) return false
        val instanceCount = byInstance[app to instance] ?: 0
        if (instanceCount >= perInstance) return false
        byKey[app] = keyCount + 1
        byInstance[app to instance] = instanceCount + 1
        return true
    }
}
