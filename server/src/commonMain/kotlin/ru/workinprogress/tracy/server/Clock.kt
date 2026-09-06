package ru.workinprogress.tracy.server

import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/**
 * Wall clock in epoch millis.
 *
 * `expect`/`actual` over `gettimeofday` was the first attempt and did not survive: the POSIX
 * binding differs between native targets, and the widths of its fields differ too, which the
 * compiler rejects in a shared signature. The stdlib clock is common and does the same job.
 */
@OptIn(ExperimentalTime::class)
@Suppress(
    "ktlint:kapkan:wall-clock",
    "the server's clock port: everything else is handed a () -> Long that ends up here",
)
public fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
