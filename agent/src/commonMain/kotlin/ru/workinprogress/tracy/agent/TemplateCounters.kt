package ru.workinprogress.tracy.agent

import kotlinx.coroutines.channels.Channel
import ru.workinprogress.tracy.wire.Level
import ru.workinprogress.tracy.wire.TemplateCount

/**
 * Minute-window counters per (template, level).
 *
 * These are the only numbers tracy can be honest about. Everything else on the read side is a
 * sample, so a frequency computed from stored records would be understated by `1/sampleRate` and
 * would still look like a fact (research D13). Counters are therefore incremented for every
 * record that passes the level threshold and sent **regardless of sampling**.
 *
 * The level threshold does apply: counting what was never logged would mean computing a template
 * for suppressed calls, which breaks the promise that a suppressed record costs nothing.
 *
 * Aggregation happens in the draining coroutine, so incrementing is a non-blocking hand-off and
 * never contends with another thread.
 */
public class TemplateCounters(
    capacity: Int = DEFAULT_CAPACITY,
) {
    private data class Key(
        val windowStart: Long,
        val template: String,
        val level: Level,
    )

    private val events = Channel<Key>(capacity)
    private val windows = LinkedHashMap<Key, Int>()

    /** Hot path: one small object, no locks, never blocks. */
    public fun increment(
        template: String,
        level: Level,
        nowMs: Long,
    ) {
        events.trySend(Key(windowOf(nowMs), template, level))
    }

    /**
     * Folds pending events and returns the windows that have closed. An open window is kept:
     * emitting it early would produce two rows for one minute and make the counter look like it
     * dropped.
     */
    public fun drainClosed(nowMs: Long): List<TemplateCount> {
        fold()

        val currentWindow = windowOf(nowMs)
        val closed = windows.keys.filter { it.windowStart < currentWindow }
        return closed.map { key ->
            val count = windows.remove(key) ?: 0
            TemplateCount(
                windowStart = key.windowStart,
                template = key.template,
                level = key.level,
                count = count,
            )
        }
    }

    /** Everything still held, closed or not — for a graceful shutdown. */
    public fun drainAll(): List<TemplateCount> {
        fold()
        val out =
            windows.map { (key, count) ->
                TemplateCount(key.windowStart, key.template, key.level, count)
            }
        windows.clear()
        return out
    }

    private fun fold() {
        while (true) {
            val key = events.tryReceive().getOrNull() ?: break
            windows[key] = (windows[key] ?: 0) + 1
        }
    }

    public companion object {
        public const val DEFAULT_CAPACITY: Int = 8192
        public const val WINDOW_MS: Long = 60_000

        public fun windowOf(nowMs: Long): Long = nowMs / WINDOW_MS * WINDOW_MS
    }
}
