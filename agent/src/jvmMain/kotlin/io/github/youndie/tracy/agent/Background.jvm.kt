package io.github.youndie.tracy.agent

import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.runBlocking

// A desktop app on the JVM: the shutdown hook is the "you are leaving" signal. On a server the
// delivery is stopped by the service's own shutdown instead, and this is not called there.
public actual fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable {
    val hook = Thread({ runBlocking { stop() } }, "tracy-shutdown-flush")
    Runtime.getRuntime().addShutdownHook(hook)
    return AutoCloseable {
        runCatching { Runtime.getRuntime().removeShutdownHook(hook) }
    }
}

internal actual fun HttpRequestBuilder.platformIngestOptions() = Unit

internal actual val PLATFORM_MAX_BATCH_BYTES: Int? = null
