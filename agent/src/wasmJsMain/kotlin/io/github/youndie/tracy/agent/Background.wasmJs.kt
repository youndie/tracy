package io.github.youndie.tracy.agent

import io.ktor.client.fetchOptions
import io.ktor.client.request.HttpRequestBuilder
import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.events.Event

// `visibilitychange` to hidden is the last event a page reliably gets — on a phone a tab switch or the
// home button is often the end of it — and `pagehide` covers navigation away. Both only ask for a
// flush; what makes the request survive the page is `keepalive` on every send, below.
//
// Not `navigator.sendBeacon`, which research-clients K5 first named: a beacon cannot carry headers,
// and the ingest key travels in `X-Tracy-Key`.
public actual fun TracyDelivery.flushWhenBackgrounded(): AutoCloseable {
    val onVisibility: (Event) -> Unit = { if (pageHidden()) requestFlush() }
    val onPageHide: (Event) -> Unit = { requestFlush() }
    document.addEventListener("visibilitychange", onVisibility)
    window.addEventListener("pagehide", onPageHide)
    return AutoCloseable {
        document.removeEventListener("visibilitychange", onVisibility)
        window.removeEventListener("pagehide", onPageHide)
    }
}

internal actual fun HttpRequestBuilder.platformIngestOptions() {
    fetchOptions { keepalive = true }
}

// The browser's keepalive quota is 64 KiB of body across a page's in-flight keepalive requests; a
// batch below it, with room for headers, leaves the page.
internal actual val PLATFORM_MAX_BATCH_BYTES: Int? = 60 * 1024

// The DOM bindings in kotlinx-browser do not carry `visibilityState`.
@OptIn(kotlin.js.ExperimentalWasmJsInterop::class)
private fun pageHidden(): Boolean = js("document.visibilityState === 'hidden'")
