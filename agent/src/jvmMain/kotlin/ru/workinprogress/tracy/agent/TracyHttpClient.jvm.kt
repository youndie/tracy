package ru.workinprogress.tracy.agent

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.cio.CIO

// On the JVM CIO is fine: TLS works and the selector runs in the host's own pool.
internal actual fun tracyHttpClient(configure: HttpClientConfig<*>.() -> Unit): HttpClient = HttpClient(CIO, configure)
