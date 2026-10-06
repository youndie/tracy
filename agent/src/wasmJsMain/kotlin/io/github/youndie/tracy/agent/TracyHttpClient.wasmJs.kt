package io.github.youndie.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.js.Js

// The browser's fetch. There is no other engine in a page, and no TLS or DNS of our own to carry.
internal actual fun tracyHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Js, configure)
