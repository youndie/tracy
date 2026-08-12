package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.curl.Curl

// The Curl klib carries static libcurl/libssl/libcrypto, so the image needs no libcurl4 —
// only ca-certificates, which OpenSSL reads from the system bundle.
//
// libcurl also resolves host names itself, which is why tracy does not need the getaddrinfo
// shim metrik had to write: that problem belongs to ktor-network sockets, not to this engine.
// Verified rather than assumed — see SenderSocketTest.
internal actual fun tracyHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(Curl, configure)
