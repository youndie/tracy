package io.github.youndie.tracy.agent

import io.ktor.client.request.HttpRequestBuilder

/**
 * Sends what is buffered when the app leaves the screen (research-clients K5).
 *
 * A server is told it is going away and has a grace period: `stop()` delivers the last records. An app
 * is not. It goes to the background, and from there it is suspended or killed without another word,
 * taking up to one flush interval of records with it — and the records just before a person left are
 * the ones most worth having. So the platform's own "you are leaving" signal asks for a flush:
 *
 * - Android: the process lifecycle's `ON_STOP` — no activity of the app is visible any more;
 * - iOS: `UIApplicationDidEnterBackgroundNotification`, which leaves a few seconds before suspension;
 * - browser: `visibilitychange` to hidden and `pagehide`, the last moments a page reliably runs, with
 *   every request sent `keepalive` so it outlives the page;
 * - desktop JVM: a shutdown hook that stops the delivery, which makes its last attempt;
 * - Linux and macOS native: nothing — those are servers, and a server's shutdown is ordered by kore.
 *
 * Returns the handle that removes the hook again.
 */
public expect fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable

/**
 * What a platform adds to every ingest request. Only the browser adds anything: `keepalive`, so a
 * batch sent while the page is being hidden or closed is not cancelled with it.
 */
internal expect fun HttpRequestBuilder.platformIngestOptions()

/**
 * A ceiling the platform puts on one request body, or `null` for none. The browser allows a
 * `keepalive` request 64 KiB of body in total, and a larger batch fails before it leaves the page.
 */
internal expect val PLATFORM_MAX_BATCH_BYTES: Int?
