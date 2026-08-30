package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.darwin.Darwin

/**
 * Darwin on iOS, because Curl is not there to choose.
 *
 * `ktor-client-curl` publishes no Apple mobile artifact — `ktor-client-curl-iosarm64` is a 404 in
 * Central — so the engine that carries the desktop targets simply does not exist on a phone. Darwin
 * wraps `NSURLSession`, which is the platform's own client: no bundled TLS to keep current, and
 * proxy and certificate settings come from the device rather than from us.
 *
 * Desktop native deliberately stays on Curl rather than moving here too. That klib carries a static
 * libcurl and libssl and resolves host names itself, both verified rather than assumed
 * (research 1.5), and changing the desktop engine would reopen questions that are already closed.
 */
internal actual fun tracyHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient =
    HttpClient(Darwin, configure)
