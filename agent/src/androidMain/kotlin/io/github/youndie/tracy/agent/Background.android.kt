package io.github.youndie.tracy.agent

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.launch

// The process lifecycle, not an activity's: `ON_STOP` here means no activity of the app is visible,
// which is the moment the app may be killed without another callback. Observers are added on the main
// thread, as the lifecycle requires.
public actual fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable {
    val observer =
        object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) {
                requestFlush()
            }
        }
    val scope = MainScope()
    scope.launch(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.addObserver(observer) }
    return AutoCloseable {
        scope.launch(Dispatchers.Main) { ProcessLifecycleOwner.get().lifecycle.removeObserver(observer) }
    }
}

internal actual fun HttpRequestBuilder.platformIngestOptions() = Unit

internal actual val PLATFORM_MAX_BATCH_BYTES: Int? = null
