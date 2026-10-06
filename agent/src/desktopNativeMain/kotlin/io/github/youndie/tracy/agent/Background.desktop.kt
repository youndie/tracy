package io.github.youndie.tracy.agent

import io.ktor.client.request.HttpRequestBuilder

// Linux and macOS native are servers here, and a server's shutdown is ordered by kore, which calls
// `stop()` in its drain stage. There is no background to go to.
public actual fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable = AutoCloseable { }

internal actual fun HttpRequestBuilder.platformIngestOptions() = Unit

internal actual val PLATFORM_MAX_BATCH_BYTES: Int? = null
