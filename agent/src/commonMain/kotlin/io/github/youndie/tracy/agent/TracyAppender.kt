package io.github.youndie.tracy.agent

import io.github.oshai.kotlinlogging.Appender
import io.github.oshai.kotlinlogging.KLoggingEvent
import io.github.youndie.tracy.wire.Level

/**
 * Captures logs written through kotlin-logging by somebody else's code.
 *
 * Two properties are not negotiable:
 *
 * - the previous appender still runs, so stdout keeps working. tracy is not the only copy of the
 *   logs and must not become one (research D10): an in-process buffer cannot survive a SIGKILL,
 *   and stdout can;
 * - records captured this way carry **no trace**. `Appender.log` is not suspend and
 *   `KLoggingEvent` has no trace id in it — on Kotlin/Native there is nowhere to recover one from
 *   (research 1.3). This is the documented limit, and pretending otherwise would be worse than
 *   the limit itself.
 */
public class TracyAppender(
    private val sink: RecordSink,
    private val delegate: Appender,
) : Appender {
    @Suppress(
        "ktlint:kapkan:swallowed-failure",
        "the appender's contract: the host's line may not be lost and neither may its call",
    )
    override fun log(loggingEvent: KLoggingEvent) {
        // The host's own output first: whatever happens next must not cost it a line.
        runCatching { delegate.log(loggingEvent) }
        sink.capture(loggingEvent)
    }
}

/** Whether a kotlin-logging event at [level] would be kept by this sink. */
internal fun RecordSink.wants(level: io.github.oshai.kotlinlogging.Level): Boolean =
    level.toTracy()?.let(::isEnabled) ?: false

/**
 * Hands one captured kotlin-logging event to the sink: the half of [TracyAppender] that is
 * tracy's, shared with the logger wrapper used where direct logging is not the active mechanism.
 */
@Suppress(
    "ktlint:kapkan:swallowed-failure",
    "the host's call may not be lost because tracy failed to take a copy of it",
)
internal fun RecordSink.capture(loggingEvent: KLoggingEvent) {
    val level = loggingEvent.level.toTracy() ?: return
    if (!isEnabled(level)) return

    val builder = LogBuilder()
    loggingEvent.payload?.forEach { (key, value) ->
        if (value != null) builder.field(key, value.toString())
    }

    runCatching {
        accept(
            level = level,
            logger = loggingEvent.loggerName,
            message = loggingEvent.message.orEmpty(),
            cause = loggingEvent.cause,
            builder = builder,
            trace = null,
            // Captured, not written through tracy's API: the framework already substituted
            // its arguments into this string.
            untrusted = true,
        )
    }
}

private fun io.github.oshai.kotlinlogging.Level.toTracy(): Level? =
    when (this) {
        io.github.oshai.kotlinlogging.Level.TRACE -> Level.TRACE
        io.github.oshai.kotlinlogging.Level.DEBUG -> Level.DEBUG
        io.github.oshai.kotlinlogging.Level.INFO -> Level.INFO
        io.github.oshai.kotlinlogging.Level.WARN -> Level.WARN
        io.github.oshai.kotlinlogging.Level.ERROR -> Level.ERROR
        io.github.oshai.kotlinlogging.Level.OFF -> null
    }
