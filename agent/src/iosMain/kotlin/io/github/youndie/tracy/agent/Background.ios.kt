package io.github.youndie.tracy.agent

import io.ktor.client.request.HttpRequestBuilder
import platform.Foundation.NSNotificationCenter
import platform.Foundation.NSOperationQueue
import platform.UIKit.UIApplicationDidEnterBackgroundNotification

// iOS leaves an app a few seconds after this notification before suspending it — enough for one
// batch, which is what a flush is.
public actual fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable {
    val center = NSNotificationCenter.defaultCenter
    val observer =
        center.addObserverForName(UIApplicationDidEnterBackgroundNotification, null, NSOperationQueue.mainQueue) { _ ->
            requestFlush()
        }
    return AutoCloseable { center.removeObserver(observer) }
}

internal actual fun HttpRequestBuilder.platformIngestOptions() = Unit

internal actual val PLATFORM_MAX_BATCH_BYTES: Int? = null
